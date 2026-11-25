# Optional Pi status providers and tmux startup

Goal: Install Glowkin once, run ordinary Pi in tmux, and keep the choice of permission system or sandbox independent of status reporting.

Decisions and assumptions: Status is local and read-only. No permission system is required or installed by Glowkin. Preserve the existing permission status for users who opt into its adapter. Enable tmux integration explicitly.
Open questions: None for the next slice.

| Outcome | Depends on | Status |
|---|---|---|
| Basic status works without a permission extension; optional providers can report attention | — | Done |
| One-time receiver install and opt-in startup make chooser status work with ordinary Pi | Optional status providers | Done |

Next: None
Review: Install the standalone receiver once and start it automatically: pending

### Review brief: Receiver install and startup
- **Your call:** No input needed.
- **What happened:** Opted-in tmux servers now start the installed receiver. Plain Pi discovers it through its own server without flags, environment setup, or a permission provider.
- **See it:** Follow the [one-time setup](../../../pi-tmux-signals/README.md#one-time-setup-for-the-window-chooser). Start tmux, run `pi`, and press `Ctrl+b w`. On both default and non-default servers, the smoke test saw `[idle] → [busy] → [done]` during an offline run and again after restart.

## Install the standalone receiver once and start it automatically

Depends on: Optional status providers (Done).

### Outcome
After one opt-in build, install, and setup, starting the configured tmux server starts the installed receiver and enables chooser status for ordinary `pi` launches.

### Guarantees
- tmux startup or an equivalent mechanism starts the installed binary without manual tmux socket lookup, `source-file`, `mktemp`, or `cargo run`. Pi uses its normal extension loading and needs no per-launch flags or environment setup.
- Pi and the receiver use the same stable, private Unix signal socket across launches. The receiver publishes chooser status through the opted-in tmux server's own socket, including for a non-default server. It does not mix status across servers or replace an unrelated socket.
- Without opt-in, startup and chooser behavior stay unchanged. Status stays local and read-only. Basic status still needs no provider, and permission and sandbox integrations remain optional. The signal socket is not the Android bridge; no Android changes.

### Done when
Document the one-time setup. Verify from a fresh tmux start that plain `pi` shows `[idle] → [busy] → [done]` in the chooser with no provider installed. Restart the configured tmux server and repeat without setup, using the same signal socket path. Repeat with a non-default tmux socket alongside an unconfigured server and verify only the opted-in server starts a receiver and shows status. Re-run the existing status, optional-provider, and disconnect checks.

### Verification
- [Startup smoke test](../../../pi-tmux-signals/tests/verify-startup.sh) passed on tmux 3.8, Pi 1.6.0, and Node 22.23.5. It installed once into an isolated home and launched plain `pi` with an offline model and no permission provider. It confirmed the same private socket and run transitions through fresh starts and restarts. A nested unconfigured server with the same pane ID kept its stock chooser and did not affect the opted-in server. The test also verified disconnect cleanup, preserved an unrelated live socket, and rejected a different listener behind an old registration.
- [Capture regressions](../../../pi-tmux-signals/tests/verify-capture.sh) passed for run status, optional providers including the real permission adapter, chooser behavior, wire bounds, and disconnects. Strict TypeScript, Bash syntax, Rust format, Clippy, unit tests, and debug and release builds passed. The startup test first failed because the old receiver did not support `--serve`. Removing the socket identity check made the stale-registration test fail as expected.
- The run model was offline. ShellCheck was unavailable. A separate hard-kill check confirmed startup leaves the stale socket untouched and resumes after the known stale entry is removed.

### Handoff
- Each opted-in server needs a distinct private socket path. Pi reads a registration from its own tmux server rather than inheriting a path from the pane environment. The registration identifies the actual socket so a different listener cannot inherit it.
- The receiver follows the tmux server lifetime, not a respawn supervisor. It removes its own socket on normal shutdown and never reclaims an occupied path on startup. This favors preserving unrelated entries over automatic recovery from a hard receiver kill. See the [setup and recovery instructions](../../../pi-tmux-signals/README.md#one-time-setup-for-the-window-chooser).

## See Pi status with or without a permission extension

Depends on: None

### Outcome
See built-in Pi run status in the chooser and optionally see attention from a permission extension, sandbox, or other Pi integration.

### Guarantees
- With no adapter, Pi still reports `idle`, `busy`, and `done` with their [existing semantics](../../../pi-tmux-signals/README.md).
- A provider reports its current attention through one namespaced Pi event with a stable provider key and one of `clear`, `needs_input`, or `needs_permission`. Providers aggregate their own prompts. Malformed events are ignored, and clearing one provider does not clear another. Attention takes priority over run status, with permission winning ties; clearing the last provider reveals the current run state. Attention from a new prompt does not revive an old `done` label.
- The separately loaded `pi-permission-system` adapter translates prompt and matching decision events. The core never imports, installs, or listens to vendor-specific events. A sandbox with no Pi integration gets run status only.
- Keep existing wire values and chooser labels; add `[needs input]` for generic attention. Do not forward provider keys, prompt IDs, commands, or text over the [local signal stream](../../../pi-tmux-signals/README.md). Preserve disconnect and stale-state handling. Status cannot answer prompts or change enforcement.

### Scope boundary
One generic attention category plus the existing permission category. No arbitrary labels, external plugin loader, automatic prompt detection, receiver autostart, or Android changes.

### Done when
The chooser shows run status in a Pi session with no permission package installed. An independent fixture shows and clears generic attention without clearing another provider's active prompt. The optional real adapter preserves the permission flow and label. Verify the wire contains only bounded status data and that disconnect hides stale state as before.

### Verification
- The [smoke test](../../../pi-tmux-signals/tests/verify-capture.sh) passed twice with Pi 1.6.0, tmux 3.8, Node 22.23.5, and `@gotgenes/pi-permission-system` 42.2.1. It exercised the shipped sender, receiver, and chooser with no permission package, an independent provider, and the real adapter. It verified priority, independent clearing, malformed event rejection, bounded raw frames, disconnect cleanup, and the existing run and permission regressions.
- Strict TypeScript checks, Bash syntax, Rust format, Clippy, tests, and build passed. Test-first checks failed for the missing `needs_input` parser and sender behavior, and for stale `done` after a same-category report before the fix.
- The model was an offline fixture. Overlapping real vendor prompts and live `/reload` were not exercised.

### Handoff
- A non-clear report means a provider has new attention, even if its category is unchanged. It invalidates an earlier `done`, so providers should report new prompts rather than send periodic repeats. See the [provider contract](../../../pi-tmux-signals/README.md#optional-status-providers).
- For generic attention, use the sender, receiver, and chooser binding from the same checkout. [Manual mode](../../../pi-tmux-signals/README.md#enable-the-window-chooser-suffix) remains available for diagnostics.
