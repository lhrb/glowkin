#!/usr/bin/env bash
# Run from any directory with Bash, Pi 1.6.0, tmux and Node 22 on PATH.
set -euo pipefail
umask 077
src=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
pi=$(command -v pi)
permission=${PI_PERMISSION_PACKAGE:-$HOME/.pi/agent/npm/node_modules/@gotgenes/pi-permission-system}
permission=$(realpath "$permission")
product=$(realpath "$src/../../../pi-tmux-signals")
root=$(mktemp -d /tmp/pi-capture.XXXXXXXX)
receiver_pid=
pids=()

clean=(env -i PATH=/usr/bin:/bin SHELL=/bin/sh HOME="$root/home" XDG_RUNTIME_DIR="$root/runtime"
  CAPTURE_ROOT="$root" CAPTURE_TMUX="$root/tmux" PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 PI_TELEMETRY=0)
signal_socket=$root/runtime/signals.sock
signals=$root/received
client() { "${clean[@]}" tmux -N -S "$root/tmux" "$@"; }
fail() { printf '%s\n' "$*" >&2; exit 1; }
wait_for() {
  local i
  for ((i = 0; i < 300; i++)); do "$@" && return; sleep 0.05; done
  return 1
}
gone() { ! kill -0 "$1" 2>/dev/null || [[ $(ps -o stat= -p "$1") == Z* ]]; }
server_gone() { ! client show-options -s -v exit-empty >/dev/null 2>&1; }
stop_receiver() {
  if [[ -n $receiver_pid ]]; then
    kill "$receiver_pid" 2>/dev/null || true
    wait "$receiver_pid" 2>/dev/null || true
    receiver_pid=
  fi
}
cleanup() {
  local status=$? pid
  trap - EXIT
  stop_receiver
  client kill-server >/dev/null 2>&1 || true
  if ! wait_for server_gone; then printf 'Private tmux server still running.\n' >&2; status=1; fi
  for pid in "${pids[@]}"; do
    if ! wait_for gone "$pid"; then printf 'Fixture pane process still running: %s\n' "$pid" >&2; status=1; fi
  done
  if ! rm -rf -- "$root" || [[ -e $root ]]; then printf 'Temporary directory cleanup failed.\n' >&2; status=1; fi
  (( status != 0 )) || printf 'Cleanup verified.\n'
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir "$root"/{home,runtime}
cp -- "$src/capture-fixture.ts" "$root/"
[[ $(cd "$root" && "${clean[@]}" "$pi" --no-extensions --version) == 1.6.0 ]] || fail 'Expected Pi 1.6.0.'
"${clean[@]}" node -e 'const p = require(process.argv[1]); if (p.name !== "@gotgenes/pi-permission-system" || p.version !== "42.2.1") process.exit(1)' \
  "$permission/package.json" || fail 'Expected installed @gotgenes/pi-permission-system 42.2.1.'

prepare_pane() {
  local dir=$root/$1 config
  mkdir -p "$dir"/{cwd,home,config,data,state,cache,tmp,agent,sessions}
  config=$dir/agent/extensions/pi-permission-system
  mkdir -p "$config"
  printf '%s\n' '{"retry":{"enabled":false}}' > "$dir/agent/settings.json"
  printf '%s\n' '{"doublePressToConfirm":false,"permissionReviewLog":false,"debugLog":false,"permission":{"*":"deny","fixture_ask":"ask","fixture_silent":"allow"}}' > "$config/config.json"
  pane_command=(env HOME="$dir/home" TMPDIR="$dir/tmp" XDG_CONFIG_HOME="$dir/config" XDG_DATA_HOME="$dir/data"
    XDG_STATE_HOME="$dir/state" XDG_CACHE_HOME="$dir/cache" PI_CODING_AGENT_DIR="$dir/agent"
    PI_CODING_AGENT_SESSION_DIR="$dir/sessions" GLOWKIN_PI_SIGNALS_SOCKET="$signal_socket" "$pi" --model capture-fixture/fixture
    --no-session --tools fixture_ask,fixture_silent --no-mcp --no-extensions --no-skills --no-prompt-templates --no-themes --no-context-files
    -e "$root/capture-fixture.ts" -e "$permission/src/index.ts" "${capture_args[@]}")
}
record_pid() { pids+=("$(client display-message -p -t "$1" '#{pane_pid}')"); }
marked() { grep -Fxq -- "$2" "$root/$1/trace" 2>/dev/null; }
ready_or_failed() { marked "$1" ready || [[ -f $root/$1/preflight-failed ]]; }
require_ready() {
  local dir=$1
  wait_for ready_or_failed "$dir" || fail "$dir: fixture did not reach preflight; no input sent."
  [[ ! -f $root/$dir/preflight-failed ]] || fail "$dir: preflight failed ($( < "$root/$dir/preflight-failed" )); no input sent."
  wait_for marked "$dir" permission-ready || fail "$dir: real permission extension did not start; no input sent."
}
mark_wait() { wait_for marked "$1" "$2" || fail "$1: missing fixture milestone '$2'."; }
view_has() { client capture-pane -p -t "$1" | grep -F -- "$2" >/dev/null; }
view_wait() { wait_for view_has "$1" "$2" || fail "$1: terminal did not display '$2'."; }
submit() {
  client send-keys -t "$1" -l "$2"
  client send-keys -t "$1" Enter
}
reset_trace() { : > "$root/$1/trace"; rm -f -- "$root/$1/release"; }
release() { touch "$root/$1/release"; }

start_panes() {
  prepare_pane left
  "${clean[@]}" tmux -S "$root/tmux" -f /dev/null new-session -d -s capture -x 120 -y 40 -c "$root/left/cwd" "${pane_command[@]}" \; set-option -w remain-on-exit on
  left=$(client display-message -p -t '=capture:0.0' '#{pane_id}')
  record_pid "$left"
  prepare_pane right
  right=$(client split-window -v -d -P -F '#{pane_id}' -t "$left" -c "$root/right/cwd" "${pane_command[@]}")
  record_pid "$right"
  [[ -S $root/tmux && $(client list-sessions -F '#{session_attached}') == 0 && $left != "$right" &&
    $(client display-message -p -t "$left" '#{window_id}') == "$(client display-message -p -t "$right" '#{window_id}')" ]] \
    || fail 'Expected two panes in the same detached private tmux window.'
  require_ready left
  require_ready right
}
respawn_right() {
  local instance=$1
  prepare_pane "$instance"
  client respawn-pane -t "$right" -c "$root/$instance/cwd" "${pane_command[@]}"
  record_pid "$right"
  require_ready "$instance"
}

# Exercise the shipped receiver. Build without fetching, and keep artifacts disposable.
(cd "$root" && "${clean[@]}" cargo build --quiet --locked --offline --manifest-path "$product/Cargo.toml" --target-dir "$root/target")
"${clean[@]}" "$root/target/debug/pi-tmux-signals" "$signal_socket" > "$signals" &
receiver_pid=$!
wait_for test -S "$signal_socket" || fail 'Private receiver did not start.'
capture_args=(-e "$product/extension.ts")
start_panes

last_signal() { awk -F '\t' -v pane="$1" '$1 == pane {line=$0} END {print line}' "$signals"; }
latest_is() { [[ $(last_signal "$1") == *$'\t'"$2" ]]; }
await_state() { wait_for latest_is "$1" "$2" || fail "$1: expected latest signal '$2'."; }
instance() { last_signal "$1" | cut -f2; }
begin() { reset_trace "$1"; first_line=$(wc -l < "$signals"); }
state_count() {
  awk -F '\t' -v start="$first_line" -v pane="$1" -v state="$2" 'NR > start && $1 == pane && $3 == state {n++} END {print n+0}' "$signals"
}
expect_count() { [[ $(state_count "$1" "$2") == "$3" ]] || fail "$1: expected $3 '$2' signal(s) in current case."; }
end_run() { mark_wait "$1" 'settled normal'; await_state "$2" done; expect_count "$2" done 1; }
ask() { submit "$2" 'ask fixture-prompt-canary'; mark_wait "$1" permission-prompt; view_wait "$2" 'Permission Required'; }

await_state "$left" idle
await_state "$right" idle
first_line=0
expect_count "$left" done 0
expect_count "$right" done 0
[[ $(instance "$left") != "$(instance "$right")" ]] || fail 'Concurrent Pi instances have the same source ID.'
printf 'Startup: %s and %s are independently idle.\n' "$left" "$right"

begin left
ask left "$left"
await_state "$left" needs_permission
latest_is "$right" idle || fail 'Permission state leaked to the other pane.'
expect_count "$right" needs_permission 0
client send-keys -t "$left" y
mark_wait left 'decision user_approved'
mark_wait left 'executed fixture_ask'
mark_wait left 'after tool'
await_state "$left" busy
release left
end_run left "$left"
expect_count "$left" needs_permission 1

begin right
ask right "$right"
await_state "$right" needs_permission
latest_is "$left" done || fail 'Right-pane prompt changed left-pane state.'
client send-keys -t "$right" Escape
mark_wait right 'decision user_denied'
mark_wait right 'after tool'
await_state "$right" busy
! marked right 'executed fixture_ask' || fail 'Dismissed permission still executed the tool.'
release right
end_run right "$right"
expect_count "$right" needs_permission 1
printf 'Real approval and Escape dismissal both clear permission before settlement.\n'

begin left
submit "$left" 'silent fixture-prompt-canary'
mark_wait left 'decision policy_allow'
mark_wait left 'executed fixture_silent'
mark_wait left 'after tool'
await_state "$left" busy
release left
end_run left "$left"
expect_count "$left" needs_permission 0

begin left
submit "$left" /fixture-dialog
view_wait "$left" 'Unrelated fixture dialog'
client send-keys -t "$left" Escape
mark_wait left 'dialog closed'
# The next settled run gives a causal boundary on the same signal stream.
submit "$left" 'queued fixture-prompt-canary'
mark_wait left 'first request'
await_state "$left" busy
client send-keys -t "$left" -l 'follow-up fixture-prompt-canary'
client send-keys -t "$left" -H 1b 0d # Alt+Enter queues a follow-up, not steering.
mark_wait left 'follow-up queued'
mark_wait left 'final request'
sleep 0.2 # Let the asynchronous socket drain while final work is held.
expect_count "$left" done 0
latest_is "$left" busy || fail 'Pi did not stay busy for the queued follow-up.'
release left
end_run left "$left"
expect_count "$left" needs_permission 0
printf 'Silent policy and unrelated UI never prompt. Queued work emits done only at final settlement.\n'

begin left
submit "$left" 'error fixture-prompt-canary'
mark_wait left 'provider error'
mark_wait left 'settled normal'
await_state "$left" idle
expect_count "$left" busy 1
expect_count "$left" done 0
begin left
submit "$left" 'cancel fixture-prompt-canary'
mark_wait left 'waiting for cancel'
await_state "$left" busy
client send-keys -t "$left" Escape
mark_wait left 'provider aborted'
mark_wait left 'settled aborted'
await_state "$left" idle
expect_count "$left" done 0
printf 'Provider failure and cancellation return to idle without done.\n'

replace_right() {
  local new=$1 old_id pid
  old_id=$(instance "$right")
  pid=$(client display-message -p -t "$right" '#{pane_pid}')
  kill -KILL "$pid" # Force a disconnect without an extension shutdown hook.
  wait_for gone "$pid" || fail 'Killed fixture Pi did not exit.'
  await_state "$right" gone
  [[ $(instance "$right") == "$old_id" ]] || fail 'Disconnect was attributed to the wrong instance.'
  respawn_right "$new"
  await_state "$right" idle
  [[ $(instance "$right") != "$old_id" ]] || fail 'Replacement Pi reused the prior source ID.'
}
replace_right replacement
ask replacement "$right"
await_state "$right" needs_permission
replace_right replacement-again
printf 'Same-pane replacement drops both prior done and prior waiting states.\n'

# Keep the extension configured, but make its receiver genuinely unavailable.
stop_receiver
rm -f -- "$signal_socket"
[[ ! -e $signal_socket ]] || fail 'Receiver socket still exists during the absent-receiver check.'
reset_trace replacement-again
ask replacement-again "$right"
client send-keys -t "$right" y
mark_wait replacement-again 'decision user_approved'
mark_wait replacement-again 'executed fixture_ask'
release replacement-again
mark_wait replacement-again 'settled normal'
client send-keys -t "$right" C-d
wait_for gone "${pids[-1]}" || fail 'Pi could not exit normally without its receiver.'
printf 'Pi can approve, execute, settle and exit without a receiver.\n'

# Stop the remaining publisher so the raw socket sees only the fresh fixture Pi.
client send-keys -t "$left" C-d
wait_for gone "${pids[0]}" || fail 'Left Pi could not exit normally.'
raw=$root/raw
"${clean[@]}" python3 - "$signal_socket" "$raw" <<'PY' &
import socket
import sys

# Inspect producer bytes before the Rust receiver can parse or discard anything.
with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
    listener.bind(sys.argv[1])
    listener.listen(1)
    connection, _ = listener.accept()
    with connection, open(sys.argv[2], 'wb', buffering=0) as output:
        while chunk := connection.recv(4096):
            output.write(chunk)
PY
receiver_pid=$!
wait_for test -S "$signal_socket" || fail 'Raw Unix socket did not start.'
respawn_right wire
wire_has() { grep -q $'\t'"$1"'$' "$raw" 2>/dev/null; }
wait_for wire_has idle || fail 'Publisher did not send startup idle to the raw Unix socket.'
ask wire "$right"
client send-keys -t "$right" y
mark_wait wire 'decision user_approved'
release wire
mark_wait wire 'settled normal'
wait_for wire_has done || fail 'Publisher did not send done to the raw Unix socket.'
client send-keys -t "$right" C-d
wait_for gone "$receiver_pid" || fail 'Raw socket did not reach EOF after Pi exited.'
wait "$receiver_pid"
receiver_pid=
"${clean[@]}" python3 - "$right" "$raw" "$left" "$signals" <<'PY'
import re
import sys
from pathlib import Path

raw = Path(sys.argv[2]).read_bytes()
rows = raw.splitlines(keepends=True)
pane = re.escape(sys.argv[1].encode())
pattern = pane + rb'\t([0-9a-f]{32})\t(idle|busy|needs_permission|done)\n'
frames = [re.fullmatch(pattern, row) for row in rows]
assert rows and all(frames), 'Publisher leaked extra fields, bytes, or non-opaque identifiers'
assert len({frame[1] for frame in frames}) == 1, 'Publisher changed instance mid-session'
assert {frame[2] for frame in frames} == {b'idle', b'busy', b'needs_permission', b'done'}, 'Missing wire states'
received = Path(sys.argv[4]).read_bytes().splitlines(keepends=True)
panes = b'(?:' + pane + b'|' + re.escape(sys.argv[3].encode()) + b')'
assert all(re.fullmatch(panes + rb'\t[0-9a-f]{32}\t(idle|busy|needs_permission|done|gone)\n', row)
           for row in received), 'Receiver output violated the TSV contract'
PY
printf 'Raw producer bytes and receiver output contain only pane ID, opaque instance ID, and state.\n'
printf '\nReceiver output (isolated run):\n'
"${clean[@]}" awk '1' "$signals"
