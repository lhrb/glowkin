# Android control of PC pi agents

Goal: Use one Android overview to manage tmux windows, select projects, and chat with existing or newly started pi agents while retaining PC keyboard control.

Decisions and assumptions: See [decisions.md](decisions.md); trusted-network address-only access, shared interactive pi sessions, directory selection only, and remote recovery when no tmux sessions remain.
Open questions: None for the workspace slice. Verify selected-directory entry and shell fallback during implementation.

| Order | Feature | Depends on | Status |
|---|---|---|---|
| 1 | [connection.md](connection.md) | — | Done |
| 2 | [Probe tmux working-directory launch](#probe-tmux-working-directory-launch) | — | Done |
| 3 | [workspaces.md](workspaces.md) | connection.md | Ready |
| 4 | [shared-pi-chat.md](shared-pi-chat.md) | workspaces.md | Blocked |
| 5 | [agent-overview.md](agent-overview.md) | shared-pi-chat.md | Blocked |

Next: [workspaces.md](workspaces.md)
Review: Probe tmux working-directory launch: pending

Connection verification: 3 server tests, 12 domain tests plus the existing JVM example, 6 emulator tests, debug build, and lint pass. The user confirmed manual acceptance and requested completion. IPv6 routing remains unverified. See [connection-deployment.md](connection-deployment.md).

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

**Follow-up:** The workspace contract now permits the normal shell to open at home with a warning when directory entry fails. Direct native `-c` remains rejected for selected-directory entry. Verify an in-pane directory change as part of the workspace slice; the original fail-without-creation criterion no longer gates normal shell creation.

**Limitations:** This tests tmux 3.8 on this host with the specified path and a deterministic deletion between validation and launch. It does not establish the cause of the substitution, behavior on other versions, handling of unreadable directories, or behavior in other filesystem races.

**Cleanup:** Issued `kill-server` only on the three probe sockets. Confirmed no probe server was reachable and all five probe pane PIDs were gone. Removed the temporary directory and verified it was absent. No bridge or Android code changed and no probe artifacts remain.
