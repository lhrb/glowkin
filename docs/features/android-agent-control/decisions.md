# Durable decisions and constraints

## Connection and delivery

- [Trusted-network HTTP](connection-deployment.md#scope-and-security) uses a saved address and port, not application authentication. Keep the default bind loopback-only. Reachability grants shipped capabilities, not approval for future controls.
- Use foreground snapshot polling for authoritative current state. The opaque revision identifies the bridge lifetime, not an ordered revision or tmux/agent state. A valid read replaces the prior snapshot regardless of revision ordering. Extend the snapshot for inventory rather than inferring availability from connection status.
- Send a mutation once per explicit action and only when connected and reconciled. Treat ambiguous delivery as uncertain, not applied. Block further mutations until an authoritative read **begun after** the uncertainty succeeds. A failed or older read cannot unlock the gate. Never retain or replay the request; only a fresh action against the reconciled target can retry.
- The v1 snapshot's workspace field is optional for compatibility. Older bridges still connect without offering workspace controls. Preserve this contract when extending it.

## Workspace targeting

- Use one configured tmux server under the bridge OS account, not other users or sockets. Resolve the account's default socket (including `TMUX_TMPDIR`), not the socket inherited via `TMUX`. Keep tmux commands in the bridge adapter; use `-N` for current operations so they never start a server.
- Keep bridge and tmux lifetimes distinct. Use stable tmux IDs, not names or mutable indices. Keep session, window, and pane identities distinct; check membership and fail without retargeting. Treat labels and paths as data, not commands.
- Read session count and inventory in one tmux queue. Check the single-session count, exact session ID, and tmux PID/start time in the same queue as shell creation via `if-shell -F`, avoiding configuration writes. The generation is not authentication and assumes no PID reuse within the same tmux start-time second.
- Bypass `default-command` and pass the bridge account's HOME literally; refuse to start elsewhere. Shell startup files and tmux hooks still run.
- Keep mutation requests bounded and fixed-length. Reject browser origins and send no CORS headers. Bound tmux concurrency, time, and output. Android uses fixed-length streaming and disables redirects to avoid automatic mutation replay.

## Pane previews

- The requester accepted that any reachable peer can read eligible panes without Android or per-pane setup. Keep pane contents on the [separate read route](connection-deployment.md#v1-http-contract). POST and browser checks do not authorize the caller.
- Omit optional pane IDs rather than losing a previously listable inventory at either size limit.
- Check the full target and capture in one synchronous tmux queue. Refuse an effective `after-capture-pane` hook to avoid side effects without changing tmux configuration.
- Clear the preview across selection, lifecycle, or invalidation boundaries. Treat each tap as a distinct selection, even when the user returns to the same pane. Keep preview text in memory only. Secure the Android window before displaying pane text because the Recents-only API still showed preview pixels on the emulator. This also blocks screenshots and screen recording until the activity closes.

## Pane input

- `--enable-pane-input` is the sole server-side input enablement switch. The optional v1 `paneInputEnabled` field advertises it, not authorization. Prefer omitting the field (read-only Android) to displacing previously listable inventory or pane IDs at the size cap. See the [input contract and trust boundary](connection-deployment.md#pane-input-api).
- Input reserves an idle Android HTTP worker or does not send. Busy reads may require another explicit tap; an action never waits behind a read to be sent later. Open shell shares the mutation gate.
- Bind Android input to the selected pane and observed bridge revision. The revision is not sent as a new v1 target field; the server checks the existing exact tmux identities and input eligibility. An unobserved restart is not detectable by the client revision check.
- Keep the worker and mutation gate across Android activity recreation so rotation cannot start a competing read or forget uncertainty. Do not restore the selection, preview, or draft. Process death does not preserve pending state or replay input.
- Pass input to tmux over stdin, not process arguments. Check eligibility and inject in one synchronous queue without changing tmux configuration or invoking a send-keys hook.

## Constraints for optional work

These constraints apply if a direction is approved, not as authority for the current workflow.

- **Workspace:** Discover existing PC work, not just app-created windows. Android selection must not change PC focus. Destructive close requires explicit confirmation that every process in the window will terminate, even for the final window. Verify stale and linked targets and final-window behavior before shipping close. Recovery from no server or no sessions must be explicit, never automatic. It must work without PC interaction or a bridge restart and report actual shell startup failure. No direct session deletion or pane-layout management.
- **Directories and launch:** Browse directories, including parents, under the bridge account's access rights unless a project-root policy is configured. Do not add file viewing or mutation. Verify literal directory entry before claiming success. A plain shell may fall back to home with a pane warning; Pi must not start there if entry fails. Start Pi in a fresh window rather than injecting a launch command into an existing pane. See [tmux probe findings](tmux-findings.md).
- **Structured Pi chat:** Preserve independent interactive Pi use and unsent PC editor contents. An installed extension can attach to a normal TUI after a one-time reload and must reconnect independently, including late bridge startup. Do not replace the TUI with RPC. Keep agent and session/window/pane identities distinct; lost connectivity or agent exit disables remote submission rather than choosing a replacement. Inputs share the same conversation without duplicates. Prompts during work queue as follow-ups; stop is separate and delivery does not prove cancellation. Reconcile the current branch and state on reload, reconnect, or session change; do not replay commands or merge unrelated agents. Pi session storage stays authoritative; no extra durable archive or offline cache.
- **Attention:** State is per agent, including multiple agents in one window. Distinguish idle, working, needs input, done, failed, and unavailable/unknown where observable. A turn ending is not completion while follow-ups, retries, or compaction remain. Newly observed idle is not completion. New work supersedes the previous result; prompt resolution clears attention. Reconcile after reconnect and show unobserved state as stale/unknown. No background notifications or inventory of unbridged internal subagents.
- **Pi dialogs:** Installed interactive extension APIs exposed blocking prompt spans but no generic response resolver. Mark detectable blockers as needing input on the PC. RPC dialog responses cannot attach to an existing TUI. Do not promise generic remote approval or turn Pi tool execution into a Glowkin approval workflow. Revalidate against the targeted Pi version (`docs/extensions.md`, `docs/rpc.md`, `docs/rpc-extension-ui.md`, extension types); remote answers would need cooperating prompt owners or an API change.
