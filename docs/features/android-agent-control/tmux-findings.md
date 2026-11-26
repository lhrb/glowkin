# tmux probe findings

Both tmux 3.8 probes used disposable private sockets. Neither gates the current workflow. See the [optional roadmap](plan.md#optional-roadmap).

## Probe tmux working-directory launch

**Finding:** Do not use native `-c` directly for literal selected paths. On tmux 3.8, both `new-window` and `new-session` launched at home rather than in the requested path and falsely succeeded after the path disappeared.

**Prerequisites:** `/usr/bin/tmux` (tested with 3.8), `/bin/sleep`, and Linux procfs. Use an empty config and fresh private sockets.

**Reproduction:**
1. Set `probe=$(mktemp -d /tmp/glowkin-tmux-cwd.XXXXXXXX)` and `cd "$probe"`. Set `path="$probe/project #{session_name}"` and run `mkdir -- "$path"`. Prefix every tmux invocation with `env -u TMUX -u TMUX_PANE tmux -S "$probe/<socket>.sock" -f /dev/null`, replacing `<socket>` with `window`, `session-valid`, or `session-missing` as indicated below.
2. On `window`, run `new-session -d -s seed /bin/sleep 120`, then `new-window -d -P -F '#{window_id} #{pane_pid}' -t seed: -n valid -c "$path" /bin/sleep 120`. On the fresh `session-valid` socket, confirm `list-sessions` finds no server, then run `new-session -d -P -F '#{session_id} #{pane_pid}' -s valid -c "$path" /bin/sleep 120`. Record exit status, output, and `readlink "/proc/<pane_pid>/cwd"` for each launch.
3. Record `list-windows -t seed -F '#{window_id} #{window_name}'` on `window`. Confirm `list-sessions` on `session-missing` finds no server. Run `test -d "$path" && rmdir -- "$path" && test ! -e "$path"`. Repeat both launch commands with `valid` replaced by `missing`, using the same `window` socket and the fresh `session-missing` socket. Compare `list-windows -t seed -F '#{window_id} #{window_name}'` before and after the missing-window launch. Run `list-sessions -F '#{session_id} #{session_name}'` after the missing-session launch. Record status, output, and cwd as above.

**Direct observations:**

| Command | Requested directory | Exit | Created | Pane process cwd |
|---|---|---:|---|---|
| `new-window` | Present | 0 | Window `@1` | `/home/lukas` |
| `new-session` | Present, fresh socket | 0 | Session `$0` | `/home/lukas` |
| `new-window` | Removed after validation | 0 | Window `@2`, confirmed by inventory | `/home/lukas` |
| `new-session` | Removed after validation, fresh socket | 0 | Session `$0`, confirmed by inventory | `/home/lukas` |

**Follow-up:** Test a separate launcher that verifies the actual cwd. Preserve the [shell and Pi fallback rules](decisions.md#constraints-for-optional-work).

**Limits:** One host, version, path, and deterministic deletion between validation and launch. The cause, other versions, unreadable paths, and other races remain unverified.

**Cleanup:** Run `kill-server` only on the three probe sockets, check that the probe servers and pane PIDs are gone, then leave and remove the temporary directory.

## Probe stale tmux window closure

**Finding:** A single `if-shell -F` guard can enforce the tmux lifetime and exact session/window membership before closing a window on tmux 3.8 (package `3.8-1`). A separate check before `kill-window` would not cover the race. This is evidence only, not approval to ship close.

**Prerequisites:** `/usr/bin/tmux`, `/bin/sleep`, Linux procfs UUIDs, an empty config, and a fresh private socket. Use disposable panes only. The Bash trap stops only the probe server and removes its temporary directory.

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
   # Verify no server is running on this fresh socket before setup.
   t list-sessions
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

**Why it does not yield:** In tmux 3.8, [`if-shell -F`](https://github.com/tmux/tmux/blob/3.8/cmd-if-shell.c) inserts only the selected branch next in the same queue and returns `CMD_RETURN_NORMAL` without waiting. [`cmdq_next`](https://github.com/tmux/tmux/blob/3.8/cmd-queue.c) runs that branch before [`server_loop`](https://github.com/tmux/tmux/blob/3.8/server.c) can service another queue or event. [`cmd-find`](https://github.com/tmux/tmux/blob/3.8/cmd-find.c) checks `session_has` for the explicit session/window pair. [`kill-window`](https://github.com/tmux/tmux/blob/3.8/cmd-kill-window.c) resolves that same pair and closes it synchronously.

**Limitations and follow-up:** The probe seeded the marker manually. If remote close is approved, define how to initialize or adopt a fresh marker before issuing close identities. Never restore an old marker into a replacement server. Map rejections to a domain error. The marker is not authentication against a local tmux user who can edit options. Recheck on supported versions and cover linked windows and final-window closure.
