source-file ~/.local/share/glowkin-pi/chooser.tmux
set-option -soq @glowkin-pi-socket pending
run-shell -b 'exec "$HOME/.local/bin/pi-tmux-signals" --serve "$HOME/.local/state/glowkin-pi/work/signals.sock" #{q:socket_path} #{pid}:#{start_time}'
