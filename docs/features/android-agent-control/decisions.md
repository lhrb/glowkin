# Decisions and assumptions

## Accepted product decisions
- Connect using a saved address and port over WLAN or Tailscale, without authentication. The requester accepts that any reachable peer can read conversations, instruct agents, and close windows. WLAN does not itself protect this authority; Tailscale policy and host/network controls remain deployment responsibilities. Public exposure is unsupported.
- Discover PC-created work across all sessions on the configured tmux server; do not limit discovery to app-created agents.
- Allow confirmed closure of the last window, even if that ends the final tmux session. Preserve remote recovery instead: the running bridge can create a fresh session with a shell from Android when no tmux server is running or no sessions remain. Do not create a replacement automatically or require PC interaction.
- Confirm other closures because they terminate every process in the window.
- Combine tmux windows and agent attention in one primary overview.
- Browse directories only; retain normal pi terminal use alongside Android.

## Integration finding and scope
A globally installed interactive pi extension can expose conversation events, history, prompt submission, stop, and lifecycle without replacing the TUI. Existing processes require a one-time reload after installation. The extension must reconnect independently when the server appears.

The installed pi API reports blocking UI prompt spans but exposes no generic response resolver in interactive mode. Structured dialog responses exist in RPC mode, which is not an attachment interface for an existing TUI. Therefore v1 detects these prompts and directs the user to the PC; it does not promise remote permission approval. Pi's built-in tool execution is not a new Glowkin approval workflow. Supporting remotely answerable dialogs later requires cooperating prompt owners or a pi API change.

Research: installed pi `docs/extensions.md`, `docs/rpc.md`, `docs/rpc-extension-ui.md`, and extension types. Revalidate against the targeted pi version during implementation.

## Planning defaults
- One PC, one configured tmux server under its OS account; other users and sockets are outside discovery.
- Without configured project-root policy, directory browsing follows the server account's access rights. No file mutation UI is introduced.
- Start pi in a new window to avoid injecting launch commands into an unknown active shell or agent.
- Android submissions during a run become follow-ups; explicit stop remains separate.
- Current conversation branch and current state are authoritative after reconnect; exact replay of every missed transient event is unnecessary.
- No additional durable chat archive or Android offline chat cache is required; existing pi session storage remains authoritative.
- Builder chooses transport, server language, rendering, reconnect timing, and routine UX. Document significant tradeoffs here.

## Connection implementation decisions
- Choose foreground read-only HTTP polling instead of a persistent channel. Reconnect reconciles snapshots; it never replays commands. Only a read begun after uncertain delivery can authorize a fresh explicit retry.
- The connection-only revision identifies a bridge lifetime, not agent state. No workspace or agent inventory exists yet.
- Use trusted-network cleartext HTTP with a loopback-only default bind. Non-loopback deployment requires explicit network access controls; public exposure is unsupported.
- Background/destruction stops polling and invalidates obsolete responses, without touching server or agent lifetimes. Foreground entry reconnects unless explicitly disconnected during that activity lifetime.

Deployment and remaining manual acceptance checks: [connection-deployment.md](connection-deployment.md).
