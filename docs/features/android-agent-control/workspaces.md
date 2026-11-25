# Manage tmux windows and select project directories

Depends on: connection.md

## Outcome
Browse existing tmux sessions and windows, create or close windows, and select a PC directory without typing shell commands.

## Guarantees
- Discover all sessions and windows on the server user's configured tmux server, including those created before Glowkin started.
- Identify windows by session and stable identity, not display name or mutable index alone.
- Reflect PC-side creation, closure, and renaming without restarting Android.
- List panes when needed to distinguish multiple agents within a window; switching the Android selection does not change the PC's active tmux window.
- **Open shell** creates a normal interactive shell window in the chosen session, without starting pi. When no sessions remain, an explicit action creates a fresh tmux session with one shell window, even if the server has exited or never started. Neither action requires PC interaction or a bridge restart. Report actual startup failure; do not show a session as created if no shell started.
- Browse and select directories accessible to the server account, including navigating parents; do not offer file editing or file-management actions.
- With no directory selected, open the shell at the server user's home directory. With a directory selected, try to enter the literal path before starting the interactive shell. If entry fails, open the shell at home and warn in the pane. Android must not claim the selected directory was entered unless verified.
- Treat directory paths and window names as data, including spaces and shell metacharacters.
- Closing a window requires explicit confirmation that every process inside, including agents, will terminate. The last window may be closed even if that ends the final session. Do not start a replacement automatically.
- A missing session/window or failed tmux operation reports failure without affecting another target.

## Scope boundary
The configured tmux server only; no arbitrary terminal input, direct session deletion, pane-layout management, file content browser, or restriction on independent PC-side tmux actions.

## Done when
Behavioral and tmux integration tests verify discovery, PC-side changes, creation, confirmed closure, and stale identities. Verify the shell's actual working directory for a literal path with spaces and `#{session_name}`, home when no directory is selected, and home with a warning when the selected path disappears or cannot be entered. Cover launches in an existing session and from a cold tmux start. Verify that closing the final window leaves no replacement and that an explicit request recovers without restarting the bridge. Android tests verify selection, destructive confirmation, and the create-session action in the empty state.

## Review
On Android, open a plain shell and verify on the PC that it is interactive and no pi process started. Try a selected directory that has disappeared and verify that the shell opens at home with a warning.
