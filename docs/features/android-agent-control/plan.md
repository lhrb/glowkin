# Android access to PC tmux

Goal: Read and control a selected PC tmux pane from Android. Prepare and verify the bridge first without changing Android.

| Outcome | Depends on | Status |
| --- | --- | --- |
| Drive one existing Pi pane through the bridge | Existing pane targeting | Done |
| Android sends input from Window detail | Verified bridge input and [Android navigation review](../improve-android-client/plan.md#reach-navigation-with-your-thumb-and-see-connection-and-window-count-at-a-glance) | Blocked |

Next: None

Review: Drive one existing Pi pane through the bridge: accepted

Android input review brief:
- **Your call:** No input needed now. Finish and review the in-progress Android navigation slice before starting input.
- **What happened:** The requester chose to try the existing eight keys on Android before deciding whether the shortcuts work well. Android input is planned below, not implemented.
- **See it:** The offline bridge demo appended ` + remote π` to a PC draft without submitting it. The Android slice should show the same combined draft and submit it only on explicit Enter. That Android behavior is unverified.

The pane-preview review is still pending. Choosing server input did not establish preview acceptance.

Pane-preview review brief:
- **Your call:** No input needed for the server input slice.
- **What happened:** Android now shows the selected pane’s visible screen without changing PC focus or sending input. Any peer that can reach the updated bridge can read eligible panes.
- **See it:** Run `printf 'first line\n    second line\n'` in a single-pane PC window and tap its name in Glowkin. The emulator preserved indentation and refreshed the preview through the real bridge.

## See and open windows in one existing session

**Done.** Android connects to one PC bridge, lists existing windows (including PC-created windows that predate Glowkin), and refreshes the list. **Open shell** opens a normal interactive shell at home in that session, not a terminal on Android. It does not start Pi or send input to an existing pane. If the target is unavailable, opening fails without falling back. With no session, start tmux on the PC; with multiple sessions, Android explains the limit rather than picking one. Session creation and window closure stay on the PC.

See [deployment and recorded verification](connection-deployment.md) for the current workflow and [decisions](decisions.md) for compatibility and targeting constraints.

## Read a selected PC tmux pane on Android

**Done.** Read the selected pane's current screen regardless of which app is running in it.

- Show a bounded, plain monospaced snapshot that preserves readable line breaks and layout. Refresh only while selected and Android is in the foreground.
- Read only single-pane windows in the one existing session. Match the exact tmux server lifetime, session, window, and pane. Refuse gone, replaced, moved, or otherwise ineligible targets, including split windows and multiple sessions. Never guess or fall back.
- Clear the preview on selection change, disconnect, or invalidation. Discard obsolete responses even after reselecting the same pane. Neither Android nor the bridge logs or persists pane contents.
- Reading or selecting must not mutate tmux, change PC focus, or send input. Preserve connection, window listing, and **Open shell**, including compatibility with current bridges.
- Keep the [existing listener defaults and private-network trust model](connection-deployment.md#scope-and-security). The user accepts that **when shipped, any reachable peer can read eligible panes**, even without the Android app. No login, pairing, per-pane setup, or public exposure.

No ANSI interpretation, transcript, scrollback, shell or input emulation, or Pi integration. Leave transport, polling details, and styling to the builder within these guarantees.

**Verified:** Android emulator → release bridge → disposable tmux 3.8 on a private socket. Shell and offline Pi TUI previews worked. Foreground refresh left PC focus and pending input untouched. Split, multiple-session, and disconnect cases cleared the preview. The bridge stayed on loopback and **Open shell** created one detached interactive shell at home. A synthetic marker stayed out of inventory, app storage, and logs. Recents showed a black app card.

**Checks:** Rust formatting, strict Clippy, debug and release builds, and 13 bridge tests passed. Android assembly, lint, 13 JVM tests, and 10 emulator tests passed. Lint reported 15 unrelated warnings and no errors. Tests caught a wrong-window read, an obsolete same-pane response, and a capture-hook focus change before the fixes. They also cover target replacement and movement, read limits, and compatibility at both inventory size limits.

**Review:** Try a shell and an already-running Pi TUI before planning text delivery. Physical-device, WLAN/Tailscale, and live Pi redraw behavior remain unverified. See [deployment](connection-deployment.md) and [pane-preview decisions](decisions.md#pane-previews).

## Drive one existing Pi pane through the bridge

Depends on: Existing v1 inventory and pane reads.

### Outcome

A local HTTP test client can read an existing Pi pane, use its permission dialog, and carry on typing in the same Pi TUI from either keyboard.

See the [v1 input API and enablement warning](connection-deployment.md#pane-input-api) and [pane input decisions](decisions.md#pane-input).

### Guarantees

- Input is explicitly enabled and off by default. When enabled, **any reachable peer can type into eligible panes and potentially execute commands as the pane's user**. Preserve loopback by default and the existing trusted-network boundary. Reject browser-originated requests using the existing mutation checks.
- A bounded, fixed-length JSON POST uses the existing pane identity and carries either one nonempty single-line literal Unicode text payload or one allowed key. Reject control characters and raw escape sequences in text. Forward no implicit Enter. Support Enter, Escape, four arrows, Backspace, and Ctrl+O as explicit keys. This covers basic editing and the repository's `@gotgenes/pi-permission-system` flow: navigation, confirmation, dismissal, and inspection via Pi's default tool-expansion binding.
- Send input only to the exact live pane in the one existing session. Revalidate tmux lifetime, membership, and single-pane eligibility in the same synchronous queue as delivery. Refuse dead, input-disabled, or modal tmux panes rather than reporting success or driving tmux UI. Do not retarget, activate the window, run mutation hooks outside the intended input, or change tmux configuration.
- Input goes to whatever currently owns the pane's input. It is not tied to a rendered screen or a permission prompt ID. PC and remote input share the same editor; Enter can submit PC-typed text. An application that echoes input makes it visible through the existing pane read on its next refresh, not through a keystroke feed. Hidden input stays hidden.
- A success acknowledges injection, not any Pi decision, submission, or application processing. Distinguish a definite rejection from uncertain delivery. Never replay, log, or persist input in the bridge. Preserve [mutation reconciliation rules](decisions.md#connection-and-delivery) and existing v1 snapshot, Open shell, and pane read behavior.

### Scope boundary

Server and verification only. No Android or Pi extension changes, approval API, prompt detection or binding, arbitrary key sequences, host-wide keyboard control, or terminal emulator. A later Android slice can use the existing foreground pane preview to show echoed PC typing; it need not synchronize a separate draft.

### Done when

- On disposable private tmux, use the shipped bridge and an offline Pi with the permission package. Read the rendered prompt, expand its details, navigate and confirm a harmless fixture request, and dismiss another. Type on the PC and observe the echoed draft through the same pane-read endpoint. Send literal text remotely and verify it appears in that draft without submission; send Enter separately and verify Pi receives the combined text. Keep another window focused throughout.
- Verify exact literal and key delivery, no delivery when disabled or invalid, and rejection of stale/replaced/moved/split or otherwise ineligible targets. Cover bounded requests, browser rejection, timeout uncertainty without replay, and unchanged existing endpoints and snapshot limits. Run Rust formatting, Clippy, and tests. Document the API and enablement warning.

### Verification

Rust formatting, strict Clippy, debug and release builds, and all 25 tests passed on tmux 3.8. The default-off regression test first failed because the route did not exist. Disposable tmux tests verified exact UTF-8 text and all eight keys, unchanged focus, rejected requests and targets, and one write with no replay after a lost acknowledgement. A read-only attached client did not interfere or trigger hooks. The existing inventory size, Open shell, and pane-read tests still pass.

The release bridge passed the workflow on loopback in an isolated network namespace with disposable tmux 3.8, Pi 1.6.0, permission-system 42.2.1, and a local model fixture. Ctrl+O expanded the rendered permission details. Down and Up moved the selection. Enter approved a harmless fixture write, and Escape denied another without writing. Pane read showed PC input and a literal remote suffix before submission. Separate Enter submitted the exact combined text. The attached client stayed on the other window. No live model, Android input, or non-loopback access was tested.

### Review

Inspect the permission-dialog and shared-draft demo and the v1 input contract. Decide whether this key set and snapshot-based feedback are enough before changing Android.

### Parallel implementation handoff (after approval)

1. **Integrator first:** Freeze the request schema, bounds, enablement policy, and domain command/result contract. Own shared workspace types and final wiring review.
2. **Two builders in parallel:** HTTP/CLI builder owns `server/src/main.rs` and `server/src/features/connection/`; tmux builder owns `server/src/features/workspace/adapters/outbound/tmux.rs`. The adapter owns stale-target rejection, literal input handling, and tmux side-effect checks; transport owns request validation, enablement, and status mapping.
3. **Optional third agent (builder or test-writer):** Own `server/tests/workspace_http.rs` and the real-bridge checks against the frozen contract. Coordinate fixture reuse; do not duplicate the private tmux harness.
4. **Integrate and review:** Run Rust formatting, Clippy, and tests. Update the deployment contract and demonstrate the approved input behavior with a local client before planning Android integration.

## Android sends input from Window detail

Depends on: Verified bridge input and reviewed [Android navigation](../improve-android-client/plan.md#reach-navigation-with-your-thumb-and-see-connection-and-window-count-at-a-glance). Blocked while navigation changes the same detail view.

### Outcome

From Android’s Window detail, append text to an existing Pi draft, submit it with Enter, and use a rendered permission dialog without changing PC focus.

### Guarantees

- Offer explicit **Insert text** and **Enter** actions and make the existing Escape, arrows, Backspace, and Ctrl+O reachable. Inserting text does not submit it. Enter can submit a draft started on the PC. Use the existing pane preview to show what the app echoes; explain that keys act on the pane's current input, not on a specific prompt.
- Advertise opt-in input with an optional capability in the v1 snapshot. Android offers input only when the current bridge explicitly advertises it and the selected pane is current. An absent or false capability leaves the detail read-only. Do not send input to probe capability. The [startup flag and HTTP input contract](connection-deployment.md#pane-input-api) still govern delivery.
- Send each action once, to the exact selected target. Reuse the [mutation reconciliation rules](decisions.md#connection-and-delivery) for input and **Open shell**. Make rejection and uncertainty visible without claiming Pi processed the input. Never queue, replay, retarget, log, or persist input. Clear unsent Android text on leaving detail, backgrounding, disconnect, or target invalidation. Preserve preview protections.

### Scope boundary

Window detail and the small bridge capability advertisement only. No terminal emulator, Pi-specific approval API, keyboard-shortcut customization, agent status, or navigation redesign.

### Done when

On an emulator or phone connected to the real opt-in bridge, append Android text to a PC-started Pi draft, verify it is not submitted, then submit with Enter. Use Ctrl+O, arrows, Enter, and Escape on the offline Pi permission fixture while another PC window stays focused. Verify older and flag-off bridges stay read-only, target loss and leaving detail disable input, and a lost acknowledgement blocks further mutations until a fresh read without replay. Run Android and bridge regressions.

### Review

Try editing a shared draft and handling one harmless permission request from Window detail. Decide whether the controls are easy to find and leave enough room to read the pane.

## Optional roadmap

None of these directions is approved for the current workflow. See [constraints](decisions.md#constraints-for-optional-work) before planning them.

- **Broader workspace management:** Discover PC-created work across sessions, reflect PC-side changes, distinguish panes where needed, and consider directory selection, confirmed window close, and explicit empty-state recovery. The [directory launch](tmux-findings.md#probe-tmux-working-directory-launch) and [stale close](tmux-findings.md#probe-stale-tmux-window-closure) probes record prerequisites for those actions.
- **Structured Pi chat:** Start with joining one existing interactive agent and verify shared conversation from both devices. That does not require directory selection, close, or recovery. Consider project launch, streaming tool activity, follow-up prompts, and explicit stop separately.
- **Agent attention:** Combine window inventory and per-agent status in the foreground overview. Keep windows without agents visible and make attention, completion, and failure distinguishable without relying on color. The [local per-pane Pi signal stream](../../../pi-tmux-signals/README.md) is only a candidate input: it cannot distinguish failure from idle or detect arbitrary blocking dialogs.
