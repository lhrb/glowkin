# Replace the Android terminal UI with Pi chat

Goal: Start the bridge on the PC, launch normal interactive Pi in tmux, then open that agent on the phone and send prompts and read replies in a ChatGPT-inspired conversation.

Decisions and assumptions: Android is a chat client, not a terminal. Remove the legacy workspace and terminal UI, including **Open shell**, raw keys, and all terminal fallbacks. Keep connection setup and recovery, the bridge's [v1 contract](../android-agent-control/connection-deployment.md#v1-http-contract), and Pi's independent PC use. Pi owns the conversation and shared draft.

Access: Keep the [trusted-network boundary](../android-agent-control/connection-deployment.md#scope-and-security). Any reachable peer can read chat, tool output, and unsent drafts. Enabling bridge input lets reachable peers edit drafts and still exposes the raw pane input API; removing its Android UI does not restrict network access. Android and the bridge do not log or persist chat or drafts.

| Outcome | Depends on | Status |
| --- | --- | --- |
| Skim tool results and type or dictate into the shared draft | Existing chat view | Ready |
| Open a live agent in a chat-only app with no terminal fallback | Existing chat view | — |
| Send from Android and read the reply in the same conversation | Chat-only app; supported Pi acknowledgement of the specific submission | Blocked |
| [See agent activity by project](../android-agent-overview/plan.md) | Chat-only agent entry; fresh signals matched to exact panes | — |

Next: Skim tool results and type or dictate into the shared draft.

Review: The requester confirmed the current Pi chat workflow works and asked for tool-result and composer changes. This does not verify failure handling, terminal removal, or phone submission.

## PC setup

**Load `pi-chat` as an extension, not a wrapper.** Add the absolute path to [`pi-chat/extension.ts`](../../../pi-chat/extension.ts) to the `extensions` list in `~/.pi/agent/settings.json`. Keep existing entries and the extension's sibling files. For a one-off trial, run `pi -e /absolute/path/to/glowkin/pi-chat/extension.ts` instead. See [deployment and security](../android-agent-control/connection-deployment.md#pi-conversation-and-shared-draft).

1. On the Linux PC, run `./server/run-server.sh --bind <trusted-PC-IP>` from the repository root. The helper enables input; use it only on a restricted network. Run the bridge and Pi as the same OS user against the same tmux server.
2. In a single-pane window in the one tmux session served by the bridge, run `pi` with the extension loaded. The bridge can already be running when Pi starts.
3. In Android, connect to the PC IP on port `8787` and open the agent. The current build shares the draft but still submits on the PC and still has a terminal fallback.

## Skim tool results and type or dictate into the shared draft

Depends on: Existing Pi chat view.

### Outcome

Scan tool output without crowding out the conversation, and compose a shared draft with Gboard typing or voice typing without a password-save prompt.

### Guarantees

- Tool results start collapsed with a short, ellipsized text preview when there is output. A subtle themed background and tool label distinguish them from agent replies in light and dark modes.
- An explicit **Expand** / **Collapse** control remains available without a gesture. Long-pressing inside an expanded result collapses it without scrolling back to its header or jumping to the latest message. Preserve Markdown and horizontal code scrolling when expanded.
- Treat the composer as ordinary multiline text, not `KeyboardType.Password`. Opt it out of credential autofill and saving separately. Retain screen-capture and draft-retention safeguards. Request no personalized learning where supported; keyboard providers control dictation and may process audio off-device. Glowkin does not capture audio or add a speech service.
- Dictation edits the same visible Pi draft; it never submits. Preserve draft revisions, PC conflict priority, read-only behavior, and pause/reconciliation on unavailable or uncertain state. Do not replay changes.

### Scope boundary

Only the chat view and composer behavior needed for these changes. No bridge protocol changes, phone Send, or legacy-route removal in this slice. Tool result presentation and composer input can be verified independently.

### Done when

- Open a real chat with short and very long tool outputs. Check the bounded previews, expand a long result, and long-press far from its header. Confirm it collapses near its own position and code blocks still scroll when expanded. Inspect the result at compact width, large text, and in light and dark modes.
- On a phone with Gboard voice typing and a password manager enabled, type and dictate a multiline draft. Confirm dictation remains usable during live refresh, the PC receives the draft without submitting it, and focusing, editing, and leaving the chat does not trigger a password-save prompt. Check read-only mode and a PC edit conflict. If no suitable phone is available, report these checks as unverified.

### Review

Try a long tool result and dictate into the composer on your phone. Confirm whether both feel natural before returning to the chat-only navigation work.

## Carry forward

- Chat-only navigation must never show terminal output or raw controls, including on missing extensions, unsupported bridges, and confirmed exit. Distinguish an unavailable agent from an exited one. Never silently retarget a chat. Keep setup and recovery actionable and verify the live startup path plus independent v1 compatibility. Use a private socket for automated tmux verification (`tmp=$(mktemp -d)` and `tmux -S "$tmp/socket"` for every tmux command, including cleanup); point the test bridge at that socket.
- Existing evidence: Pi type checking, seven extension tests, Rust formatting/Clippy/builds and 15 non-tmux tests, and Android unit tests/lint/debug APK builds passed. An emulator used the real bridge with mock tmux/Pi to edit a draft and show tools and subagents. Draft conflict, wrong target, compaction, input-disabled, and lost-response behavior were exercised. Instrumentation passed 29/30; the targeted legacy reselection test passed after a fix, but a concurrent install interrupted the full rerun. No independent live structured-chat workflow or standalone Compose preview rendering was verified.
- Phone Send stays blocked by the requirement for a supported per-submission outcome (accepted, rejected, or pending) across a lost response. Pi 1.6.0's ordinary TUI `sendUserMessage()` returns `void`. During asynchronous input/auth checks, Pi can look idle with nothing queued, so a refresh cannot distinguish pending from rejected. The replacement-session promise API does not apply to an existing TUI. The current adapter does not close that gap. Do not substitute raw keys or replace the TUI with RPC.
