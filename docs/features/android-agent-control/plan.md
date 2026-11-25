# Android control of PC pi agents

Goal: Use one Android overview to manage tmux windows, select projects, and chat with existing or newly started pi agents while retaining PC keyboard control.

Build the everyday workflow first: one existing tmux session, like starting `tmux` on the PC and pressing `Ctrl+b c` to open another window. Ship a working vertical slice for review, then extend it in place. Check behavior in the running app first. Run a separate experiment only when an unresolved question blocks the next useful slice. Track skipped behavior in [deferred.md](deferred.md); keep unsupported actions out of the app rather than weakening safeguards.

Decisions and assumptions: See [decisions.md](decisions.md). The broader feature contracts are targets, not requirements for the first slice.

| Outcome | Depends on | Status |
|---|---|---|
| [Connect Android to the PC](connection.md) | — | Done |
| [See and open windows in one existing session](#see-and-open-windows-in-one-existing-session) | Connection | Ready |
| Chat with one already-running pi agent in that session | Single-session prototype | — |
| Complete [workspace management](workspaces.md) and [shared pi chat](shared-pi-chat.md) in follow-up slices | Feedback from the prototype | — |
| [Agent attention in the overview](agent-overview.md) | Shared pi chat | — |

Next: See and open windows in one existing session

Review: Single-session workspace reopened after the user reported that **Open shell** does not work on their Android device and PC. Resolve this before starting any follow-up slice.

Connection verification: 3 server tests, 12 domain tests plus the existing JVM example, 6 emulator tests, debug build, and lint pass. The user confirmed manual acceptance and requested completion. IPv6 routing remains unverified. See [connection-deployment.md](connection-deployment.md).

## See and open windows in one existing session

Depends on: [connection.md](connection.md)

### Outcome
See the windows in the PC's existing tmux session on Android and tap **Open shell** to create another window, like `Ctrl+b c`.

### Guarantees
- Show windows created on the PC, including those that predate Glowkin. Reflect new windows on refresh.
- **Open shell** opens a normal interactive shell at home in the listed session, without starting pi or sending input to an existing pane. Report failure if the target is unavailable rather than falling back to another session.
- If there is no session, show that one needs to be started on the PC. If there are multiple sessions, explain that this version supports one and do not choose a session to modify.
- Preserve the connection contract: no wider access, no automatic replay after uncertain delivery, and no impact on PC work when Android disconnects.

### Scope boundary
One existing session on the configured tmux server. Create and list only. Close windows and start or select sessions on the PC. Directory selection, remote recovery, and pi chat are follow-ups.

Keep tmux commands in the bridge adapter. Carry stable session and window IDs rather than names or indices as identity. Keep the existing Android connection contract; add only what this slice uses.

### Done when
On the PC, start tmux and create a window with `Ctrl+b c`. Verify Android lists it. Open a shell from Android and verify on the PC that it is a normal interactive window at home in the same session. Verify an unavailable target reports failure without opening a window elsewhere. Add focused coverage for the new behavior; defer the broader matrix in [deferred.md](deferred.md).

### Verification
Passed 7 bridge tests, 13 Android JVM tests, and 7 emulator tests. Rust formatting, Clippy, the Rust and Android debug builds, and Android lint passed. The emulator also listed and refreshed PC-created windows through the real bridge, then opened exactly one interactive shell at home in the same disposable tmux session. Private-socket bridge tests verified unavailable and stale targets never opened a window elsewhere.

### Review
Manual review failed and remains open: the user corrected their earlier acceptance and reported that **Open shell** does not work on their Android device and PC. No further symptoms or cause are known. The automated and disposable-session checks above still stand, but they did not reproduce the user's setup.

## Completed experiments

Reference evidence only. Neither experiment gates the single-session prototype.

## Probe tmux working-directory launch

Type: Experiment

**Question:** When passed `-c` as a subprocess argument, do `new-window` and `new-session` launch in the exact requested directory and reject a directory that disappeared after validation?

**Decision:** Use native `-c` directly only if both commands preserve the literal path and reject the missing path without creating a window or session. Otherwise, reject the direct approach and keep workspace implementation blocked until a safe alternative is established.

**Probe:** Use an isolated socket under a temporary directory, an empty tmux config, and no inherited `TMUX`. Pass a path containing a space and literal `#{session_name}`. Check the actual process working directory for each command. Repeat with the path removed after checking it exists but before issuing the command. For `new-session`, test startup with no server on the probe socket. Record the tmux version, command results, process working directories, and whether a window or session was created. Clean up the probe server and temporary files only.

**Done when:** The observations answer the question on the installed tmux version. No bridge or Android changes belong in this experiment.

**Prerequisite checks:** `/usr/bin/tmux` reported `tmux 3.8`. `/bin/sleep` was executable and Linux procfs was available. `list-sessions` confirmed no server on either fresh session-test socket before launch.

**Reproduction:**
1. Set `probe=$(mktemp -d /tmp/glowkin-tmux-cwd.XXXXXXXX)` and `cd "$probe"`. Set `path="$probe/project #{session_name}"` and run `mkdir -- "$path"`. Prefix every tmux invocation with `env -u TMUX -u TMUX_PANE tmux -S "$probe/<socket>.sock" -f /dev/null`, replacing `<socket>` with `window`, `session-valid`, or `session-missing` as indicated below.
2. On `window`, run `new-session -d -s seed /bin/sleep 120`, then `new-window -d -P -F '#{window_id} #{pane_pid}' -t seed: -n valid -c "$path" /bin/sleep 120`. On the fresh `session-valid` socket, run `new-session -d -P -F '#{session_id} #{pane_pid}' -s valid -c "$path" /bin/sleep 120`. Record exit status, output, and `readlink "/proc/<pane_pid>/cwd"` for each launch.
3. Run `test -d "$path" && rmdir -- "$path" && test ! -e "$path"`. Repeat both launch commands with `valid` replaced by `missing`, using the same `window` socket and the fresh `session-missing` socket. Compare `list-windows -t seed -F '#{window_id} #{window_name}'` before and after the missing-window launch. Run `list-sessions -F '#{session_id} #{session_name}'` after the missing-session launch. Record status, output, and cwd as above.

**Direct observations:**

| Command | Requested directory | Exit | Created | Pane process cwd |
|---|---|---:|---|---|
| `new-window` | Present | 0 | Window `@1` | `/home/lukas` |
| `new-session` | Present, fresh socket | 0 | Session `$0` | `/home/lukas` |
| `new-window` | Removed after validation | 0 | Window `@2`, confirmed by inventory | `/home/lukas` |
| `new-session` | Removed after validation, fresh socket | 0 | Session `$0`, confirmed by inventory | `/home/lukas` |

**Conclusion:** Reject direct native `-c`. Neither command preserved the existing literal path, and both reported success and created a pane in the fallback directory after removal. Under the original contract, this blocked workspace implementation and called for a separate launcher experiment.

**Follow-up:** The prototype opens at home and does not accept a path. When adding selected-directory entry, avoid direct native `-c` and verify the actual cwd. A plain shell may fall back to home with a pane warning; pi must not start there on directory-entry failure.

**Limitations:** This tests tmux 3.8 on this host with the specified path and a deterministic deletion between validation and launch. It does not establish the cause of the substitution, behavior on other versions, handling of unreadable directories, or behavior in other filesystem races.

**Cleanup:** Issued `kill-server` only on the three probe sockets. Confirmed no probe server was reachable and all five probe pane PIDs were gone. Removed the temporary directory and verified it was absent. No bridge or Android code changed and no probe artifacts remain.

## Probe stale tmux window closure

Type: Experiment

**Question:** Can a guarded tmux command reject a stale server lifetime or session/window pair at execution time, without a separate bridge-side check before `kill-window`?

**Decision:** Use a native guarded close only if the server checks its lifetime and the window's membership in the requested session before killing that exact window, with no intervening yield. Otherwise keep workspaces blocked until a safe approach is established.

**Required evidence:** Record the exact command and tmux version. Show that a current pair closes only its window. Show that the same request fails after the window moves to another session, and that an old request fails after restarting the server on the same socket with reused IDs. For both failures record the command status and inventory before and after to show the surviving targets were untouched. Explain from tmux command queue semantics why the guard and close do not yield between validation and mutation; a successful check in a separate tmux invocation is not sufficient.

**Prerequisites and safeguards:** Use the installed tmux, a private temporary socket, an empty config, and no inherited `TMUX` or `TMUX_PANE`. Use only disposable shell or `/bin/sleep` panes. Never target the user's tmux server. Stop only the probe server and remove its temporary files. Do not change bridge or Android code.

**Prerequisite checks:** `/usr/bin/tmux` reported `tmux 3.8` (package `3.8-1`). `/bin/sleep` was executable, procfs supplied UUIDs, and `list-sessions` found no server on the fresh socket.

**Reproduction:**
1. Set `probe=$(mktemp -d /tmp/glowkin-tmux-close.XXXXXXXX)`. Use these commands in Bash. The generation is a UUID; the request IDs are literal `$`/`@` followed by digits. The false branch is a probe-only way to return a nonzero status without mutating anything.

   ```bash
   t() { env -u TMUX -u TMUX_PANE /usr/bin/tmux -S "$probe/tmux.sock" -f /dev/null "$@"; }
   trap 'if test -S "$probe/tmux.sock"; then t kill-server >/dev/null 2>&1 || :; fi; rm -rf -- "$probe"' EXIT
   setup() {
     t new-session -d -s a -n keep-a /bin/sleep 120
     t new-session -d -s b -n keep-b /bin/sleep 120
     t new-window -d -t a: -n target /bin/sleep 120
   }
   inv() { t list-windows -a -F '#{session_id}:#{window_id} #{window_name}' | sort; }
   close_request() {
     local sid="$1" wid="$2" pair="$1:$2" guard
     guard='#{&&:#{==:#{@glowkin-probe-generation},'"$old_gen"'},#{&&:#{==:#{session_id},'"$sid"'},#{==:#{window_id},'"$wid"'}}}'
     t if-shell -F -t "$pair" "$guard" "kill-window -t '$pair'" 'show-options -gv glowkin-probe-stale-target'
   }
   setup
   IFS= read -r old_gen </proc/sys/kernel/random/uuid
   t set-option -g @glowkin-probe-generation "$old_gen"
   ```

2. Run `inv`, `close_request '$0' '@2'; echo "exit=$?"`, and `inv` again. Run `t new-window -d -t a: -n moved /bin/sleep 120` and verify in `inv` that the new window is `$0:@3`. Run `t move-window -s '$0:@3' -t b:`. Run `inv`, `close_request '$0' '@3'; echo "exit=$?"`, and `inv` again.
3. Record `t display-message -p '#{pid}'`. Run `t kill-server` and wait for `t list-sessions` to report no server. Run `setup` on the same socket and `t new-window -d -t a: -n replacement /bin/sleep 120`. Record the new PID. Run `inv`, `close_request '$0' '@3'; echo "exit=$?"`, and `inv` again. Set a fresh UUID with `IFS= read -r new_gen </proc/sys/kernel/random/uuid; t set-option -g @glowkin-probe-generation "$new_gen"`, then repeat the same inventory and old request without changing `old_gen`.

**Direct observations:** The server PID changed from `44462` to `44506`. In the table, `K` is the unchanged set `$0:@0 keep-a` and `$1:@1 keep-b`. Every rejection printed `invalid option: glowkin-probe-stale-target`.

| Request | Before | Exit | After |
|---|---|---:|---|
| Current `$0:@2` | K + `$0:@2 target` | 0 | K |
| `$0:@3` after move | K + `$1:@3 moved` | 1 | K + `$1:@3 moved` |
| Old `$0:@3` after restart, no generation | K + `$0:@2 target`, `$0:@3 replacement` | 1 | K + `$0:@2 target`, `$0:@3 replacement` |
| Same old request, fresh generation | K + `$0:@2 target`, `$0:@3 replacement` | 1 | K + `$0:@2 target`, `$0:@3 replacement` |

**Why it does not yield:** In tmux 3.8, [`if-shell -F`](https://github.com/tmux/tmux/blob/3.8/cmd-if-shell.c) evaluates the format synchronously and inserts only the selected branch immediately after itself in the same queue. It returns `CMD_RETURN_NORMAL` instead of starting a shell job or waiting. [`cmdq_next`](https://github.com/tmux/tmux/blob/3.8/cmd-queue.c) continues straight into the inserted command before [`server_loop`](https://github.com/tmux/tmux/blob/3.8/server.c) can service another queue or return to event processing. [`cmd-find`](https://github.com/tmux/tmux/blob/3.8/cmd-find.c) checks `session_has` for an explicit session and `@` window ID. [`kill-window`](https://github.com/tmux/tmux/blob/3.8/cmd-kill-window.c) resolves the same pair and synchronously kills that window. No separate invocation participates in the check or mutation.

**Conclusion:** Yes, for the tested tmux version. An in-server generation marker and exact session/window IDs work in a single `if-shell -F` guarded close. A missing or fresh marker rejects old requests even when the socket and IDs are reused.

**Limitations and follow-up:** The probe used an empty config and seeded the marker manually. Before shipping remote close, define how to initialize or adopt a fresh marker before issuing close identities, never restore an old marker into a replacement server, and map rejections to a domain error. The marker is not an authentication boundary against a local tmux user who can edit options. Recheck on supported versions and cover linked windows and final-window closure then. This does not block listing or opening windows.

**Cleanup:** Stopped only the private probe servers. Verified the main probe's pane PIDs were gone and removed both temporary socket directories, including a leftover socket from the command-metadata check. No bridge or Android code changed and no probe artifacts remain.
