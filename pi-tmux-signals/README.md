# Inspect Pi signals in tmux

This standalone receiver prints live Pi state. It does not answer prompts, change tmux styling, or use the Android bridge. Verified versions: Pi 1.6.0 and `@gotgenes/pi-permission-system` 42.2.1.

From the repository root, start the receiver in one terminal:

```sh
signal_dir=$(mktemp -d)
printf 'Socket: %s/signals.sock\n' "$signal_dir"
cargo run --manifest-path pi-tmux-signals/Cargo.toml -- "$signal_dir/signals.sock"
```

Start one interactive Pi per pane on the **same tmux server**. Substitute the printed socket path and the absolute path to this checkout. Keep your usual permission extension enabled.

```sh
GLOWKIN_PI_SIGNALS_SOCKET=/absolute/private-directory/signals.sock \
  pi -e /absolute/path/to/glowkin/pi-tmux-signals/extension.ts
```

Each output line has three tab-separated fields: tmux pane ID, opaque Pi instance ID, and state. `idle` means waiting for work; `busy` supersedes the previous state; `needs_permission` means the permission extension is showing an approval prompt; `done` means Pi settled normally, not that the task succeeded; `gone` means the current source disconnected. Answer or dismiss prompts in their original pane.

Delivery is best effort. A reconnect sends the current state, not missed transitions. The receiver stores no history.

Stop the receiver with Ctrl+C. After it exits, remove its dedicated socket and directory with `rm -f -- "$signal_dir/signals.sock"` and `rmdir -- "$signal_dir"`.
