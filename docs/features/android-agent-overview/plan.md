# Android agent overview

Goal: Scan agents by project folder, see their current activity, and spot changes not yet reviewed.

Decisions and assumptions: Use the tmux window name as the row name and the current working directory of its sole pane as the project folder. Keep shell and unclassified windows accessible. Reuse Pi signals for live state, not the tmux chooser’s acknowledgement rules.
Open questions: Before the inbox slice, decide which transitions count, what marks a change reviewed, and whether acknowledgement survives an app restart or is shared with the PC.

| Outcome | Depends on | Status |
| --- | --- | --- |
| Find windows by project folder on Android | — | Done |
| See live Pi activity in each folder | Grouped overview; fresh signals matched to exact panes | — |
| Spot and review unseen status changes | Live activity; agreed review rules and change identity | — |

Next: None
Review: Find windows by project folder on Android: pending

### Review brief — Find windows by project folder on Android

- **Your call:** No input needed.
- **What happened:** Android groups windows by current folder without changing which pane each row opens. It keeps a flat list if the bridge supplies no folder metadata.
- **See it:** With the updated bridge, open Workspace with `Alpha` and `Beta` in one `project` folder and `Gamma` in another. On the emulator, `Alpha` and `Beta` shared `project (1)`, `Gamma` appeared under `project (2)`, and each row opened its own pane. After `cd`, `Beta` moved to `project (2)`.

## Find windows by project folder on Android

Depends on: None.

### Outcome

Open Android’s overview and find tmux windows grouped by the folder they’re working in.

### Guarantees

- A fresh snapshot groups single-pane windows by exact working directory, not by the displayed folder name. Show the folder name as the heading and the tmux window name as the row title. Make same-name folders distinguishable without exposing parent paths. A working-directory change moves the row on the next fresh snapshot.
- Keep non-Pi windows accessible. Put windows without a reliable single directory, including split windows, in an ungrouped section. With an older bridge that supplies no folder metadata, keep a usable flat list.
- Extend the [v1 snapshot contract](../android-agent-control/connection-deployment.md#v1-http-contract) additively. Send only the display name and an opaque grouping key; document that reachable peers can see folder names under the existing trusted-network boundary. Optional metadata must not displace existing inventory, pane IDs, or input capabilities at snapshot limits.
- Keep pane opening tied to the existing exact target, not to group or display names. Preserve stale notices and disabled stale rows, blocked-state recovery, **Refresh**, **Open shell**, and current terminal/chat routing.

### Scope boundary

No live activity badges, unread dots, repo-root detection, new navigation, or changes to pane detail. The builder owns routine styling: make folder headings and window rows the focal point; tap a row to open it, with existing recovery and secondary actions still reachable.

### Done when

Use a disposable tmux session and Android to show two windows in one folder, distinct folders with the same final name, a window changing directories, and an unclassified window. Verify each eligible row opens the exact pane. Check an older bridge, stale-state recovery, and the existing snapshot limits and action regressions. Inspect the rendered list with synthetic data at compact width and large text in light and dark modes.

### Review

Try finding and opening an agent among your usual windows. Decide whether current folder is the right grouping before adding live activity.

### Verification

- Disposable tmux and Android emulator: verified same-folder grouping, distinct headings for equal basenames, regrouping after `cd`, exact terminal content from each eligible row, and a non-clickable split window under Ungrouped.
- Bridge: all 37 Rust tests passed, including both 64 KiB budget regressions. Formatting, Clippy with warnings denied, and release build passed. The release smoke test confirmed parent paths are absent from the response.
- Android: debug builds, unit tests, lint, and all 33 emulator tests passed. Fixtures covered the old flat list, stale disable/recovery, Open shell, and exact terminal/chat routing. Inspected synthetic screenshots at 320 dp width and 1.75× font size in light and dark modes: long headings wrapped and controls stayed legible.

### Handoff

The [v1 contract](../android-agent-control/connection-deployment.md#v1-http-contract) uses snapshot-local keys, not path hashes or pane identifiers. Duplicate-heading numbers follow the list order and can change after a refresh. Folder metadata drops as a set at either snapshot limit, so grouping can disappear for a large inventory without losing existing pane IDs or capabilities.

### Later direction

The existing [Pi signals](../../../pi-tmux-signals/README.md) already report busy, idle, done, and waits for input or permission. Reuse the receiver’s fresh pane state on the same tmux server rather than reading terminal text or polling each chat. Show a spinner for working, label other observed states, and treat missing or expired signals as unknown, not idle or done. The current chooser snapshot carries state labels but no change identity; the inbox needs that distinction so repeated runs between Android polls don’t look unchanged. Recommended starting point for review: mark a change seen only after the matching detail loads successfully on the phone, not on a tap or PC focus change. Keep the green dot independent of the activity indicator and give it an accessible label.
