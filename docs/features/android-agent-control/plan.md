# Android control of PC pi agents

Goal: Use one Android overview to manage tmux windows, select projects, and chat with existing or newly started pi agents while retaining PC keyboard control.

Decisions and assumptions: See [decisions.md](decisions.md); trusted-network address-only access, shared interactive pi sessions, directory selection only, and remote recovery when no tmux sessions remain.
Open questions: None for this scope; arbitrary terminal-dialog responses remain excluded.

| Order | Feature | Depends on | Status |
|---|---|---|---|
| 1 | [connection.md](connection.md) | — | Done |
| 2 | [workspaces.md](workspaces.md) | connection.md | Ready |
| 3 | [shared-pi-chat.md](shared-pi-chat.md) | workspaces.md | Blocked |
| 4 | [agent-overview.md](agent-overview.md) | shared-pi-chat.md | Blocked |

Next: `workspaces.md`.

Connection verification: 3 server tests, 12 domain tests plus the existing JVM example, 6 emulator tests, debug build, and lint pass. The user confirmed manual acceptance and requested completion. IPv6 routing remains unverified. See [connection-deployment.md](connection-deployment.md).
