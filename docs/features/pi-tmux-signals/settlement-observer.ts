import { appendFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  const record = (hook: string, status = "") => appendFileSync("../trace", `${hook}${status}\n`);
  let stop: string | undefined;
  let completed = false;

  pi.on("agent_start", () => {
    stop = undefined;
    completed = false;
    record("agent_start");
  });
  pi.on("turn_start", () => record("turn_start"));
  pi.on("turn_end", (event, ctx) => {
    const reason = event.message.role === "assistant" ? event.message.stopReason : "none";
    record("turn_end", ` stop=${reason} outcome=${event.outcome} pending=${ctx.hasPendingMessages()}`);
  });
  pi.on("agent_end", (event) => {
    stop = event.messages.findLast((message) => message.role === "assistant")?.stopReason;
    record("agent_end", ` stop=${stop ?? "none"}`);
  });
  pi.on("agent_before_settle", (event) => {
    completed = event.outcome === "completed";
    record("agent_before_settle", ` outcome=${event.outcome} continue=${event.continue}`);
  });
  pi.on("agent_settled", (event, ctx) => {
    const idle = ctx.isIdle();
    const done = idle && !event.aborted && completed && stop === "stop";
    record("agent_settled", ` aborted=${event.aborted} idle=${idle} done=${done}`);
    stop = undefined;
    completed = false;
  });
}
