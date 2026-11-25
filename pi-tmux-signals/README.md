# Inspect Pi signals in tmux

This standalone receiver prints live Pi state and can show it in the tmux window chooser. It does not answer prompts or use the Android bridge. Verified versions: Pi 1.6.0, tmux 3.8, and `@gotgenes/pi-permission-system` 42.2.1.

Use your existing tmux and Pi installations. No tmux extension is needed. From the repository root, start the receiver in one terminal:

```sh
signal_dir=$(mktemp -d)
printf 'Socket: %s/signals.sock\n' "$signal_dir"
cargo run --manifest-path pi-tmux-signals/Cargo.toml -- "$signal_dir/signals.sock"
```

Start a new interactive Pi in each pane you want to observe on the **same tmux server**. An already-running Pi won’t connect automatically. Replace `/absolute/private-directory/signals.sock` with the actual path printed after `Socket:`. Replace `/absolute/path/to/glowkin` with this checkout’s absolute path. Keep your usual permission extension enabled.

```sh
GLOWKIN_PI_SIGNALS_SOCKET=/absolute/private-directory/signals.sock \
  pi -e /absolute/path/to/glowkin/pi-tmux-signals/extension.ts
```

Expect an `idle` line when Pi starts. Each line has three tab-separated fields: tmux pane ID, opaque Pi instance ID, and state. Signals never carry prompt text, commands, paths, or approval decisions. `idle` means waiting for work; `busy` supersedes the previous state; `needs_permission` means the permission extension is showing an approval prompt; `done` means Pi settled normally, not that the task succeeded; `gone` means the current source disconnected. Answer or dismiss prompts in their original pane.

`done` requires both final settlement and a normal provider stop. An earlier settlement boundary can still lead to more work, and Pi labels a provider `length` stop `completed`.

Delivery is best effort. A reconnect sends the current state, not missed transitions. The receiver stores no history.

## Enable the window chooser suffix

From the repository root **inside the tmux server you want to observe**, source the chooser binding and start the receiver with that server's socket. Use this receiver command instead of the raw-only command above. Keep one chooser receiver per tmux server.

```sh
tmux_socket=$(tmux display-message -p '#{socket_path}')
tmux -S "$tmux_socket" source-file "$PWD/pi-tmux-signals/chooser.tmux"
signal_dir=$(mktemp -d)
printf 'Socket: %s/signals.sock\n' "$signal_dir"
cargo run --manifest-path pi-tmux-signals/Cargo.toml -- \
  "$signal_dir/signals.sock" --tmux-socket "$tmux_socket"
```

Start Pi in the panes as above. Press `Ctrl+b w` to see one suffix per observed window: `[permission]` beats `[busy]`, then `[done]`, then `[idle]`. Windows without an observed Pi have no suffix. Names and navigation stay unchanged. Answer prompts in the original pane.

The binding replaces `w` in tmux's prefix table. Restore a custom binding from your config, or restore the stock binding with `tmux bind-key -T prefix w choose-tree -Zw`. If the receiver stops, its snapshot expires after six seconds and the next chooser redraw hides it.

Stop the receiver with Ctrl+C. After it exits, remove its dedicated socket and directory with `rm -f -- "$signal_dir/signals.sock"` and `rmdir -- "$signal_dir"`.

## Verification

From the repository root, run:

```sh
bash pi-tmux-signals/tests/verify-capture.sh
```

The smoke test uses private tmux servers and exercises the shipped sender, receiver, permission UI, and chooser. It requires Linux, Bash, Rust/Cargo, and Python 3. It was verified with Pi 1.6.0, tmux 3.8, Node 22, and `@gotgenes/pi-permission-system` 42.2.1. Set `PI_PERMISSION_PACKAGE` to the installed package directory if it is not under `~/.pi/agent/npm/node_modules/@gotgenes/`.
