#!/bin/sh
set -eu

CDPATH= cd -- "$(dirname -- "$0")"
product=$(pwd -P)
bin=$HOME/.local/bin
share=$HOME/.local/share/glowkin-pi

for tool in cargo pi tmux; do
    command -v "$tool" >/dev/null || { echo "Missing required command: $tool" >&2; exit 1; }
done

cargo build --locked --release --target-dir "$product/target"
install -d "$bin" "$share"
install -d -m 700 "$HOME/.local/state/glowkin-pi/work"
install -m 755 target/release/pi-tmux-signals "$bin/"
install -m 644 chooser.tmux startup.tmux extension.ts permission-system.ts "$share/"

cd -- "$share"
pi install extension.ts
pi install permission-system.ts

printf '\nAdd this line to your tmux config, then start a fresh tmux server:\n'
printf '%s\n' 'source-file ~/.local/share/glowkin-pi/startup.tmux'
