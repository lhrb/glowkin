# Connect to a personal PC bridge

Depends on: none

## Outcome
Connect Android to one PC server using only a saved address and port over WLAN or Tailscale.

## Guarantees
- A valid reachable address and port connect without login, pairing, or a token.
- Save the endpoint across app restarts and allow replacing it.
- Distinguish connecting, connected, and disconnected states; invalid endpoints and unreachable servers produce actionable errors without losing the saved endpoint.
- Lost connections never imply that agents stopped or commands succeeded.
- Reconnection restores authoritative current state; stale displayed data remains identifiable until refreshed.
- Do not automatically replay state-changing commands after uncertain delivery; report uncertain outcomes and reconcile state before retrying.
- Closing Android or losing its connection does not terminate tmux or pi.
- Address-only access is explicitly documented as unauthenticated PC control for trusted networks, not public deployment.

## Scope boundary
One configured server; no account system, pairing, public hosting, VPN provisioning, or background Android push service.

## Done when
Tests verify endpoint validation/persistence, connection failures, disconnect/reconnect, uncertain command delivery without replay, and agent survival after client disconnect; a device connects over both WLAN and Tailscale.
