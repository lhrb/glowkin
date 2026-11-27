#!/usr/bin/env bash
set -euo pipefail

product=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
tmp=$(mktemp -d)
t() { tmux -S "$tmp/socket" "$@"; }
cleanup() {
  t kill-server >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup EXIT

cargo build --quiet --locked --manifest-path "$product/Cargo.toml" --target-dir "$tmp/target"
export HOME=$tmp/home SHELL=/bin/sh
bin=$HOME/.local/bin/tmux-herd-names
install -Dm755 "$tmp/target/debug/tmux-herd-names" "$bin"
name() { t display-message -p -t "$1" '#{window_name}'; }
new() { t new-window -dP -F '#{window_id}' -t "$1:" "${@:2}"; }
expect() {
  if [[ $1 != "$2" ]]; then
    printf 'Expected <%s>, got <%s>\n' "$2" "$1" >&2
    exit 1
  fi
}

# The first new-session loads the hook and names its own window.
t -f "$product/herd.tmux" new-session -ds one
expect "$(name @0)" Pip

# Simulate an existing default-named window that grouping must not rename.
t rename-window -t one: seed
t set-window-option -g automatic-rename off
t set-window-option -u -t one: automatic-rename
t new-session -ds two -n Pip
t set-hook -g 'after-new-window[18765]' 'set-option -sq @other-hook ran'
t source-file "$product/herd.tmux"
t source-file "$product/herd.tmux"
t new-session -ds grouped -t one
expect "$(name @0)" seed
t kill-session -t grouped
t rename-window -t @0 seed
t new-session -ds fresh
expect "$(t display-message -p -t fresh: '#{window_name}')" Moss
t kill-session -t fresh
t new-session -ds group-leader -t new-group
expect "$(t display-message -p -t group-leader: '#{window_name}')" Moss
t kill-session -t group-leader
t new-session -ds named -n custom
expect "$(t display-message -p -t named: '#{window_name}')" custom
t kill-session -t named
t set-window-option -g automatic-rename on

explicit=$(new two -n Fern)
first=$(new one)
expect "$(name "$first")" Moss
expect "$(name "$explicit")" Fern
expect "$(name @0)" seed
expect "$(name @1)" Pip
expect "$(t show-options -sv @other-hook)" ran
t rename-window -t "$first" custom
second=$(new two)
expect "$(name "$first")" custom
expect "$(name "$second")" Moss
t kill-window -t "$second"
t set-window-option -g automatic-rename off
expect "$(name "$(new one)")" Moss

# Fill the remaining 13 slots from both sessions at once.
workers=()
for i in {1..13}; do
  session=one
  (( i % 2 )) || session=two
  new "$session" > "$tmp/window-$i" &
  workers+=("$!")
done
for worker in "${workers[@]}"; do wait "$worker"; done
for i in {1..13}; do name "$(<"$tmp/window-$i")"; done > "$tmp/names"
expect "$(sort -u "$tmp/names" | wc -l)" 13
t set-window-option -g automatic-rename on
expect "$(t list-windows -a -F '#{automatic-rename}' | sort -u)" 0
expect "$(t list-windows -a -F '#{window_name}' | sort -u | wc -l)" 18
fallback=$(new two)
expect "$(t display-message -p -t "$fallback" '#{automatic-rename}')" 1

t kill-window -t "$(<"$tmp/window-1")"
mv -- "$bin" "$tmp/helper-disabled"
opened=$(t new-window -dP -F 'opened #{window_id}' -t two:)
expect "${opened%% *}" opened
expect "$(t display-message -p -t "${opened#* }" '#{automatic-rename}')" 1
printf '%s\n' 'tmux herd names verified'
