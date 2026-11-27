import { appendFileSync, existsSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { createAssistantMessageEventStream, type AssistantMessage } from "@earendil-works/pi-ai";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const provider = "capture-fixture";
const cost = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
const dir = dirname(process.cwd());
const mark = (event: string) => appendFileSync(`${dir}/trace`, `${event}\n`);

export default function (pi: ExtensionAPI) {
  let session: ExtensionContext;
  pi.on("session_start", (_event, ctx) => {
    const env = process.env;
    const allowed = new Set(`PATH SHELL SHLVL LANG TERM TERM_PROGRAM TERM_PROGRAM_VERSION COLORTERM TMUX TMUX_PANE PWD
      HOME XDG_CONFIG_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_CACHE_HOME XDG_RUNTIME_DIR TMPDIR CAPTURE_TMUX CAPTURE_ROOT GLOWKIN_PI_SIGNALS_SOCKET
      PI_CODING_AGENT_DIR PI_CODING_AGENT_SESSION_DIR PI_OFFLINE PI_TELEMETRY PI_SKIP_VERSION_CHECK
      AI_AGENT PI_CODING_AGENT PI_MANAGED_INSTALL_ROOT`.trim().split(/\s+/));
    const dirs = {
      HOME: "home", XDG_CONFIG_HOME: "config", XDG_DATA_HOME: "data", XDG_STATE_HOME: "state",
      XDG_CACHE_HOME: "cache", TMPDIR: "tmp", PI_CODING_AGENT_DIR: "agent", PI_CODING_AGENT_SESSION_DIR: "sessions",
    };
    const checks = {
      environment: Object.keys(env).every((key) => allowed.has(key)),
      directories: Object.entries(dirs).every(([key, subdir]) => env[key] === `${dir}/${subdir}`),
      cwd: dir.startsWith(`${env.CAPTURE_ROOT}/`) && ctx.cwd === `${dir}/cwd`,
      socket: env.TMUX?.split(",")[0] === env.CAPTURE_TMUX && env.CAPTURE_TMUX === `${env.CAPTURE_ROOT}/tmux`,
      runtime: env.XDG_RUNTIME_DIR === `${env.CAPTURE_ROOT}/runtime`,
      signals: !env.GLOWKIN_PI_SIGNALS_SOCKET || env.GLOWKIN_PI_SIGNALS_SOCKET === `${env.CAPTURE_ROOT}/runtime/signals.sock`,
      interactive: process.stdin.isTTY && process.stdout.isTTY && ctx.mode === "tui" && ctx.isIdle(),
      offline: env.PI_OFFLINE === "1" && env.PI_TELEMETRY === "0" && env.PI_SKIP_VERSION_CHECK === "1",
      model: ctx.model?.provider === provider && ctx.model.id === "fixture",
      ephemeral: !ctx.sessionManager.getSessionFile(),
      tools: [...pi.getActiveTools()].sort().join() === "fixture_ask,fixture_silent",
    };
    const failures = Object.entries(checks).filter(([, ok]) => !ok).map(([name]) => name);
    if (failures.length) {
      writeFileSync(`${dir}/preflight-failed`, failures.join(", "));
      return;
    }
    session = ctx;
    mark("ready");
  });

  for (const name of ["fixture_ask", "fixture_silent"]) {
    pi.registerTool({
      name, label: name, description: "A no-op; never opens files, runs commands, or uses the network.",
      parameters: Type.Object({ sample: Type.String() }),
      async execute() {
        mark(`executed ${name}`);
        return { content: [{ type: "text", text: "Fixture tool ran" }], details: {} };
      },
    });
  }
  pi.events.on("permissions:ready", () => mark("permission-ready"));
  pi.events.on("permissions:ui_prompt", () => {
    mark("permission-prompt");
    if (existsSync(`${dir}/unrelated-decision`)) {
      // Dispatch after every listener has processed the real prompt.
      queueMicrotask(() => {
        pi.events.emit("permissions:decision", { requestId: "unrelated-request-canary" });
        mark("unrelated decision");
      });
    }
  });
  pi.events.on("permissions:decision", (raw) => {
    const resolution = (raw as { resolution?: string }).resolution;
    // Record status only; never write the rest of the permission payload.
    const known = ["policy_allow", "user_approved", "user_denied"];
    mark(`decision ${resolution && known.includes(resolution) ? resolution : "other"}`);
  });
  pi.on("agent_settled", (event) => mark(`settled ${event.aborted ? "aborted" : "normal"}`));
  pi.registerCommand("fixture-dialog", {
    description: "Open a non-permission dialog",
    async handler(_args, ctx) {
      mark("dialog opening");
      await ctx.ui.confirm("Unrelated fixture dialog", "This is not a permission prompt.");
      mark("dialog closed");
    },
  });

  pi.registerProvider(provider, {
    baseUrl: "http://127.0.0.1:1", // Model metadata only. No connection is opened.
    apiKey: "dummy",
    api: provider,
    models: [{ id: "fixture", name: "Fixture", reasoning: false, input: ["text"], cost, contextWindow: 128000, maxTokens: 4096 }],
    streamSimple(model, context, options) {
      const stream = createAssistantMessageEventStream();
      const signal = options?.signal;
      void (async () => {
        let stop: AssistantMessage["stopReason"] = "stop";
        const content: AssistantMessage["content"] = [];
        if (!session) throw new Error("Fixture preflight failed");
        const latest = context.messages.at(-1);
        const input = latest?.role === "user"
          ? (typeof latest.content === "string" ? latest.content : latest.content.find((part) => part.type === "text")?.text)
          : undefined;
        const scenario = input?.split(" ")[0];
        if (scenario === "ask" || scenario === "silent") {
          stop = "toolUse";
          content.push({ type: "toolCall", id: `fixture-${Date.now()}`, name: `fixture_${scenario}`, arguments: { sample: "fixture-command-canary /fixture-path-canary" } });
        } else if (latest?.role === "toolResult") {
          mark("after tool");
          while (!existsSync(`${dir}/release`) && !signal?.aborted) await delay(10);
        } else if (scenario === "queued") {
          mark("first request");
          while (!session.hasPendingMessages() && !signal?.aborted) await delay(10);
          if (session.hasPendingMessages()) mark("follow-up queued");
        } else if (scenario === "follow-up") {
          mark("final request");
          while (!existsSync(`${dir}/release`) && !signal?.aborted) await delay(10);
        } else if (scenario === "error") {
          stop = "error";
        } else if (scenario === "cancel") {
          mark("waiting for cancel");
          while (!signal?.aborted) await delay(10);
        }
        if (signal?.aborted) stop = "aborted";
        const message: AssistantMessage = {
          role: "assistant", provider: model.provider, model: model.id, api: model.api,
          stopReason: stop, timestamp: Date.now(), content,
          usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { ...cost, total: 0 } },
        };
        if (stop === "error" || stop === "aborted") {
          message.errorMessage = stop === "error" ? "fixture invalid request" : "fixture cancelled";
          mark(`provider ${stop}`);
          stream.push({ type: "error", reason: stop, error: message });
        } else {
          stream.push({ type: "start", partial: message });
          stream.push({ type: "done", reason: stop, message });
        }
        stream.end();
      })();
      return stream;
    },
  });
}
