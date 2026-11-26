# Improve the Android client

Goal: Connect to the PC, see which work needs attention at a glance, and inspect a window in one tap before adding input.

Decisions and assumptions: Keep the existing one-PC, one-tmux-session workflow and [bridge contract](../android-agent-control/connection-deployment.md). The overview is home once connected. Use three views: **Connection** for setup and recovery, **Session overview** for scanning work, and **Window detail** for reading one pane. Input comes after UX review.

Open questions: Before integrating Pi status, approve who may read it. Recommend explicit bridge opt-in: after enabling it, every peer that can reach the unauthenticated bridge can read the status labels. A running local socket is not consent to share them over HTTP. This does not block navigation work.

| Outcome | Depends on | Status |
| ------- | ---------- | ------ |
| Connect, scan the window list, and inspect an eligible pane in one tap | Existing inventory and pane reads | Done |
| Reach navigation with your thumb and see connection and window count at a glance | Connect, scan, and inspect | Done |
| See which agents need attention in the bottom bar without hiding shell windows | Bottom bar review and status-sharing decision | — |

Next: None
Review: Reach navigation with your thumb and see connection and window count at a glance: pending

### Review brief — Reach navigation with your thumb and see connection and window count at a glance

- **Your call:** Try the bar one-handed and check pane readability. Reply “bottom bar works for me” or say what to change.
- **What happened:** **Overview** shows the current window count, and **Connection** shows bridge status. Both stay reachable below the list and pane.
- **See it:** Scroll to the last window, open a readable pane, then tap **Overview**. On the emulator with a real bridge, the last row stayed above the bar, the list returned to the same position, and pane reads stopped.

### Review brief — Connect, scan the window list, and inspect an eligible pane in one tap

- **Your call:** No input needed.
- **What happened:** Glowkin opens the session overview after connecting. One tap opens a separate read-only pane view. Back stops pane refreshes and restores the list position.
- **See it:** In a tmux session with several windows, tap a shell or Pi window, then press Back. The emulator showed both pane types through the real bridge and returned to the same scrolled position.

Later candidate: The [local Pi receiver](../../../pi-tmux-signals/README.md) already publishes leased per-pane state [on its tmux server](../../../pi-tmux-signals/src/chooser.rs). Prefer having the bridge read that state on the same server rather than exposing the Unix socket. Keep absent or expired state unknown, not idle. `done` means finished, not succeeded; attention labels only cover prompts a provider reports.

## Connect, scan the window list, and inspect an eligible pane in one tap

Depends on: Existing v1 inventory and pane reads.

### Outcome
Arrive at the session overview after connecting, open a window directly from the list, and return with one Back action.

### Guarantees
- Connection shows the endpoint, setup, trusted-network warning, and actionable failures. A saved endpoint still reconnects without a manual step. The overview provides a way to reach connection controls without showing the form or bridge revision in the work list.
- The overview shows the session name and all listed windows, including shells. Keep **Open shell** and its delivery feedback here. Explain unavailable workspaces and windows without a readable pane; never imply that an unobserved window is idle.
- One tap on an eligible row opens a detail view with the window name and its current read-only pane. Back returns to the same list position when the window list still permits it. The overview never shows terminal contents.
- Leaving detail stops further pane refreshes and discards late responses. Connection loss or target invalidation clears the preview and gives a route back to the overview or connection recovery. Never silently select a replacement, change PC focus, or send input.
- Preserve the [existing preview protections, stale-target checks, and mutation reconciliation](../android-agent-control/decisions.md). Older v1 bridges still connect; unavailable capabilities are explained instead of shown as usable controls.

### Scope boundary
Android presentation and navigation only. No bridge changes, Pi status, new workspace controls, multi-session or split-pane support, or remote input.

### Parallel implementation handoff
- Run two builders in parallel after agreeing on the overview's state and callbacks. Reuse existing connection and workspace types; pass navigation and action callbacks in so the overview does not own polling or routing.
- **Builder A — flow and lifecycle:** Own `MainActivity.kt`, connection and detail presentation, and `ConnectionModule.kt` changes. Wire navigation, back behavior, and preview teardown. Integrate Builder B's overview.
- **Builder B — overview:** Own the overview in a separate Android file. Build the window list, unavailable states, and **Open shell** action and feedback. Do not edit `MainActivity.kt` or `ConnectionModule.kt`.
- Run the full navigation and regression checks after integration. Pi status is a later slice, not a parallel assignment.

### Done when
On an emulator or phone, connect through the real bridge, open a shell and an existing Pi pane, and check the one-tap and one-Back paths. Exercise a saved endpoint, failed connection, older bridge, ineligible window, and target loss. Run Android checks for foreground-only preview reads, late replies after leaving detail, and uncertain **Open shell** delivery. Verify that reading leaves PC focus and pending input untouched.

### Verification
- Android debug and instrumentation APK builds, lint, 13 JVM tests, and 14 emulator tests passed. Emulator fixtures covered saved endpoints, older v1 bridges, failed connections, ineligible and invalidated targets, exact list-position restoration, foreground-only pane reads, late replies, and uncertain **Open shell** delivery without replay.
- All 13 baseline bridge contract tests passed. No bridge changes are part of this slice.
- The new Back regression test failed against the old inline view because the overview row was still visible with pane text, then passed against the separated views.
- A headless emulator opened the real loopback bridge over `adb reverse` using a saved endpoint. **Open shell** created one window. A split window stayed listed but could not be opened. Shell and offline Pi panes opened with one tap and returned with one Back. The scrolled overview hierarchy was identical after Back. PC focus and both pending drafts were unchanged. Removing the viewed target cleared the preview and left a route back to the overview.
- Older-bridge compatibility and connection loss/recovery were verified with HTTP fixtures. Physical-device, WLAN/Tailscale, and live Pi redraw checks were not run.

Connection loss returns to setup and never reopens detail on recovery. A lost target with a healthy connection leaves an explanation in detail so Back still returns to the list. The window stays protected until the activity closes after it first displays nonblank pane text.

### Review
Use a session with several windows. Check whether the overview is quick to scan, the pane has enough room, and Back puts you where you expected. Navigation feedback is recorded above.

## Reach navigation with your thumb and see connection and window count at a glance

Depends on: Connect, scan the window list, and inspect an eligible pane in one tap.

### Outcome
Reach the overview and connection controls from a compact bottom bar that shows connection state and the current tmux window count.

### Guarantees
- Replace the prominent top **Connection** button with a labeled bottom entry. Keep navigation outside the scrolling content and clear of Android system controls. From window detail, the bottom overview entry and system Back both restore the list position and stop pane reads.
- Show connection state separately from workspace availability. Count all windows in the current session inventory, including shells and windows with no readable pane. Show zero only for a current, empty inventory. Mark the count unavailable or omit it when the inventory is missing or stale.
- Keep the overview as home after saved-endpoint connection. Preserve endpoint setup, manual disconnect, failure guidance, reconnect behavior, and **Open shell** feedback. Recovery never reopens a preview. Preserve the [v1 contract and foreground lifecycle](../android-agent-control/connection-deployment.md) and [preview and delivery protections](../android-agent-control/decisions.md).

### Scope boundary
Android navigation and status presentation only. No bridge changes, agent status, or new workspace actions. Leave room for an attention indicator without displaying a placeholder.

### Done when
On a phone or emulator, verify the bottom controls stay reachable while scrolling and do not cover the last row or pane contents. Verify one-tap entry to connection controls and both paths back from detail, including scroll restoration and preview teardown. Check the count with mixed window types, an empty inventory, a window added or removed, an older v1 bridge, and connection loss and recovery. Run the existing Android navigation, preview, and delivery regression checks.

### Verification
- Five targeted checks failed on the previous UI for the missing bottom entries, status, count, or fixed placement. Debug and instrumentation APK builds, 13 JVM tests, and all 18 emulator tests passed on the final code. Lint passed with 15 warnings and no errors. All 25 bridge tests passed without bridge changes.
- Android fixtures covered a current empty inventory, older v1 with no inventory, stale state, reconnection, manual disconnect, late pane replies, foreground polling, and uncertain **Open shell** delivery without replay.
- A headless emulator used the real loopback bridge and disposable tmux through `adb reverse`. A saved endpoint opened the overview. The mixed inventory, including an unreadable split, updated **30 → 31 → 30**. The last row and pane viewport stayed above the fixed bar. **Overview** and system Back restored the exact list position. Traffic confirmed pane reads stopped on either exit and on entering **Connection**. Restarting the bridge restored the count without reopening the pane. PC focus and the pending draft did not change.
- Screenshot protection prevented visual confirmation of the final pane line. No physical-device or WLAN/Tailscale check was run.

### Review
Try it one-handed with several windows. Decide whether connection controls are easy to find and the bar leaves enough room to read a pane before adding agent attention.
