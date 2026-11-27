# Glowkin herd names for tmux

New unnamed windows, including the first window of a new session, get a
short name such as `Pip` or `Moss`. Names stay put when you start or exit
Pi. The roster is shared across sessions on one tmux server. If all names
are taken, tmux keeps its default naming.

From the repository root, install the Rust helper and tmux config:

```sh
cargo install --locked --path tmux-herd-names --root "$HOME/.local"
install -Dm644 tmux-herd-names/herd.tmux "$HOME/.local/share/glowkin-tmux/herd.tmux"
```

Add this line to the tmux config for each server you want to opt in:

```tmux
source-file ~/.local/share/glowkin-tmux/herd.tmux
```

Reload that server's config or start a new server, then press `Ctrl+b c`.
Existing windows and names passed with `new-window -n` or `new-session -n`
are left alone. You can rename any window with
`Ctrl+b ,`. The extension does not require Pi or Pi signals.

Verify on a private server with `bash tmux-herd-names/tests/verify.sh`.
