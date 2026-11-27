# The receiver publishes expiry|pane:state|...|+pane_+pane_... (unread pane IDs).
set-option -s @glowkin-pi-lease-live '#{e|>:#{s/[|].*//:@glowkin-pi-signals},#{T:#{l:%s}}}'
set-option -s @glowkin-pi-window-unread '#{P:#{?#{m:*+#{pane_id}_*,#{@glowkin-pi-signals}},1,}}'

# L: keeps the surrounding session context; match clients actually on that session.
set-option -s @glowkin-pi-session-viewers '#{L:#{?#{&&:#{==:#{client_session},#{session_name}},#{&&:#{m:*attached*,#{client_flags}},#{!:#{client_control_mode}}}},1,}}'
set-option -s @glowkin-pi-viewed-panes '#{S:#{?#{E:@glowkin-pi-session-viewers},#{P:+#{pane_id}_},}}'

# Acknowledge only real visits, synchronously with tmux selection. Use an indexed
# hook so re-sourcing this file neither duplicates the hook nor replaces existing hooks.
set-option -s @glowkin-pi-acknowledged '#{s/[+](#{P:#{pane_id}|}never)_//:@glowkin-pi-signals}'
set-option -s @glowkin-pi-can-acknowledge '#{&&:#{E:@glowkin-pi-session-viewers},#{E:@glowkin-pi-window-unread}}'
set-hook -g session-window-changed[18765] { if-shell -F '#{E:@glowkin-pi-can-acknowledge}' { set-option -sF @glowkin-pi-signals '#{E:@glowkin-pi-acknowledged}' } }
set-hook -g client-session-changed[18765] { if-shell -F '#{E:@glowkin-pi-can-acknowledge}' { set-option -sF @glowkin-pi-signals '#{E:@glowkin-pi-acknowledged}' } }
set-hook -g client-attached[18765] { if-shell -F '#{E:@glowkin-pi-can-acknowledge}' { set-option -sF @glowkin-pi-signals '#{E:@glowkin-pi-acknowledged}' } }

# Match complete pane IDs in this window. The first matching state wins.
set-option -s @glowkin-pi-chooser-suffix '#{?#{E:@glowkin-pi-lease-live},#{?#{E:@glowkin-pi-window-unread},#[default] ●,}#{?#{P:#{?#{m:*|#{pane_id}:permission|*,#{@glowkin-pi-signals}},1,}},#[default] [permission],#{P:#{?#{m:*|#{pane_id}:needs_input|*,#{@glowkin-pi-signals}},1,}},#[default] [needs input],#{P:#{?#{m:*|#{pane_id}:busy|*,#{@glowkin-pi-signals}},1,}},#[default] [busy],#{P:#{?#{m:*|#{pane_id}:done|*,#{@glowkin-pi-signals}},1,}},#[default] [done],#{P:#{?#{m:*|#{pane_id}:idle|*,#{@glowkin-pi-signals}},1,}},#[default] [idle],},}'

# Keep the stock chooser format, arguments, and selection command.
bind-key -T prefix w choose-tree -Zw -F '#{E:tree_mode_format}#{?window_format,#{E:@glowkin-pi-chooser-suffix},}'
