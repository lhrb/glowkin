# Manage tmux windows and select project directories

Depends on: connection.md

## Outcome
Browse existing tmux sessions and windows, create or close windows, and select a PC directory without typing shell commands.

## Guarantees
- Discover all sessions and windows on the server user's configured tmux server, including those created before Glowkin started.
- Identify windows by session and stable identity, not display name or mutable index alone.
- Reflect PC-side creation, closure, and renaming without restarting Android.
- List panes when needed to distinguish multiple agents within a window; switching the Android selection does not change the PC's active tmux window.
- Create a shell window in a chosen session and directory. When no sessions remain, Android offers an explicit action to create a fresh tmux session with one shell window in the chosen directory. The running bridge can do this even if the configured tmux server has exited or never started, without PC interaction or a bridge restart. Report startup failure explicitly; do not show a session as created on failure.
- Browse and select directories accessible to the server account, including navigating parents; do not offer file editing or file-management actions.
- Missing or unreadable directories produce explicit errors and never launch in a silently substituted directory.
- Treat directory paths and window names as data, including spaces and shell metacharacters.
- Closing a window requires explicit confirmation that every process inside, including agents, will terminate. The last window may be closed even if that ends the final session. Do not start a replacement automatically.
- A missing session/window or failed tmux operation reports failure without affecting another target.

## Scope boundary
The configured tmux server only; no arbitrary terminal input, direct session deletion, pane-layout management, file content browser, or restriction on independent PC-side tmux actions.

## Done when
Behavioral and tmux integration tests verify discovery, PC-side changes, directory errors and special characters, creation, confirmed closure, and stale identities. Verify that closing the final window leaves no replacement and that a subsequent explicit request creates a fresh session and shell in the chosen directory without restarting the bridge, including when no tmux server is running. Android tests verify selection, destructive confirmation, and the create-session action in the empty state.
