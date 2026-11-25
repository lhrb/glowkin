import { randomBytes } from "node:crypto";
import { lstat } from "node:fs/promises";
import { createConnection, type Socket } from "node:net";
import { dirname, isAbsolute } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

type RunState = "idle" | "busy" | "done";
type Signal = RunState | "needs_permission";

function openPublisher(path: string, pane: string) {
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
      const parent = await lstat(dirname(path));
      if (!parent.isDirectory() || parent.uid !== uid || (parent.mode & 0o777) !== 0o700) {
        scheduleRetry();
        return;
      }
      const endpoint = await lstat(path);
      if (stopped) return;
      if (!endpoint.isSocket() || endpoint.uid !== uid) {
        scheduleRetry();
        return;
      }
      const connection = createConnection({ path });
      socket = connection;
      connection.unref();
      connection.on("error", () => connection.destroy());
      connection.on("close", () => {
        socket = undefined;
        writable = false;
        sent = undefined;
        scheduleRetry();
      });
      connection.on("connect", () => {
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

function requestId(event: unknown): string | undefined {
  if (event && typeof event === "object" && "requestId" in event) {
    return typeof event.requestId === "string" ? event.requestId : undefined;
  }
}

export default function (pi: ExtensionAPI) {
  const pane = process.env.TMUX_PANE;
  const path = process.env.GLOWKIN_PI_SIGNALS_SOCKET;
  if (!process.env.TMUX || !pane || !/^%[0-9]{1,10}$/.test(pane)) return;
  if (!path || !isAbsolute(path) || path.includes("\0") || Buffer.byteLength(path) > 107) return;

  let publisher: ReturnType<typeof openPublisher> | undefined;
  let run: RunState = "idle";
  let stoppedNormally = false;
  let completed = false;
  const prompts = new Set<string>();
  const publish = () => publisher?.publish(prompts.size ? "needs_permission" : run);

  // gotgenes/pi-permission-system's public channels. Only correlate the opaque
  // request IDs in memory; an unrelated decision cannot clear an active ask.
  const unsubscribePrompt = pi.events.on("permissions:ui_prompt", (event) => {
    if (!publisher) return;
    const id = requestId(event);
    if (!id) return;
    if (run === "done") run = "idle";
    prompts.add(id);
    publish();
  });
  const unsubscribeDecision = pi.events.on("permissions:decision", (event) => {
    const id = requestId(event);
    if (id && prompts.delete(id)) publish();
  });

  pi.on("session_start", (_event, ctx) => {
    if (ctx.mode === "tui") publisher = openPublisher(path, pane);
  });
  pi.on("session_shutdown", () => {
    unsubscribePrompt();
    unsubscribeDecision();
    prompts.clear();
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
