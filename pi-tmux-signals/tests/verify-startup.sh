#!/usr/bin/env bash
# Exercise the installed workflow with two private tmux servers and a globally loaded offline model.
set -euo pipefail
umask 077
product=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
pi=$(command -v pi)
root=$(mktemp -d /tmp/pi-startup.XXXXXXXX)
home=$root/home
bin=$home/.local/bin
share=$home/.local/share/glowkin-pi
signal=$home/.local/state/glowkin-pi/work/signals.sock
default=$root/tmux-$(id -u)/default
named=$root/'work, non-default.sock'
configured=$default
unconfigured=$root/unconfigured.sock
config=$home/.tmux.conf
pid=
clean=(env -i PATH="$bin:$(dirname -- "$pi"):/usr/bin:/bin" SHELL=/bin/sh HOME="$home" LANG=C.UTF-8
  PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 PI_TELEMETRY=0)
client() { "${clean[@]}" tmux -N -S "$1" "${@:2}"; }
work() { client "$configured" "$@"; }
view() { client "$root/view.sock" "$@"; }
cleanup() {
  local status=$?
  trap - EXIT
  for socket in "$root/view.sock" "$unconfigured" "$default" "$named"; do
    client "$socket" kill-server >/dev/null 2>&1 || true
  done
  if [[ -n $pid ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  if [[ $status == 0 ]]; then wait_for test ! -e "$signal" || status=1; fi
  rm -rf -- "$root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
fail() { printf '%s\n' "$*" >&2; exit 1; }
wait_for() {
  local i
  for ((i = 0; i < 200; i++)); do "$@" && return; sleep 0.05; done
  return 1
}
marked() { grep -Fxq -- "$2" "$root/$1/trace" 2>/dev/null; }
mark_wait() { wait_for marked "$1" "$2" || fail "$1 never reported '$2'."; }

# Set up a private receiver and fixture, capturing receiver stderr for the collision check.
cargo build --quiet --locked --release --manifest-path "$product/Cargo.toml" --target-dir "$root/target"
install -d "$bin" "$share" "$home/.pi/agent/extensions" "${default%/*}"
install -d -m 700 "${signal%/*}"
install -m 755 "$root/target/release/pi-tmux-signals" "$bin/"
install -m 644 "$product/chooser.tmux" "$share/"
install -m 644 "$product/extension.ts" "$home/.pi/agent/extensions/glowkin-pi-signals.ts"
install -m 644 "$product/tests/startup-fixture.ts" "$home/.pi/agent/extensions/startup-fixture.ts"
printf '%s\n' '{"defaultProvider":"startup","defaultModel":"startup","retry":{"enabled":false}}' > "$home/.pi/agent/settings.json"
printf '%s\n' \
  'source-file ~/.local/share/glowkin-pi/chooser.tmux' \
  'set-option -soq @glowkin-pi-socket pending' \
  "run-shell -b 'exec \"\$HOME/.local/bin/pi-tmux-signals\" --serve \"\$HOME/.local/state/glowkin-pi/work/signals.sock\" #{q:socket_path} #{pid}:#{start_time} 2>\"$root/startup-error\"'" > "$config"
mkdir "$root"/{default-first,default-second,first,second,nested,blocked}

start() {
  "${clean[@]}" tmux -S "$configured" -f "$config" new-session -d -s startup -n observed -x 120 -y 40 -c "$root/$1" pi \
    \; set-option -w remain-on-exit on \; new-window -n plain sleep 300
}
registered() {
  local encoded
  encoded=$(work show-options -s -v @glowkin-pi-socket 2>/dev/null) && [[ $encoded == "$expected_hex:"* && -S $signal ]]
}
expected_hex=$(printf '%s' "$signal" | od -An -v -tx1 | tr -d ' \n')
await_receiver() {
  wait_for registered || { [[ ! -s $root/startup-error ]] || tail -n 1 "$root/startup-error" >&2; fail 'Installed receiver did not register its expected socket.'; }
  [[ $(stat -c '%a:%u' "${signal%/*}") == "700:$(id -u)" && $(stat -c '%u' "$signal") == "$(id -u)" ]] || fail 'Signal socket is not in an owned private directory.'
  [[ $(work show-messages -JT | grep -c -- 'pi-tmux-signals.*--serve') == 1 ]] || fail 'Expected one receiver job in the configured server.'
}
open_chooser() {
  "${clean[@]}" tmux -S "$root/view.sock" -f /dev/null new-session -d -x 120 -y 40 \
    "${clean[@]}" TERM=xterm-256color tmux -N -S "$1" attach -t startup
  attached() { [[ -n $(client "$1" list-clients -F '#{client_name}') ]]; }
  wait_for attached "$1" || fail 'Chooser client never attached.'
  view send-keys C-b w
}
chooser_is() {
  local row
  row=$(view capture-pane -p | grep -m1 -E ': observed([* -]|$)') || return 1
  if [[ -n $1 ]]; then [[ $row == *" [$1]" ]]; else [[ $row != *' ['* ]]; fi || return 1
  case ${2:-} in
    unread) [[ $row == *" ● [$1]" ]] ;;
    seen) [[ $row != *' ● '* ]] ;;
  esac
}
expect_chooser() {
  if ! wait_for chooser_is "$@"; then view capture-pane -p >&2; fail "Expected chooser label '${1:-none}' ${2:-}."; fi
}
close_chooser() {
  local target
  target=$(view display-message -p '#{pane_pid}')
  view send-keys q
  view kill-server
  viewer_gone() { ! view list-sessions >/dev/null 2>&1; }
  wait_for viewer_gone || fail "Viewer $target did not stop."
}
submit() {
  client "$1" send-keys -t startup:observed -l 'offline run'
  client "$1" send-keys -t startup:observed Enter
}
server_stopped() { ! work list-sessions >/dev/null 2>&1; }
stop() {
  work kill-server
  wait_for server_stopped || fail 'Configured tmux server did not stop.'
}
status_cycle() {
  start "$1"
  await_receiver
  mark_wait "$1" ready
  open_chooser "$configured"
  expect_chooser idle
  submit "$configured"
  mark_wait "$1" running
  expect_chooser busy
  touch "$root/$1/release"
  mark_wait "$1" settled
  expect_chooser done unread
  close_chooser
}

status_cycle default-first
stop
status_cycle default-second
stop
wait_for test ! -e "$signal" || fail 'Default server left its signal socket behind.'
printf 'Default server: fresh and restart both use the same endpoint and show [idle] → [busy] → [done].\n'

configured=$named
start first
await_receiver
mark_wait first ready
# Start the unconfigured server from a real opted-in pane, inheriting that pane's environment.
work new-window -d -n nest -c "$root/nested" \
  "exec tmux -S '$unconfigured' -f /dev/null new-session -d -s startup -n observed -x 120 -y 40 pi \\; set-option -w remain-on-exit on"
mark_wait nested ready
pane=$(work display-message -p -t startup:observed '#{pane_id}')
[[ $pane == "$(client "$unconfigured" display-message -p -t startup:observed '#{pane_id}')" ]] || fail 'Expected overlapping pane IDs.'
no_opt_in() {
  [[ -z $(client "$unconfigured" show-options -sqv @glowkin-pi-socket) &&
     -z $(client "$unconfigured" show-options -sqv @glowkin-pi-signals) ]] || fail 'Unconfigured server has Glowkin state.'
  client "$unconfigured" list-keys -T prefix w | grep -q 'choose-tree -Zw$' || fail 'Unconfigured chooser binding changed.'
  if client "$unconfigured" show-messages -JT | grep -q -- '--serve'; then fail 'Unconfigured server started a receiver job.'; fi
}
no_opt_in
open_chooser "$unconfigured"
expect_chooser ''
close_chooser
work select-window -t startup:plain
open_chooser "$configured"
expect_chooser idle
submit "$configured"
mark_wait first running
expect_chooser busy
submit "$unconfigured"
mark_wait nested running
touch "$root/nested/release"
mark_wait nested settled
client "$unconfigured" send-keys -t startup:observed C-d
nested_dead() { [[ $(client "$unconfigured" display-message -p -t startup:observed '#{pane_dead}') == 1 ]]; }
wait_for nested_dead || fail 'Unconfigured Pi did not exit.'
expect_chooser busy
no_opt_in
touch "$root/first/release"
mark_wait first settled
expect_chooser done unread
work select-pane -t startup:observed -T 'unread-preview-canary'
view send-keys Up
previewed_observed() { view capture-pane -p | grep -qF '0:unread-preview-canary'; }
wait_for previewed_observed || fail 'Chooser did not preview the observed window.'
[[ $(work list-clients -F '#{window_name}') == plain ]] || fail 'Preview changed the current window.'
expect_chooser done unread
view send-keys Enter
selected_observed() { [[ $(work list-clients -F '#{window_name}') == observed ]]; }
wait_for selected_observed || fail 'Chooser did not select the observed window.'
view send-keys C-b w
expect_chooser done seen
view send-keys q
chooser_closed() { [[ $(work display-message -p -t startup:observed '#{pane_in_mode}') == 0 ]]; }
wait_for chooser_closed || fail 'Chooser did not close before running Pi in the current window.'
: > "$root/first/trace"
rm -- "$root/first/release"
submit "$configured"
mark_wait first running
active_busy() { [[ $(work display-message -p -t startup:observed '#{E:@glowkin-pi-chooser-suffix}') == '#[default] [busy]' ]]; }
wait_for active_busy || fail 'Current window did not show busy without a dot.'
touch "$root/first/release"
mark_wait first settled
view send-keys C-b w
expect_chooser done seen
close_chooser
printf 'Fresh non-default server: chooser preview preserved unread completion; selection kept [done] but cleared the dot. An active Pi run finished without a dot. The nested unconfigured server stayed independent.\n'

# Restart at once: no socket cleanup, rebuild, setup, or config changes in between.
stop
status_cycle second
client "$configured" send-keys -t startup:observed C-d
open_chooser "$configured"
expect_chooser ''
close_chooser
stop
wait_for test ! -e "$signal" || fail 'Receiver left its socket behind after tmux stopped.'
no_opt_in
printf 'Restart: same private endpoint, [idle] → [busy] → [done], and disconnect removed the suffix.\n'

start_blocker() {
  python3 - "$signal" <<'PY' &
import socket, sys
with socket.socket(socket.AF_UNIX) as s:
    s.bind(sys.argv[1])
    s.listen(1)
    conn, _ = s.accept()
    with conn:
        conn.sendall(b"unrelated listener")
PY
  pid=$!
  wait_for test -S "$signal" || fail 'Unrelated listener did not bind.'
}
probe_blocker() {
  python3 - "$signal" <<'PY'
import socket, sys
with socket.socket(socket.AF_UNIX) as s:
    s.settimeout(2)
    s.connect(sys.argv[1])
    assert s.recv(128) == b"unrelated listener"
PY
  wait "$pid"
  pid=
  rm -- "$signal" # Remove the blocker, not a Glowkin socket.
}

# A live unrelated listener at the configured path must still be reachable by name after startup.
start_blocker
start blocked
collision() { grep -q 'Address already in use' "$root/startup-error"; }
wait_for collision || fail 'Expected the receiver to decline the unrelated endpoint.'
[[ $(work show-options -sqv @glowkin-pi-socket) == pending ]] || fail 'Receiver advertised an unrelated endpoint.'
probe_blocker
stop
start blocked
await_receiver
open_chooser "$configured"
expect_chooser idle
close_chooser
printf 'Collision: unrelated listener remained reachable by pathname; startup worked after removing the blocker.\n'

# Stop the receiver but keep the Pi and tmux generation alive. The old registration
# must not send the reconnecting Pi to a different listener at the same path.
job=$(work show-messages -JT | grep -m1 -- 'pi-tmux-signals.*--serve')
[[ $job =~ pid=([0-9]+) ]] || fail 'Could not identify the receiver job.'
kill -TERM "${BASH_REMATCH[1]}"
wait_for test ! -e "$signal" || fail 'Receiver did not clean up after TERM.'
start_blocker
lease_expired() { [[ -z $(work display-message -p -t startup:observed '#{E:@glowkin-pi-chooser-suffix}') ]]; }
wait_for lease_expired || fail 'Chooser label did not expire when the receiver stopped.'
probe_blocker
printf 'Stale registration: Pi did not connect to a different listener at the same path.\n'
