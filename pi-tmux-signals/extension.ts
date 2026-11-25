import { execFile } from "node:child_process";
import { randomBytes } from "node:crypto";
import { lstat } from "node:fs/promises";
import { createConnection, type Socket } from "node:net";
import { dirname, isAbsolute } from "node:path";
import { promisify } from "node:util";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

type RunState = "idle" | "busy" | "done";
type Attention = "needs_input" | "needs_permission";
type Signal = RunState | Attention;
type SocketSource = string | (() => Promise<string | undefined>);

const runFile = promisify(execFile);

function isSocketPath(path: string): boolean {
  return isAbsolute(path) && !path.includes("\0") && Buffer.byteLength(path) <= 107;
}

async function tmuxDiscovery(tmux: string) {
  // The path itself may contain commas. Resolve only this pane's tmux server.
  const match = /^(\/.+),([1-9][0-9]*),[0-9]+$/s.exec(tmux);
  if (!match) return;
  const [, socket, pid] = match;
  async function lookup() {
    try {
      const { stdout } = await runFile("tmux", ["-N", "-S", socket, "display-message", "-p",
        "#{pid}:#{start_time}\t#{@glowkin-pi-socket}"], { timeout: 1000, maxBuffer: 4096 });
      const [generation, endpoint] = stdout.trimEnd().split("\t");
      if (!generation.startsWith(`${pid}:`) || !endpoint) return;
      return { generation, endpoint };
    } catch {
      return;
    }
  }
  const initial = await lookup();
  if (!initial) return; // No opt-in: no publisher or retry timer.
  return async () => {
    const current = await lookup();
    if (!current || current.generation !== initial.generation) return;
    const [encoded, ...identity] = current.endpoint.split(":");
    if (!/^(?:[0-9a-f]{2})+$/.test(encoded)) return;
    const path = Buffer.from(encoded, "hex").toString("utf8");
    if (!isSocketPath(path)) return;
    const endpoint = await lstat(path, { bigint: true }).catch(() => undefined);
    if (endpoint?.isSocket() && identity.join(":") === `${endpoint.dev}:${endpoint.ino}:${endpoint.ctimeNs}`) return path;
  };
}

function openPublisher(pane: string, path: SocketSource) {
  const instance = randomBytes(16).toString("hex");
  const uid = process.geteuid?.();
  let socket: Socket | undefined;
  let retry: NodeJS.Timeout | undefined;
  let stopped = false;
  let state: Signal = "idle";
  let sent: Signal | undefined;
  let writable = false;

  function flush() {
    if (!socket || !writable || sent === state) return;
    sent = state;
    writable = socket.write(`${pane}\t${instance}\t${state}\n`);
  }

  function scheduleRetry() {
    if (stopped) return;
    retry = setTimeout(() => void connect(), 1000);
    retry.unref();
  }

  async function connect() {
    try {
      const endpointPath = typeof path === "string" ? path : await path();
      if (!endpointPath) {
        scheduleRetry();
        return;
      }
      const parent = await lstat(dirname(endpointPath));
      if (!parent.isDirectory() || parent.uid !== uid || (parent.mode & 0o777) !== 0o700) {
        scheduleRetry();
        return;
      }
      const endpoint = await lstat(endpointPath);
      if (stopped) return;
      if (!endpoint.isSocket() || endpoint.uid !== uid) {
        scheduleRetry();
        return;
      }
      const connection = createConnection({ path: endpointPath });
      socket = connection;
      connection.unref();
      connection.on("error", () => connection.destroy());
      connection.on("close", () => {
        socket = undefined;
        writable = false;
        sent = undefined;
        scheduleRetry();
      });
      connection.on("connect", async () => {
        // The stable path may have been rebound while connect was in flight.
        if (typeof path === "function" && await path() !== endpointPath) {
          connection.destroy();
          return;
        }
        if (stopped || socket !== connection) return;
        writable = true;
        flush();
      });
      connection.on("drain", () => {
        writable = true;
        flush();
      });
      // The receiver sends nothing. Read EOF so a stopped receiver can reconnect.
      connection.resume();
    } catch {
      scheduleRetry();
    }
  }

  void connect();
  return {
    publish(next: Signal) {
      state = next;
      flush();
    },
    close() {
      stopped = true;
      clearTimeout(retry);
      socket?.destroy();
    },
  };
}

export default function (pi: ExtensionAPI) {
  const { TMUX: tmux, TMUX_PANE: pane, GLOWKIN_PI_SIGNALS_SOCKET: manualPath } = process.env;
  if (!tmux || !pane || !/^%[0-9]{1,10}$/.test(pane)) return;
  if (manualPath !== undefined && !isSocketPath(manualPath)) return;

  let publisher: ReturnType<typeof openPublisher> | undefined;
  let shutDown = false;
  let run: RunState = "idle";
  let stoppedNormally = false;
  let completed = false;
  const attention = new Map<string, Attention>();

  function visibleState(): Signal {
    for (const state of attention.values()) {
      if (state === "needs_permission") return state;
    }
    return attention.size ? "needs_input" : run;
  }

  const publish = () => publisher?.publish(visibleState());
  const unsubscribe = pi.events.on("glowkin:attention", (event) => {
    if (!event || typeof event !== "object" || Array.isArray(event)) return;
    const provider: unknown = Object.getOwnPropertyDescriptor(event, "provider")?.value;
    const state: unknown = Object.getOwnPropertyDescriptor(event, "state")?.value;
    if (typeof provider !== "string" || !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(provider)) return;
    if (state === "clear") {
      if (!attention.delete(provider)) return;
    } else if (state === "needs_input" || state === "needs_permission") {
      // Same-category re-reports can announce a new prompt. Supersede any
      // completion already recorded, but keep tracking future runs underneath.
      if (run === "done") run = "idle";
      attention.set(provider, state);
    } else {
      return;
    }
    publish();
  });

  const attach = (path: SocketSource) => {
    publisher = openPublisher(pane, path);
    publish(); // Also picks up attention reported by an earlier session_start handler.
  };
  pi.on("session_start", (_event, ctx) => {
    if (ctx.mode !== "tui") return;
    if (manualPath !== undefined) {
      attach(manualPath);
      return;
    }
    void tmuxDiscovery(tmux).then((discover) => {
      if (discover && !shutDown) attach(discover);
    });
  });
  pi.on("session_shutdown", () => {
    shutDown = true;
    unsubscribe();
    attention.clear();
    publisher?.close();
    publisher = undefined;
  });

  function start() {
    stoppedNormally = false;
    completed = false;
    run = "busy";
    publish();
  }
  pi.on("before_agent_start", start);
  pi.on("agent_start", start);
  pi.on("agent_end", (event) => {
    stoppedNormally = event.messages.findLast((message) => message.role === "assistant")?.stopReason === "stop";
  });
  pi.on("agent_before_settle", (event) => {
    completed = event.outcome === "completed";
  });
  pi.on("agent_settled", (event, ctx) => {
    // An earlier boundary may have continued or been truncated. Only the
    // last low-level run's normal stop at final, non-aborted settlement counts.
    run = !ctx.isIdle() ? "busy" : !event.aborted && completed && stoppedNormally ? "done" : "idle";
    stoppedNormally = false;
    completed = false;
    publish();
  });
}
