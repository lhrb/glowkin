# Android access to PC tmux

Goal: Read and control a selected PC tmux pane from Android. Keep the [trusted-network boundary](connection-deployment.md#scope-and-security).

| Outcome | Depends on | Status |
| --- | --- | --- |
| See and open windows in one existing session | Existing PC tmux session | Done |
| Read a selected PC tmux pane on Android | Window inventory and targeting | Done |
| Drive one existing Pi pane through the bridge | v1 inventory and pane reads | Done |
| Android sends input from Window detail | Verified bridge input and existing Window detail | Done |

Next: None

Review: Android sends input from Window detail: pending

### Review brief: Android sends input from Window detail

- **Your call:** No input needed.
- **What happened:** Android can send text and eight keys to the selected pane on an opt-in bridge. The emulator passed the shared-draft and offline Pi permission workflows without changing PC focus. Pane-preview review stays separate.
- **See it:** On a disposable Pi pane, start a PC draft and tap **Insert text** on Android. The emulator showed the combined draft without submission, then separate **Enter** submitted it.

## Android sends input from Window detail

### Outcome

From the existing Window detail, append text to a shared Pi draft, submit it with Enter, and use a rendered permission dialog without changing PC focus.

### Guarantees

- Offer separate **Insert text** and **Enter** actions, plus Escape, four arrows, Backspace, and Ctrl+O. Inserting never submits. Enter can submit text typed on the PC. Show echoed input through the existing preview and explain that actions go to the pane's current input, not to a particular prompt.
- Add an optional input capability to the v1 snapshot that advertises `--enable-pane-input`. Only an explicit `true` from the current bridge and a current eligible selection allow Android to offer input. Absent or `false` means read-only. Never probe with an input request. The flag remains the server's only input enablement switch; the advertisement neither replaces it nor adds authorization. Preserve the [v1 contract and bounds](connection-deployment.md#pane-input-api).
- Send each explicit action once to the exact current pane, with no fallback. Show definite rejection and uncertain delivery separately. Acknowledgement means injection, not application processing. After uncertainty, block input and **Open shell** until an authoritative read **started after** the uncertainty succeeds. Follow the [reconciliation rules](decisions.md#connection-and-delivery).
- Never queue, replay, retarget, log, or persist input. Clear unsent Android text on leaving detail, backgrounding, disconnect, or target invalidation. Preserve preview privacy and discard obsolete responses.

### Scope boundary

Window detail and the additive bridge advertisement only. No terminal emulator, Pi approval API, key customization, agent status, or navigation redesign.

### Done when

- On an emulator or phone with the real opt-in bridge, insert Android text into a PC-started Pi draft. Observe the combined draft before submission, then verify separate Enter submits it. Use Ctrl+O, arrows, Enter, and Escape on the offline Pi permission fixture while another PC window stays focused.
- Verify the exact target and single-send behavior, absent/false capability without probing, draft clearing at each boundary, and rejection versus uncertainty. Simulate a lost acknowledgement: no replay, no further mutation until a fresh successful read, and no unlock from an older or failed read.
- Run [Android and bridge regressions](connection-deployment.md#development-and-automated-checks), including preview, **Open shell**, and snapshot compatibility at existing size limits.

### Verification and review

- Passed 26 bridge tests, strict Clippy, Rust formatting, and debug/release builds. Passed 15 JVM tests, Android debug builds and lint, and all 25 emulator tests on the final rerun.
- Verified exact single sends, absent/false capability without probing, clearing drafts, rejection versus uncertainty, and no replay or mutation before a fresh successful read. Activity recreation preserved the delivery gate, not the draft or selection. See [delivery decisions](decisions.md#pane-input).
- One existing overview layout assertion failed once, then passed on the unchanged full rerun and three isolated runs on the baseline. No cause was established. Lint reported 15 pre-existing warnings and no errors. See [recorded verification](connection-deployment.md#recorded-verification) for platform gaps.

Try a shared draft and one harmless permission request. Decide whether the eight keys are easy to reach and leave enough room for the preview.

## Optional roadmap

Unapproved directions. See [constraints](decisions.md#constraints-for-optional-work) before planning.

- **Workspace management:** Discover existing work across sessions; consider directory selection, confirmed close, and explicit recovery. See the [tmux probes](tmux-findings.md).
- **Structured Pi chat:** Join one existing interactive agent from both devices. Consider project launch, streaming activity, follow-ups, and stop separately.
- **Agent attention:** Show per-agent status without hiding windows or relying on color. The [local Pi signal stream](../../../pi-tmux-signals/README.md) is only a candidate; it cannot distinguish failure from idle or detect arbitrary blocking dialogs.
