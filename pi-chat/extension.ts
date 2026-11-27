import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { boundedText, createChat, nonce, sameTarget, toMessage, type Activity, type Message } from "./chat.ts";
import { tmuxPane } from "./pane.ts";
import { activeEndpointGone, openEndpoint, prepareSocketDirectory } from "./socket.ts";

interface SubagentsService {
  listAgents(): { status: string; type: string }[];
}

function findSubagents(): SubagentsService | undefined {
  // The installed provider's getSubagentsService() reads this symbol. Capture
  // the service at TUI start rather than following other runtimes in-process.
  const key = Symbol.for("@gotgenes/pi-subagents:service");
  const service = (globalThis as Record<symbol, SubagentsService | undefined>)[key];
  return typeof service?.listAgents === "function" ? service : undefined;
}

type Session = ExtensionContext["sessionManager"];
type AgentMessage = Extract<ReturnType<Session["getBranch"]>[number], { type: "message" }>["message"];

export default function (pi: ExtensionAPI) {
  const pane = tmuxPane(process.env);
  if (!pane) return;

  let shutdown = false;
  let fromSignal = false;
  let stopInput: (() => void) | undefined;
  let stopSocket: (() => void) | undefined;
  let release: ((exited: boolean) => Promise<void>) | undefined;
  let invalidateDraft: (() => void) | undefined;
  let compacting = false;
  let prompting = false;
  let live: Message | undefined;
  let liveId: string | undefined;
  const ids = new WeakMap<AgentMessage, string>();
  const markSignal = () => { fromSignal = true; };

  function* history(session: Session): Iterable<Message> {
    if (live) yield live;
    let entry = session.getLeafEntry();
    for (let scanned = 0; entry && scanned < 1024; scanned++) {
      if (entry.type === "message") {
        const message = toMessage(ids.get(entry.message) ?? entry.id, entry.message);
        if (message) yield message;
      }
      entry = entry.parentId === null ? undefined : session.getEntry(entry.parentId);
    }
  }

  pi.on("session_start", async (_event, ctx) => {
    if (ctx.mode !== "tui" || !ctx.hasUI || !process.stdin.isTTY || !process.stdout.isTTY) return;
    // Both interactive and signal-triggered Pi shutdown use reason "quit".
    process.prependListener("SIGHUP", markSignal);
    process.prependListener("SIGTERM", markSignal);
    try {
      const target = await pane.inspect();
      if (shutdown || fromSignal || !target || !pane.foreground(target)) return;
      const directory = prepareSocketDirectory();
      if (!directory || !await activeEndpointGone(directory, target.marker) || shutdown || fromSignal) return;

      const instanceId = nonce();
      const marker = `active:${instanceId}`;
      const sessionId = ctx.sessionManager.getSessionId();
      const subagents = findSubagents();
      function activity(): Activity {
        if (compacting) return "compacting";
        if (prompting) return "respond_on_pc";
        return ctx.isIdle() ? "idle" : "working";
      }
      const chat = createChat(instanceId, target, {
        getDraft: () => ctx.ui.getEditorComponent() ? null : ctx.ui.getEditorText(),
        setDraft: (text) => {
          ctx.ui.setEditorText(text);
          // Pi's setter does not request a render. Clearing our unused footer
          // key repaints without replacing the editor or introducing UI text.
          ctx.ui.setStatus("glowkin-chat", undefined);
        },
        activity,
        queued: () => ctx.hasPendingMessages(),
        history: () => history(ctx.sessionManager),
        subagents: () => {
          try {
            return subagents?.listAgents().filter((agent) => agent.status === "running")
              .slice(0, 20).map((agent) => boundedText(agent.type, 128)) ?? null;
          } catch {
            return null;
          }
        },
      });
      const close = await openEndpoint(directory, instanceId, async (request, connected) => {
        if (request.instanceId !== instanceId || !sameTarget(request, target) || shutdown || fromSignal ||
          !await pane.validate(target, marker) || !connected() || shutdown || fromSignal ||
          ctx.sessionManager.getSessionId() !== sessionId || !pane.foreground(target)) return { status: "unavailable" };
        return chat.handle(request);
      });
      if (!close) return;
      stopSocket = close;
      if (shutdown || fromSignal) {
        close();
        stopSocket = undefined;
        return;
      }
      // Set cleanup before awaiting tmux; shutdown may start while the client
      // is waiting for its result. Clearing a marker we never owned is a no-op.
      const claimed = Promise.resolve(pane.claim(target, marker));
      release = async (exited) => {
        await claimed;
        await pane.release(target, marker, exited ? `exited:${instanceId}` : undefined);
      };
      if (!await claimed || shutdown || fromSignal) {
        if (!shutdown) {
          close();
          stopSocket = undefined;
          await release(false);
        }
        return;
      }
      invalidateDraft = chat.invalidate;
      // Rotate the revision even if a PC edit is undone between two reads.
      stopInput = ctx.ui.onTerminalInput(() => {
        chat.invalidate();
        return undefined;
      });
    } catch {
      stopSocket?.();
      stopSocket = undefined;
      await release?.(false);
      release = undefined;
    }
  });

  pi.on("session_before_compact", () => { compacting = true; invalidateDraft?.(); });
  pi.on("session_compact", () => { compacting = false; invalidateDraft?.(); });
  pi.on("session_compact_failed", () => { compacting = false; invalidateDraft?.(); });
  pi.on("ui_prompt_start", () => { prompting = true; invalidateDraft?.(); });
  pi.on("ui_prompt_end", () => { prompting = false; invalidateDraft?.(); });
  pi.on("session_tree", () => { live = undefined; liveId = undefined; invalidateDraft?.(); });
  pi.on("message_start", (event) => {
    if (event.message.role !== "assistant" || !invalidateDraft) return;
    live = undefined;
    liveId = `live-${nonce()}`;
  });
  pi.on("message_update", (event) => {
    if (event.message.role === "assistant" && liveId) live = toMessage(liveId, event.message);
  });
  pi.on("message_end", (event) => {
    if (event.message.role !== "assistant" || !liveId) return;
    ids.set(event.message, liveId);
    live = undefined;
    liveId = undefined;
  });

  pi.on("session_shutdown", async (event, ctx) => {
    if (ctx.mode !== "tui") return;
    shutdown = true;
    stopSocket?.();
    stopInput?.();
    try {
      await release?.(event.reason === "quit" && !fromSignal);
    } finally {
      process.off("SIGHUP", markSignal);
      process.off("SIGTERM", markSignal);
    }
  });
}
