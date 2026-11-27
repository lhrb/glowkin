#!/usr/bin/env bash
# Exercise tmux view hooks and unread transitions with controlled signal frames.
set -euo pipefail
product=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
tmp=$(mktemp -d)
cleanup() {
  tmux -N -S "$tmp/view" kill-server >/dev/null 2>&1 || true
  tmux -N -S "$tmp/socket" kill-server >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup EXIT
cargo build --quiet --locked --manifest-path "$product/Cargo.toml" --target-dir "$tmp/target"
python3 - "$tmp" "$product" <<'PY'
from contextlib import ExitStack
from pathlib import Path
import os
import shlex
import signal
import socket
import subprocess
import sys
import time

root, product = map(Path, sys.argv[1:])
os.environ.update(TERM='xterm-256color', LANG='C.UTF-8')
sock = root / 'socket'
endpoint = root / 'signals'

def tmux(*args, server=sock):
    return subprocess.check_output(['tmux', '-S', str(server), *args], text=True).strip()

def until(check, description):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.05)
    raise AssertionError(description)

def suffix():
    return tmux('display-message', '-p', '-t', 'work:observed',
                '#{E:@glowkin-pi-chooser-suffix}').replace('#[default]', '').strip()

def expect(text):
    try:
        until(lambda: suffix() == text, f'expected {text!r} on observed')
    except AssertionError as err:
        raise AssertionError(f'{err}, got {suffix()!r}') from None

def attach(session):
    command = shlex.join(['tmux', '-N', '-S', str(sock), 'attach', '-t', session])
    tmux('-f', '/dev/null', 'new-session', '-d', command, server=root / 'view')
    until(lambda: tmux('list-clients', '-F', '#{session_name}') == session, f'attach to {session}')

tmux('-f', '/dev/null', 'new-session', '-d', '-s', 'work', '-n', 'observed', 'sleep 300')
tmux('source-file', str(product / 'chooser.tmux'))
left = tmux('display-message', '-p', '-t', 'work:observed', '#{pane_id}')
right = tmux('split-window', '-d', '-P', '-F', '#{pane_id}', '-t', left, 'sleep 300')
tmux('new-window', '-d', '-t', 'work:', '-n', 'plain', 'sleep 300')
tmux('new-session', '-d', '-s', 'other', '-n', 'elsewhere', 'sleep 300')

with ExitStack() as stack:
    received = root / 'received'
    log = stack.enter_context(received.open('w'))
    receiver = stack.enter_context(subprocess.Popen([
        root / 'target/debug/pi-tmux-signals', endpoint, '--tmux-socket', sock,
    ], stdout=log))
    def stop_receiver():
        if receiver.poll() is None:
            receiver.terminate()
            receiver.send_signal(signal.SIGCONT)
            receiver.wait(timeout=5)
    stack.callback(stop_receiver)
    until(endpoint.is_socket, 'receiver did not bind')

    def agent(pane, instance):
        stream = stack.enter_context(socket.socket(socket.AF_UNIX))
        stream.connect(str(endpoint))
        return stream, f'{pane}\t{instance * 32}\t'.encode()

    def send(agent, state):
        stream, prefix = agent
        stream.sendall(prefix + state.encode() + b'\n')

    a, b = agent(left, 'a'), agent(right, 'b')
    send(a, 'idle')
    expect('[idle]')
    send(a, 'busy')
    expect('[busy]')
    send(a, 'done')
    expect('● [done]')  # A detached session's current window is not a view.
    tmux('select-window', '-t', 'work:plain', ';', 'select-window', '-t', 'work:observed')
    expect('● [done]')
    with subprocess.Popen(['tmux', '-N', '-S', sock, '-C', 'attach', '-t', 'work'],
                          stdin=subprocess.PIPE, stdout=subprocess.DEVNULL) as control:
        until(lambda: tmux('list-clients', '-F', '#{client_control_mode}') == '1',
              'control client did not attach')
        expect('● [done]')  # A control client is not a terminal view.
        send(a, 'needs_permission')
        expect('● [permission]')
        send(a, 'done')
        expect('● [done]')
        control.stdin.close()
    attach('other')
    expect('● [done]')  # Another attached session is not a view of this window.
    tmux('switch-client', '-t', 'work')
    expect('[done]')
    send(a, 'needs_input')
    expect('[needs input]')
    send(a, 'needs_permission')
    expect('[permission]')
    send(a, 'done')
    expect('[done]')
    tmux('select-window', '-t', 'work:plain')
    done_frame = a[1] + b'done\n'
    prior_done = received.read_bytes().count(done_frame)
    send(a, 'done')
    send(a, 'done')
    until(lambda: received.read_bytes().count(done_frame) == prior_done + 2,
          'receiver did not read duplicates')
    expect('[done]')  # A same-state re-report does not create a new dot.

    replacement = agent(left, 'c')
    send(replacement, 'done')
    expect('● [done]')  # A distinct instance must not inherit the old one's seen state.
    a[0].close()
    send(replacement, 'busy')
    expect('[busy]')  # New work supersedes the unread completion.
    send(replacement, 'needs_permission')
    expect('● [permission]')
    send(b, 'needs_input')
    send(replacement, 'busy')
    expect('● [needs input]')  # Resolving one prompt does not clear its sibling.
    send(b, 'idle')
    expect('[busy]')  # Resolved input leaves no stale dot.
    send(replacement, 'needs_permission')
    expect('● [permission]')
    replacement[0].close()
    expect('[idle]')  # Disconnect clears just this source.

    send(b, 'done')
    expect('● [done]')
    receiver.send_signal(signal.SIGSTOP)
    tmux('select-window', '-t', 'work:observed', ';', 'select-window', '-t', 'work:plain')
    expect('[done]')  # A short visit is acknowledged even when the receiver cannot poll.
    receiver.send_signal(signal.SIGCONT)
    send(b, 'needs_input')
    expect('● [needs input]')
    tmux('kill-server', server=root / 'view')
    until(lambda: tmux('list-clients') == '', 'viewer did not detach')
    tmux('select-window', '-t', 'work:observed')
    expect('● [needs input]')
    attach('work')
    expect('[needs input]')  # Attach acknowledges without dismissing the prompt.
    tmux('select-window', '-t', 'work:plain')
    send(b, 'needs_permission')
    expect('● [permission]')
    receiver.send_signal(signal.SIGSTOP)
    expect('')  # Existing lease hides both the dot and the label.
    receiver.send_signal(signal.SIGCONT)
    expect('[permission]')  # Renewal restores the label but not the expired dot.

print('Unread transitions, per-agent supersession, detached/current windows, attach, switch-client, fast visits, disconnect, and expiry passed.')
PY
