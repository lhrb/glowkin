#!/usr/bin/env bash
# Run with Bash on Linux, with the installed Pi 1.6.0, tmux 3.8 and Node 22.23.5 on PATH.
set -euo pipefail
umask 077
src=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
pi=$(command -v pi)
root=$(mktemp -d /tmp/pi-settle.XXXXXXXX)
socket=
pane_pid=

client() { env -i PATH=/usr/bin:/bin tmux -N -S "$socket" "$@"; }
fail() { printf '%s\n' "$*" >&2; exit 1; }
wait_for() {
  local i
  for ((i = 0; i < 300; i++)); do
    "$@" && return
    sleep 0.05
  done
  return 1
}
server_gone() {
  # tmux can leave a stale socket inode. Only the private server is queried here.
  ! client show-options -s -v exit-empty >/dev/null 2>&1
}
pane_gone() {
  [[ -z $pane_pid ]] || ! kill -0 "$pane_pid" 2>/dev/null || [[ $(ps -o stat= -p "$pane_pid") == Z* ]]
}
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n $socket ]]; then
    client kill-server >/dev/null 2>&1 || true
    if ! wait_for server_gone || ! wait_for pane_gone; then
      printf 'Probe process or socket did not exit cleanly.\n' >&2
      status=1
    fi
  fi
  if rm -rf -- "$root" && [[ ! -e $root ]]; then
    (( status != 0 )) || printf 'Cleanup verified.\n' >&2
  else
    printf 'Probe directory cleanup failed.\n' >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp -- "$src"/settlement-{observer,fixture}.ts "$root/"
[[ $(env -i PATH=/usr/bin:/bin tmux -V) == 'tmux 3.8' ]] || fail 'Expected tmux 3.8.'
[[ $(env -i PATH=/usr/bin:/bin node --version) == v22.23.5 ]] || fail 'Expected Node 22.23.5.'

preflight_finished() { [[ -f $run/ready || -f $run/preflight-failed ]]; }
settled() { [[ -f $run/trace ]] && grep -q '^agent_settled ' "$run/trace"; }
for scenario in normal queued retry error length cancel continuation-error; do
  run=$root/$scenario
  mkdir -p "$run"/{cwd,home,config,data,state,cache,tmp,agent,sessions}
  printf '%s\n' '{"retry":{"enabled":true,"maxRetries":1,"baseDelayMs":50}}' > "$run/agent/settings.json"
  socket=$run/socket
  clean=(env -i PATH=/usr/bin:/bin SHELL=/bin/sh HOME="$run/home" TMPDIR="$run/tmp"
    XDG_CONFIG_HOME="$run/config" XDG_DATA_HOME="$run/data" XDG_STATE_HOME="$run/state" XDG_CACHE_HOME="$run/cache"
    PI_CODING_AGENT_DIR="$run/agent" PI_CODING_AGENT_SESSION_DIR="$run/sessions"
    PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 PI_TELEMETRY=0 PROBE_CASE="$scenario")
  if [[ $scenario == normal ]]; then
    [[ $(cd "$run/cwd" && "${clean[@]}" "$pi" --no-extensions --version) == 1.6.0 ]] || fail 'Expected Pi 1.6.0.'
  fi
  "${clean[@]}" tmux -S "$socket" -f /dev/null new-session -d -s probe -x 100 -y 30 -c "$run/cwd" \
    "$pi" --model settlement-fixture/fixture --no-session --no-tools --no-mcp \
    --no-extensions --no-skills --no-prompt-templates --no-themes --no-context-files \
    -e "$root/settlement-observer.ts" -e "$root/settlement-fixture.ts"
  pane_pid=$(client display-message -p -t '=probe:0.0' '#{pane_pid}')
  [[ -S $socket && $pane_pid =~ ^[0-9]+$ && $(client list-sessions -F '#{session_attached}') == 0 ]] \
    || fail "$scenario: private detached tmux preflight failed."
  wait_for preflight_finished || fail "$scenario: Pi preflight did not finish; no prompt sent."
  [[ -f $run/ready ]] || fail "$scenario: Pi preflight failed ($( < "$run/preflight-failed" )); no prompt sent."

  client send-keys -t '=probe:0.0' -l 'fixture'
  client send-keys -t '=probe:0.0' Enter
  case $scenario in
    queued)
      wait_for test -f "$run/waiting" || fail 'queued: fixture never held the first request.'
      client send-keys -t '=probe:0.0' -l 'follow-up'
      client send-keys -t '=probe:0.0' -H 1b 0d # Alt+Enter, not steering via plain Enter.
      ;;
    cancel)
      wait_for test -f "$run/waiting" || fail 'cancel: fixture never reached its abort wait.'
      client send-keys -t '=probe:0.0' Escape
      ;;
  esac
  if ! wait_for settled; then
    [[ ! -f $run/trace ]] || grep '.' "$run/trace"
    fail "$scenario: no settlement observed."
  fi
  client send-keys -t '=probe:0.0' C-d
  wait_for pane_gone && wait_for server_gone || fail "$scenario: Pi or tmux did not exit after Ctrl+D."
  socket=
  pane_pid=

  printf '\n[%s]\n' "$scenario"
  grep '.' "$run/trace"
  case $scenario in
    normal) expected_stops=stop ;;
    queued)
      expected_stops='stop stop'
      grep -q '^turn_end .* pending=true$' "$run/trace" || fail 'queued: no pending follow-up observed.'
      ;;
    retry) expected_stops='error stop' ;;
    error) expected_stops=error ;;
    length) expected_stops=length ;;
    cancel) expected_stops=aborted ;;
    continuation-error) expected_stops='stop error' ;;
  esac
  expected_done=false
  if [[ $scenario == normal || $scenario == queued || $scenario == retry ]]; then expected_done=true; fi
  expected_abort=false
  if [[ $scenario == cancel ]]; then expected_abort=true; fi
  stops=$(awk '/^turn_end / { sub(/^stop=/, "", $2); printf "%s%s", sep, $2; sep=" " }' "$run/trace")
  [[ $stops == "$expected_stops" ]] || fail "$scenario: unexpected provider stop sequence."
  [[ $(grep '^agent_settled ' "$run/trace") == "agent_settled aborted=$expected_abort idle=true done=$expected_done" ]] \
    || fail "$scenario: unexpected settlement."
done
