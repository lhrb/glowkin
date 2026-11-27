# Run after tmux creates the window; use its server and window even for detached opens.
# A naming failure must not change new-window's output or exit status.
set-hook -g after-new-window[18766] { run-shell '"$HOME/.local/bin/tmux-herd-names" #{q:socket_path} #{window_id} >/dev/null 2>&1 || :' }

# Joining an existing group links windows. Starting a new group creates one.
set-hook -g after-new-session[18766] {
    if-shell -F '#{?session_grouped,#{==:#{session_group_size},1},1}' {
        run-shell '"$HOME/.local/bin/tmux-herd-names" #{q:socket_path} #{window_id} >/dev/null 2>&1 || :'
    }
}
