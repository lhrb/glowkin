# Pi attention signals in tmux

Goal: Receive Pi's permission, idle, and completion signals on the PC before deciding how to use them.

Decisions and assumptions: Start with interactive Pi on one local tmux server and the installed `@gotgenes/pi-permission-system`. Pi reports what happened; tmux identifies where. The receiver is local and observational; the terminal remains the only place to answer prompts.
Open questions: How to use the signals after review. No blocker for capture.

| Outcome | Depends on | Status |
| --- | --- | --- |
| Inspect live Pi signals by tmux pane | — | Ready |
| Use the signals in one requester-selected surface | Inspect live Pi signals by tmux pane; requester choice | — |

Next: Inspect live Pi signals by tmux pane.
Review: None.

## Inspect live Pi signals by tmux pane

Depends on: None.

### Outcome

A Pi extension sends attention signals to a local receiver whose raw output can be inspected by tmux pane without the Android app or server bridge.

### Guarantees

- A newly observed Pi waiting for work reports `idle`, not `done`. New work supersedes the previous state.
- The installed permission extension's user-facing approval prompt reports `needs_permission` on the pane that shows it. Resolving or dismissing the prompt clears that state. Silent policy decisions and unrelated dialogs do not count as permission prompts.
- `done` means a run finished normally and Pi settled. Intermediate turns, retries, and queued follow-ups do not emit `done`. Failed and cancelled runs do not emit `done`.
- Signals from different panes stay separate, even within one window. An exited or replaced Pi instance does not leave a waiting or done state attributed to its replacement.
- Signals carry only state and opaque source identifiers. No prompt text, commands, paths, or approval decisions leave Pi. An absent receiver does not block or change normal Pi use or permission handling.

### Scope boundary

Raw local reception and inspection only. No status styling, sounds, alerts, Android or bridge changes, durable event history, or remote approval. The existing [Android overview contract](../android-agent-control/agent-overview.md) stays unchanged.

### Done when

In two panes in the same tmux window, inspect raw signals for startup idle, a real permission prompt before and after answering, and a settled run after queued follow-up work. Verify that an unrelated dialog, a failed or cancelled run, and a replaced agent do not produce false signals. Confirm normal Pi and permission behavior with the receiver unavailable, and check that the received payload contains no sensitive content.

### Review

Inspect which pane produced each signal and when. Decide which transitions should trigger an action and where that action should appear.
