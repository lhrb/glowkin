# See windows and agent attention in one overview

Depends on: shared-pi-chat.md

## Outcome
Identify active agents and agents needing attention directly in the tmux-window overview.

## Guarantees
- The main overview combines all discovered windows with their associated agents and states; windows without agents remain visible.
- Each agent distinguishes idle, working, needs input, done, failed, and unavailable/unknown where applicable.
- A blocking pi UI prompt marks the agent as needing input, not working; terminal-only dialogs explicitly say input is required on the PC.
- Done means a run settled without pending follow-up work, retries, or compaction; an intermediate turn ending does not falsely mark the agent done.
- A newly observed idle agent is not represented as having just completed a task.
- Needs-input, completion, and failure indicators are distinguishable without opening chat or relying on color alone.
- Multiple agents in one window retain individual status and identity; aggregation never hides an agent needing input.
- Starting new work supersedes the previous done/failed run state; resolving a prompt removes its needs-input state.
- State changes reach the connected foreground Android UI without manual refresh.
- Connectivity loss or unobserved state becomes visibly stale/unknown, never fabricated completion; reconnect reconciles current state.
- Selecting an agent opens its shared chat; window creation, directory selection, pi launch, and confirmed closure remain accessible as actions rather than shell commands.

## Scope boundary
Foreground overview only; no Android background notifications, remote answers to arbitrary pi dialogs, or inventory of internal subagents that are not independently bridged pi instances.

## Done when
Tests verify state transitions, delayed follow-up work, prompt start/end, failures, multiple-agent windows, stale connectivity, and reconnect; Android tests verify combined navigation and distinct attention indicators.
