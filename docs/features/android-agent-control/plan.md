# Android access to PC tmux

Next: None

Review: Read a selected PC tmux pane on Android: pending

Review brief:
- **Your call:** No input needed.
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

## Next direction: literal text

Not approved or in scope. After reviewing pane reading, decide whether to insert text only or also submit with Enter. Agree on how to handle unsent PC input and which peers may send text. Do not assume an agent protocol or terminal emulator.

## Optional roadmap

None of these directions is approved for the current workflow. See [constraints](decisions.md#constraints-for-optional-work) before planning them.

- **Broader workspace management:** Discover PC-created work across sessions, reflect PC-side changes, distinguish panes where needed, and consider directory selection, confirmed window close, and explicit empty-state recovery. The [directory launch](tmux-findings.md#probe-tmux-working-directory-launch) and [stale close](tmux-findings.md#probe-stale-tmux-window-closure) probes record prerequisites for those actions.
- **Structured Pi chat:** Start with joining one existing interactive agent and verify shared conversation from both devices. That does not require directory selection, close, or recovery. Consider project launch, streaming tool activity, follow-up prompts, and explicit stop separately.
- **Agent attention:** Combine window inventory and per-agent status in the foreground overview. Keep windows without agents visible and make attention, completion, and failure distinguishable without relying on color. The [local per-pane Pi signal stream](../../../pi-tmux-signals/README.md) is only a candidate input: it cannot distinguish failure from idle or detect arbitrary blocking dialogs.
