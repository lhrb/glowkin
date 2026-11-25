# Share live pi conversations between Android and the PC

Depends on: workspaces.md

## Outcome
Start pi in a selected project or join an existing interactive pi agent, then chat and stop work from either device.

## Guarantees
- A one-time installed pi bridge extension integrates normal interactive pi; it does not replace the terminal with RPC mode.
- Already-running pi processes become available after a one-time extension reload; agents with the extension loaded register even if the server starts later.
- Starting pi from a selected directory creates a new window in the selected tmux session without injecting commands into an existing terminal process.
- A failed launch reports failure and does not claim an agent is ready.
- Identify each live agent separately and associate it with its tmux session, window, and pane; multiple agents in a window remain separately selectable.
- Opening an agent shows the current conversation branch, including user messages, assistant responses, and tool activity; historical branches are not mixed into the chat.
- PC and Android input affect the same pi session; messages from both sources appear in that shared conversation without duplicated submissions.
- Android prompts submitted while idle start work; during a run they queue for follow-up rather than implicitly interrupting it.
- Preserve PC editor contents when Android submits; do not replace unsent local input.
- Stream assistant output and tool activity incrementally without waiting for turn completion; local submission feedback does not wait for the model.
- Provide an explicit stop action for current agent work; report the resulting state rather than equating command delivery with completed cancellation.
- Reconnect, pi reload, and session changes reconcile identity, current-branch history, and state without merging unrelated agents or replaying commands.
- Server absence and bridge failures do not prevent normal PC pi use.
- Agent exit or lost bridge connectivity disables remote submission instead of targeting a replacement process.

## Scope boundary
Text chat, visible tool activity, follow-up submission, and stop; no complete pi command palette, model/settings management, branch navigation, attachments, or generic terminal-dialog response.

## Done when
Integration tests verify late server startup, reload, new launch, multiple panes, concurrent PC/Android input, queued prompts, stop, streaming before completion, session replacement, and reconnect without duplicate submission; manual verification confirms the ordinary pi TUI remains usable.
