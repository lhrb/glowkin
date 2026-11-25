# Deferred Android agent control work

These gaps do not block [the single-session prototype](plan.md#see-and-open-windows-in-one-existing-session). Revisit them when the corresponding action is next. Keep the prototype and extend it; do not ship a partial destructive action.

| Gap | Until then | Pick up when |
|---|---|---|
| Multiple sessions, linked windows, and multiple agent panes | One existing session; no session picker. | Add multi-session discovery or a workflow that needs it. Keep session, window, and pane identity distinct. |
| No tmux server or no sessions left | Start tmux on the PC; Android reports it is unavailable. | Add explicit remote recovery. Do not create a replacement automatically. |
| Remote close and stale targets | Close on the PC. Android offers no close action. | Before exposing close, require confirmation and verify the [guarded-close approach](plan.md#probe-stale-tmux-window-closure), generation lifecycle, and linked and final window behavior. |
| Project directory selection and launch | Open a plain shell at home. | Before accepting a path, verify literal directory entry. Shell may fall back to home with a pane warning; pi must not start on directory-entry failure. See [the cwd experiment](plan.md#probe-tmux-working-directory-launch). |
| Live pi integration and attention | Use pi on the PC. | Start by joining one existing agent and verify the shared conversation from both devices. Add launch, broader chat cases, and attention as separate follow-ups. |
| Broader compatibility and test matrix | Verify the current PC's setup and the new slice's guarantees. | Expand verification when adding behavior or promising support for more setups. |
