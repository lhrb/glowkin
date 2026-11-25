import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

function requestId(event: unknown): string | undefined {
  if (event && typeof event === "object" && "requestId" in event) {
    return typeof event.requestId === "string" ? event.requestId : undefined;
  }
}

export default function (pi: ExtensionAPI) {
  const prompts = new Set<string>();
  const report = (state: "needs_permission" | "clear") => {
    pi.events.emit("glowkin:attention", { provider: "pi-permission-system", state });
  };

  // Observe only the package's public broadcasts. Decisions without a matching
  // prompt (including silent policy decisions) cannot clear an active ask.
  const unsubscribePrompt = pi.events.on("permissions:ui_prompt", (event) => {
    const id = requestId(event);
    if (!id || prompts.has(id)) return;
    prompts.add(id);
    report("needs_permission");
  });
  const unsubscribeDecision = pi.events.on("permissions:decision", (event) => {
    const id = requestId(event);
    if (id && prompts.delete(id) && prompts.size === 0) report("clear");
  });

  pi.on("session_shutdown", () => {
    unsubscribePrompt();
    unsubscribeDecision();
    prompts.clear();
  });
}
