# Connection bridge deployment and verification

## Scope and security

This slice exposes only bridge identity through a connection snapshot.
It does not discover workspaces, attach to tmux/pi, or expose command routes.
A connected screen establishes bridge reachability, not agent availability.
The opaque revision identifies this bridge process lifetime; it is not an ordered counter.
Future inventory slices must extend the authoritative snapshot rather than infer agent state from connectivity.

HTTP address-only access is **unauthenticated PC control for trusted networks only**.
There is no login, pairing, token, TLS, or application-level authorization.
Any reachable peer can read/control the PC as later capabilities become available.
WLAN alone does not authorize peers.
Do not forward this port publicly or deploy behind a public ingress.
Restrict the listener and firewall to intended peers; restrict Tailscale access with tailnet policy.
Tailscale supplies its own encrypted network transport, not application authentication.
A shared or untrusted WLAN requires suitable network isolation or Tailscale instead.
Do not change global firewall policy merely to make this example work.

## Run the server

Use Python 3 with its standard library; no packages need installing.
From the repository root:

```sh
# Safe default: loopback only, useful with adb forwarding.
python3 server/bridge.py

# Substitute the PC's actual trusted WLAN interface address.
python3 server/bridge.py --bind 192.168.1.10 --port 8787

# Or bind the PC's Tailscale address; start Tailscale separately.
python3 server/bridge.py --bind 100.101.102.103 --port 8787

# IPv6 listeners also work; do not bracket the CLI bind address.
python3 server/bridge.py --bind fd7a:115c:a1e0::1234 --port 8787
```

The CLI prints an unauthenticated-access warning before binding.
The default port is 8787; valid CLI ports are 1–65535.
Binding a specific interface is preferable to binding every interface.
Choose WLAN or Tailscale binding as appropriate; a single interface bind does not promise access through other interfaces.
Run the bridge independently of Android in a PC terminal or an OS-managed service.
Android never starts, stops, or owns the bridge, tmux, or pi.
Ctrl-C stops the bridge only; no external process cleanup occurs.

Verify the listener locally:

```sh
curl --fail http://127.0.0.1:8787/v1/connection/snapshot
```

Use the chosen bind address instead of loopback when necessary.
Expect HTTP 200, `application/json`, and `{"protocolVersion":1,"revision":"..."}`.
Unknown routes return 404; mutations to the snapshot route return 405.
There is no remote disconnect route.

## Android

Build and install with an Android SDK supporting compile SDK 37:

```sh
cd android
./gradlew :app:assembleDebug :app:installDebug
```

Target SDK remains 36; minimum SDK remains 28.
Open Glowkin and enter a hostname or IP address and a separate port.
Do not enter `http://`, a path, credentials, or a host with an embedded port.
Raw and bracketed IPv6 are accepted; the saved host omits brackets.
Choose **Save and connect**, or **Replace and connect** for an existing endpoint.
Saving persists one endpoint and clears the old server's snapshot.
Invalid input does not replace the saved endpoint.
A connection failure does not erase it.

**Retry** reconnects using the saved endpoint without saving draft edits.
**Disconnect** stops local polling without contacting or stopping the server.
Foreground polling reads current state every two seconds after the preceding read completes.
A failed read shows disconnected, retains a visibly stale snapshot if available, and retries while foregrounded.
Each HTTP connect/read timeout is three seconds; responses are bounded to 64 KiB.
Redirects and HTTP caching are disabled; requests carry no application credentials.
Protocol mismatches require checking the port and compatible bridge version.
The app saves only the endpoint, not a durable snapshot or command queue.
Returning to the foreground automatically reconciles the saved endpoint unless the user explicitly disconnected during this activity lifetime.
Backgrounding or destroying the activity stops scheduled polling and discards obsolete UI deliveries.
An already-running bounded HTTP read may finish; it cannot repopulate the foreground screen after invalidation.
No Android background service or push integration is installed.

## Automated verification

```sh
PYTHONPATH=server python3 -m unittest discover -s server/tests -v
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Server tests check anonymous HTTP snapshots, route rejection, and independent-process survival.
The independent Python sentinel is **not** a pi/tmux survival test.
JVM tests cover domain validation, persistence orchestration, stale snapshots, reconciliation, and obsolete responses.
Instrumentation tests use real SharedPreferences and local HTTP sockets.
An emulator can check local transport and UI behavior but cannot establish WLAN/Tailscale acceptance.

Recorded implementation verification:
- Python: all three server tests passed; CLI live HTTP and security-warning smoke checks passed.
- Android: 12 connection-domain tests plus the existing example passed; debug assembly and lint passed.
- Emulator `emulator-5554`: six instrumentation tests passed, including the real MainActivity save/connect, stale disconnect, and recreation/reconciliation flow.
- Lint retains starter/dependency-version warnings, including the intentionally preserved target SDK 36; no lint errors occurred.

Before implementation, compile SDK 36.1 failed dependency metadata checks.
After correcting compile SDK to 37, the preimplementation JVM run reached Kotlin but failed on missing production APIs.
Those were setup/missing-foundation failures, not behavioral assertion failures.

For a loopback bridge and emulator, this optional development mapping makes the PC bridge available at Android `127.0.0.1:8787`:

```sh
adb -s emulator-5554 reverse tcp:8787 tcp:8787
# Remove after verification.
adb -s emulator-5554 reverse --remove tcp:8787
```

## Manual acceptance checklist — not yet established by automated tests

1. On a physical Android device and PC on the intended trusted WLAN, bind the PC's WLAN address and permit only the intended peer through host controls.
2. Enter that address/port in Android and verify connecting then connected, without login or pairing.
3. Replace the endpoint, force-stop/reopen Android, and verify the replacement remains saved and is reread.
4. Stop the bridge or interrupt the device network; verify disconnected, actionable error, and a stale previous snapshot.
5. Restart the bridge or restore the network; verify automatic foreground reconciliation to the new process revision, without implying any command succeeded.
6. Background Android; confirm PC access logs stop after any in-flight read completes. Foreground it and confirm polling resumes.
7. Repeat connection and network-loss checks over actual Tailscale with the PC's tailnet hostname/address and restrictive tailnet policy. Do not count `adb reverse` as WLAN or Tailscale.
8. With a real pi running in tmux independently, record its PID and session/window identity. Disconnect, background, and force-stop Android; verify the same pi process remains usable from the PC. Stop/restart the bridge and verify again. This checks lifetime independence only, not integration with pi.
9. Verify real IPv6 reachability where supported; parsing and URI construction alone do not prove network routing.

The user reported that the manual checks work and requested marking the slice Done after clarification of the acceptance checklist. This records user acceptance, not independently observed device results. Real IPv6 routing remains unverified.
