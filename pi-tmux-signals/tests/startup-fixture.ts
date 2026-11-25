import { appendFileSync, existsSync } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

// Global offline model only. It never emits an attention event or opens a network connection.
export default function (pi: ExtensionAPI) {
  const cost = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
  const mark = (state: string) => appendFileSync("trace", `${state}\n`);
  pi.on("session_start", (_event, ctx) => {
    if (ctx.mode !== "tui" || !ctx.isIdle() || ctx.model?.id !== "startup") throw new Error("Expected the offline startup fixture");
    mark("ready");
  });
  pi.on("agent_settled", () => mark("settled"));
  pi.registerProvider("startup", {
    baseUrl: "http://127.0.0.1:1",
    apiKey: "dummy",
    api: "startup",
    models: [{ id: "startup", name: "Startup", reasoning: false, input: ["text"], cost, contextWindow: 8192, maxTokens: 1024 }],
    streamSimple(model, _context, options) {
      const stream = createAssistantMessageEventStream();
      void (async () => {
        mark("running");
        while (!existsSync("release") && !options?.signal?.aborted) await delay(10);
        const message = {
          role: "assistant" as const, provider: model.provider, model: model.id, api: model.api,
          stopReason: "stop" as const, timestamp: Date.now(), content: [],
          usage: { ...cost, totalTokens: 0, cost: { ...cost, total: 0 } },
        };
        stream.push({ type: "done", reason: "stop", message });
        stream.end();
      })();
      return stream;
    },
  });
}
