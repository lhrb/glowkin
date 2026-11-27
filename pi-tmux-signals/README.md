# Pi state in the tmux window chooser

See which tmux windows have a running Pi or need your attention. Status is local and read-only; answer prompts in the original pane.

## Install and use

On Linux with Cargo, tmux, and Pi, run `./pi-tmux-signals/install.sh` from the repository root.

The installer builds the release receiver, installs it to `~/.local/bin/pi-tmux-signals`, and creates the private `~/.local/state/glowkin-pi/work/` directory. It stages the configs and extensions in `~/.local/share/glowkin-pi/` and runs `pi install extension.ts` and `pi install permission-system.ts` there. It does not edit your tmux config.

Add this line to `~/.tmux.conf` (or the config for the server you want). Replace any previous Pi signals startup block:

```tmux
source-file ~/.local/share/glowkin-pi/startup.tmux
```

Opt in only one tmux server: every config that sources this file uses the same default socket. Start that server fresh to load the binding and start the receiver automatically.

Run plain `pi` in a pane and press `Ctrl+b w`. Each window shows its highest-priority state: `[permission]`, `[needs input]`, `[busy]`, `[done]` (finished normally, not necessarily successful), or `[idle]`. Windows without an observed Pi have no label.

A `●` beside the label means an agent finished or started waiting when that window was not current on an attached terminal client. Opening the chooser or previewing a row keeps the dot. Selecting the window clears it without changing the label or answering a prompt. Starting new work, resolving a prompt, or disconnecting clears that agent’s old dot, not another agent’s. If the receiver stops, dots and labels expire within six seconds of its last update. Reopen an already open chooser to see the expiry.

The permission adapter observes `@gotgenes/pi-permission-system` only if you already use it; the installer does not install that package. Remove any old manually loaded copies of the extensions to avoid duplicates.

## Verify

From the repository root, run `bash pi-tmux-signals/tests/verify-startup.sh` for the offline Pi chooser workflow. Run `bash pi-tmux-signals/tests/verify-unread.sh` for controlled unread transitions, short visits, and lease expiry. Both use private tmux servers and require Python 3.

To also test permission prompts, run `bash pi-tmux-signals/tests/verify-capture.sh` with the permission package installed. Set `PI_PERMISSION_PACKAGE` to the installed package directory if you use a custom location.
