# Pi attention signals in tmux

Goal: Receive Pi's permission, idle, and completion signals on the PC before deciding how to use them.

Decisions and assumptions: Start with interactive Pi on one local tmux server and the installed `@gotgenes/pi-permission-system`. Pi reports what happened; tmux identifies where. The receiver is local and observational; the terminal remains the only place to answer prompts. The requester approved `done` as normal settlement, not task success.
Open question: How should the signals be used after review?

| Outcome | Depends on | Status |
| --- | --- | --- |
| Probe Pi run settlement | — | Done |
| Inspect live Pi signals by tmux pane | Probe Pi run settlement | Done |
| Use the signals in one requester-selected surface | Inspect live Pi signals by tmux pane; requester choice | — |

Next: None
Review: Inspect live Pi signals by tmux pane: pending

## Probe Pi run settlement

Type: Experiment

**Question:** Can an observation-only extension identify a normally completed run at final settlement on installed Pi 1.6.0 without mistaking failures, cancellations, or intermediate runs for `done`?

**Why this gates capture:** `agent_settled` reports cancellation but not success. `agent_before_settle` reports an outcome, but it can precede another continuation and classifies a `length` stop as `completed`.

**Decision:** Use public hooks for `done` only if they provide a positive final-success signal without stale success from earlier work. Otherwise keep capture blocked and review the completion guarantee before implementing.

**Required evidence:** Record the order of relevant public hooks and status metadata for a normal run, queued follow-up work, retry then success, an unrecovered provider error, a terminal `length` stop, and cancellation. Show that the candidate rule reports exactly one `done` after normal settlement and none on intermediate, failed, truncated, or cancelled runs. Test a failed continuation after a prior successful turn to rule out stale success.

**Prerequisites and safeguards:** Use installed Pi, tmux, and Node with a disposable interactive session and a deterministic local provider fixture. Use only a private temporary tmux socket, config, and Pi state; do not use live credentials, user sessions, or change permission policies. Record only hook names and status metadata. Remove the probe session and temporary files afterward. Do not implement the receiver or change Android or the bridge.

**Prerequisite checks:** Installed Pi 1.6.0, tmux 3.8, and Node 22.23.5 are available. Installed Pi docs and source confirm that `agent_end` is low-level, `agent_before_settle` can continue, and final `agent_settled` has no success field. Before input, the driver and fixture verify a detached private socket, isolated environment and state, an idle TUI, the in-process fixture model, no saved session, and no active tools. Pi runs offline with no inherited credentials or regular extension discovery. The fixture opens no connection.

**Reproduce:** From the repo root, run `bash docs/features/pi-tmux-signals/probe-settlement.sh`. It starts a fresh Pi pane per case, submits synthetic input, uses Alt+Enter for the queued follow-up and Escape for cancellation, prints the status-only trace, and checks each case's stop sequence and single final settlement. Only the separate fixture extension requests the failed continuation.

**Direct observations:** `A` = `agent_start`; `T(x)` = `turn_start` → `turn_end` with stop reason `x`; `E(x)` = `agent_end` with latest assistant stop reason `x`; `B(x)` = `agent_before_settle` with outcome `x`; `S` = `agent_settled`. All settlements reported `idle=true`; only cancellation reported `aborted=true`.

| Case | Hook order | `done` count |
| --- | --- | ---: |
| Normal | `A → T(stop) → E(stop) → B(completed) → S` | 1 |
| Queued follow-up | `A → T(stop, pending=true) → T(stop, pending=false) → E(stop) → B(completed) → S` | 1 |
| Retry then success | `A → T(error) → E(error) → A → T(stop) → E(stop) → B(completed) → S` | 1 |
| Unrecovered provider error | `A → T(error) → E(error) → B(error) → S` | 0 |
| Terminal length | `A → T(length) → E(length) → B(completed) → S` | 0 |
| Escape cancellation | `A → T(aborted) → E(aborted) → S` | 0 |
| Failed continuation after success | `A → T(stop) → E(stop) → B(completed) → A → T(error) → E(error) → B(error) → S` | 0 |

The observer saw `continue=false` at the first boundary of the failed continuation, before the later fixture handler requested more work. That boundary did not emit `done`. Pi labeled terminal `length` as `completed`, but the raw stop reason rejected it.

**Conclusion:** Yes for the tested Pi 1.6.0 paths. Clear evidence on every `agent_start` and after settlement. Use the last assistant stop reason from `agent_end` and the outcome from `agent_before_settle`. Report `done` only at idle, non-aborted `agent_settled` with outcome `completed` and stop reason `stop`. Review this rule before starting capture.

**Limitations:** The fixture tests lifecycle behavior, not live provider transport, tool execution, compaction, permission extension behavior, arbitrary extension interactions, or other Pi versions. A normal provider stop does not establish semantic task success. Receiver and permission guarantees remain for the capture slice.

**Cleanup and verification:** The independent rerun passed all seven checks. The driver verified Pi exit and private tmux shutdown, then removed its temporary directory. No runtime files remained. Only the rerunnable driver, fixture, and observer are retained. No production or permission changes were made.

## Inspect live Pi signals by tmux pane

Depends on: Probe Pi run settlement.

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

### Implementation and verification

[Run the standalone receiver and extension](../../../pi-tmux-signals/README.md). The private Unix stream carries only pane ID, opaque instance ID, and state. Its lifetime clears stale state; a replacement supersedes the previous instance on that pane. The sender observes the permission package's public prompt and decision events without changing the policy or UI. Delivery is best effort; reconnect sends the current state, not missed transitions.

`bash docs/features/pi-tmux-signals/verify-capture.sh` passed on Pi 1.6.0, tmux 3.8, and permission system 42.2.1. It verified startup idle, approval and Escape dismissal, silent policy and unrelated UI, final settlement after queued work, failure and cancellation, stale `done` and waiting state on replacement, normal approval without a receiver, and the raw wire payload. It used two disposable panes in one window, a deterministic offline provider, and the real permission UI. Cleanup passed. The production sender also passed all seven settlement cases from the earlier experiment. Rust formatting, strict Clippy, and receiver tests passed. No Android or bridge code or contract changed.

### Review

Inspect which pane produced each signal and when. Decide which transitions should trigger an action and where that action should appear.
