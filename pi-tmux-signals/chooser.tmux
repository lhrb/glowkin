# The receiver publishes a leased snapshot: expiry|pane:state|pane:state|...
# Match complete pane IDs in this window. The first matching state wins.
set-option -s @glowkin-pi-chooser-suffix '#{?#{e|>:#{s/[|].*//:@glowkin-pi-signals},#{T:#{l:%s}}},#{?#{P:#{?#{m:*|#{pane_id}:permission|*,#{@glowkin-pi-signals}},1,}},#[default] [permission],#{P:#{?#{m:*|#{pane_id}:needs_input|*,#{@glowkin-pi-signals}},1,}},#[default] [needs input],#{P:#{?#{m:*|#{pane_id}:busy|*,#{@glowkin-pi-signals}},1,}},#[default] [busy],#{P:#{?#{m:*|#{pane_id}:done|*,#{@glowkin-pi-signals}},1,}},#[default] [done],#{P:#{?#{m:*|#{pane_id}:idle|*,#{@glowkin-pi-signals}},1,}},#[default] [idle],},}'

# Keep the stock chooser format, arguments, and selection command.
bind-key -T prefix w choose-tree -Zw -F '#{E:tree_mode_format}#{?window_format,#{E:@glowkin-pi-chooser-suffix},}'
