import { appendFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  const emit = (value: unknown) => pi.events.emit("glowkin:attention", value);
  pi.registerCommand("fixture-attention", {
    description: "Emit a controlled attention event without the permission package",
    async handler(args) {
      const [provider, state] = args.split(" ");
      if (args === "malformed") {
        for (const value of [null, [], "needs_permission", {}, { provider: 7, state: "needs_permission" },
          { provider: "", state: "needs_permission" }, { provider: "wrong\nkey", state: "needs_permission" },
          { provider: "x".repeat(65), state: "needs_permission" }, { provider: "fixture-primary", state: "custom" },
          { provider: "fixture-primary", state: ["clear"] }, Object.create({ provider: "inherited", state: "needs_permission" }),
          { provider: "accessor", get state() { return "needs_permission"; } }]) emit(value);
      } else {
        emit({ provider: `fixture-${provider}`, state, requestId: "request-canary",
          command: "command-canary", text: "text-canary" });
      }
      appendFileSync("../trace", `attention ${args}\n`);
    },
  });
}
