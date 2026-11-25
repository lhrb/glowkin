import { strict as assert } from "node:assert";
import { writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { createAssistantMessageEventStream, type AssistantMessage } from "@earendil-works/pi-ai";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";

const provider = "settlement-fixture";
const cost = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };

export default function (pi: ExtensionAPI) {
  const scenario = process.env.PROBE_CASE;
  let session: ExtensionContext;
  let calls = 0;

  pi.on("session_start", (_event, ctx) => {
    const cwd = process.cwd();
    const dir = dirname(cwd);
    const env = process.env;
    const allowed = new Set(`PATH SHELL SHLVL TERM TERM_PROGRAM TERM_PROGRAM_VERSION COLORTERM TMUX TMUX_PANE PWD
      HOME XDG_CONFIG_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_CACHE_HOME TMPDIR PROBE_CASE
      PI_CODING_AGENT_DIR PI_CODING_AGENT_SESSION_DIR PI_OFFLINE PI_TELEMETRY PI_SKIP_VERSION_CHECK
      AI_AGENT PI_CODING_AGENT PI_MANAGED_INSTALL_ROOT`.trim().split(/\s+/));
    const dirs = {
      HOME: "home", XDG_CONFIG_HOME: "config", XDG_DATA_HOME: "data", XDG_STATE_HOME: "state",
      XDG_CACHE_HOME: "cache", TMPDIR: "tmp", PI_CODING_AGENT_DIR: "agent", PI_CODING_AGENT_SESSION_DIR: "sessions",
    };
    const checks = {
      environment: Object.keys(env).every((key) => allowed.has(key)),
      directories: Object.entries(dirs).every(([key, subdir]) => env[key] === `${dir}/${subdir}`),
      cwd: cwd === `${dir}/cwd` && ctx.cwd === cwd,
      socket: env.TMUX?.split(",")[0] === `${dir}/socket`,
      interactive: process.stdin.isTTY && process.stdout.isTTY && ctx.mode === "tui" && ctx.isIdle(),
      offline: env.PI_OFFLINE === "1" && env.PI_TELEMETRY === "0" && env.PI_SKIP_VERSION_CHECK === "1",
      model: ctx.model?.provider === provider && ctx.model.id === "fixture",
      ephemeral: !ctx.sessionManager.getSessionFile(),
      tools: pi.getActiveTools().length === 0,
    };
    const failures = Object.keys(checks).filter((key) => !checks[key as keyof typeof checks]);
    if (failures.length) {
      writeFileSync("../preflight-failed", failures.join(", "));
      return;
    }
    session = ctx;
    writeFileSync("../ready", "");
  });

  // Load this after the observer so the observer sees the proposal before another handler changes it.
  pi.on("agent_before_settle", (event) => {
    if (scenario === "continuation-error" && calls === 1 && event.outcome === "completed") {
      return {
        entries: [...event.entries, { type: "custom_message", customType: provider, content: "continue", display: false }],
        continue: true,
      };
    }
  });

  pi.registerProvider(provider, {
    baseUrl: "http://127.0.0.1:1", // Required model metadata; the fixture never opens a connection.
    apiKey: "dummy",
    api: provider,
    models: [{ id: "fixture", name: "Fixture", reasoning: false, input: ["text"], cost, contextWindow: 128000, maxTokens: 4096 }],
    streamSimple(model, _context, options) {
      const stream = createAssistantMessageEventStream();
      const call = ++calls;
      const signal = options?.signal;
      void (async () => {
        let stop: AssistantMessage["stopReason"] = "stop";
        let errorMessage: string | undefined;
        if (scenario === "queued" && call === 1) {
          writeFileSync("../waiting", "");
          while (!session.hasPendingMessages() && !signal?.aborted) await delay(10);
        }
        if (scenario === "cancel") {
          assert(signal);
          const aborted = new Promise<void>((resolve) => {
            if (signal.aborted) resolve();
            else signal.addEventListener("abort", () => resolve(), { once: true });
          });
          writeFileSync("../waiting", "");
          await aborted;
        }
        if (signal?.aborted) {
          stop = "aborted";
          errorMessage = "aborted";
        } else if (scenario === "retry" && call === 1) {
          stop = "error";
          errorMessage = "503 service unavailable";
        } else if (scenario === "error" || (scenario === "continuation-error" && call === 2)) {
          stop = "error";
          errorMessage = "invalid request";
        } else if (scenario === "length") {
          stop = "length";
        }

        const message: AssistantMessage = {
          role: "assistant", provider: model.provider, model: model.id, api: model.api,
          stopReason: stop, errorMessage, timestamp: Date.now(), content: [],
          usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { ...cost, total: 0 } },
        };
        if (stop === "error" || stop === "aborted") {
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
