# Connection bridge deployment and verification

## Scope and security

Android can list windows, open a shell, and read the visible screen of an eligible pane in one existing tmux session on the configured server.
It cannot send pane input, create sessions, close windows, or control Pi.
Connected means the bridge is reachable, not that an agent is available.

HTTP address-only access is **unauthenticated PC control for trusted networks only**.
There is no login, pairing, token, TLS, or application-level authorization.
**Any reachable peer can read eligible pane contents without the Android app.**
Reachable peers can also read the window list and request a new shell window when exactly one session is available.
Pane input is off by default. With `--enable-pane-input`, **any reachable peer can type into eligible panes and potentially execute commands as the pane's user**, without Android.
WLAN alone does not authorize peers.
Do not forward this port publicly or deploy behind a public ingress.
Restrict the listener and firewall to intended peers; restrict Tailscale access with tailnet policy.
Tailscale supplies its own encrypted network transport, not application authentication.
A shared or untrusted WLAN requires suitable network isolation or Tailscale instead.
Do not change global firewall policy merely to make this example work.

## Try the single-session workflow

1. On the PC, start `tmux` if needed and leave exactly one session running. Create a window with `Ctrl+b c`.
2. From the repository root, run `./server/run-server.sh --bind 192.168.1.10`, replacing the example address with the PC's trusted WLAN or Tailscale IP. The script builds and runs the release bridge with Cargo and forwards bridge flags. Omit `--bind` for loopback only.
3. If needed, install the debug app on the connected Android device from the repository root: `cd android && ./gradlew :app:installDebug` (requires compile SDK 37).
4. In Glowkin, enter the PC IP and port `8787`, then tap **Save and connect** or **Replace and connect**. In **Session overview**, tap **Refresh** to check for PC-created windows. Tap **Open shell** and check on the PC that a new interactive shell opened at home in the same session. Use **Connection** to reach endpoint controls again.
5. In a PC shell, run `printf 'first line\n    second line\n'`. Tap that window on Android. Its detail view shows both lines with the indentation and refreshes while Android is in the foreground. Press Back once to return to the window list and stop pane reads. Selecting and reading a pane do not change PC focus or send input.

See [recorded verification](#recorded-verification) for what was checked.

## Run the standalone binary

To build once and run the standalone binary without Cargo, Python, or Pi at runtime:

```sh
cargo build --release --locked --manifest-path server/Cargo.toml

# Safe default: loopback only, useful with adb forwarding.
server/target/release/glowkin-bridge

# Enable pane input for a local HTTP client; still loopback-only.
server/target/release/glowkin-bridge --enable-pane-input

# Substitute the PC's actual trusted WLAN interface address.
server/target/release/glowkin-bridge --bind 192.168.1.10 --port 8787

# Or bind the PC's Tailscale address; start Tailscale separately.
server/target/release/glowkin-bridge --bind 100.101.102.103 --port 8787

# IPv6 bind syntax; do not bracket the CLI address.
server/target/release/glowkin-bridge --bind fd7a:115c:a1e0::1234 --port 8787
```

The CLI warns before binding that access is unauthenticated and any reachable peer can read eligible pane contents.
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
Expect HTTP 200, `Content-Type: application/json`, and `Cache-Control: no-store`.
The v1 snapshot requires integer `protocolVersion: 1` and a nonempty string `revision` that stays stable until the bridge restarts.
Android ignores unknown fields and accepts older snapshots without `workspace`, but hides workspace controls.
`POST /v1/workspace/open-shell` and the opt-in [pane input endpoint](#pane-input-api) are the mutation routes.
`POST /v1/workspace/read-pane` is read-only. A fixed-length JSON body supplies `generation`, `sessionId`, `windowId`, and `paneId` from the inventory. Eligible windows advertise `paneId`. Older bridges, split windows, and windows with an effective tmux `after-capture-pane` hook omit it. Large inventories may omit pane IDs to preserve the window list.
A successful read returns UTF-8 `text/plain` with `Cache-Control: no-store`. Stale or ineligible targets return 409 without content. An over-limit capture fails rather than presenting a partial screen.
Unknown routes return 404; mutations to the snapshot route return 405.
There is no remote disconnect route.

## Pane input API

`POST /v1/workspace/send-input` accepts fixed-length `Content-Type: application/json` with a body of at most 2,048 bytes. Use `generation`, `sessionId`, `windowId`, and `paneId` from one current snapshot; the values here are examples. A local client can use `curl --json`:

```sh
curl -i --json '{"generation":"1234-1700000000","sessionId":"$0","windowId":"@1","paneId":"%1","input":{"text":"hello π"}}' \
  http://127.0.0.1:8787/v1/workspace/send-input
```

Send exactly one `input` variant. `{"text":"hello π"}` inserts 1–256 UTF-8 bytes on one line, literally, with no implicit Enter. Control characters and Unicode line/paragraph separators are rejected. `{"key":"enter"}` sends an explicit Enter. The other allowed keys are `escape`, `up`, `down`, `left`, `right`, `backspace`, and `ctrl-o`.

Only the exact live, input-enabled pane in a single-pane window in the one existing session can receive input. A pane in a tmux mode is ineligible. The bridge does not change PC focus. Input goes to the application currently receiving keys, not to a particular prompt or screen. PC and remote typing share one editor; remote Enter can submit PC-typed text. Read the pane again to see echoed text. Pane reads do not reveal keystrokes that the application does not display. The bridge does not log or save input, but tmux can keep commands in its own message history, and terminal applications can log what they receive. Do not use this endpoint to enter secrets.

- **204:** tmux injected input, not necessarily that the application processed it or that Pi made a decision.
- **409:** The target was definitely rejected; the bridge did not inject input.
- **503:** Delivery is uncertain. A lost connection or response can also leave delivery uncertain. Do not replay. Follow [mutation reconciliation](decisions.md#connection-and-delivery) before any fresh action.
- **403:** Input is disabled or the request carries browser request headers (`Origin`, `Referer`, or `Sec-Fetch-*`). The bridge sends no CORS headers.
- **400 / 408 / 413 / 415:** Invalid request, body-read timeout, oversized body, or unsupported media type, respectively.

## Android

Target SDK remains 36; minimum SDK remains 28.
Enter a hostname or IP address and a separate port (1–65535).
Do not enter `http://`, a path, credentials, or a host with an embedded port.
Raw and bracketed IPv6 are accepted; the saved host omits brackets.
Validation rejects malformed hosts without resolving DNS.
**Save and connect** or **Replace and connect** persists one endpoint across restarts and clears the old server's snapshot.
Invalid input or connection failure does not erase the saved endpoint.

**Retry** reconnects using the saved endpoint without saving draft edits.
**Disconnect** stops local polling without contacting or stopping the server.
Foreground polling reads current state every two seconds after the preceding read completes.
Android shows connecting, then connected only after a valid snapshot.
A failed read shows disconnected with an actionable error, marks retained state stale, and retries while foregrounded.
A non-200 or invalid snapshot is a protocol error: check the port and bridge version.
Transport errors and timeouts mean unreachable, not that Pi stopped or a command succeeded.
Each connect/read timeout is three seconds; snapshots are bounded to 64 KiB.
Requests disable redirects and caching and carry no application credentials.
The app saves no snapshot or command queue.
Foreground entry reconciles the saved endpoint unless the user disconnected during this activity lifetime.
Backgrounding or destroying the activity stops scheduled reads and invalidates in-flight UI deliveries.
There is no background service or push integration.
Pane previews show the current single-pane screen, not scrollback or an interactive terminal. They are bounded to 64 KiB and are not logged or saved by Android or the bridge.
After displaying a nonblank preview, Android hides the app’s contents in Recents and blocks screen capture until the activity closes.
See [delivery rules](decisions.md#connection-and-delivery) for uncertain mutations.

## Development and automated checks

```sh
cargo test --locked --manifest-path server/Cargo.toml
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Server tests exercise the Rust binary: anonymous HTTP snapshots, revision stability and change on restart, route rejection, CLI validation, and independent-process survival through shutdown.
The independent sentinel is **not** a pi/tmux survival test.
JVM tests cover domain validation, persistence orchestration, stale snapshots, reconciliation, and obsolete responses.
Instrumentation tests use real SharedPreferences and local HTTP sockets.
An emulator can check local transport and UI behavior but cannot establish WLAN/Tailscale acceptance.

For a loopback bridge and emulator, this optional development mapping makes the PC bridge available at Android `127.0.0.1:8787`:

```sh
adb -s emulator-5554 reverse tcp:8787 tcp:8787
# Remove after verification.
adb -s emulator-5554 reverse --remove tcp:8787
```

## Manual acceptance checks

1. On a physical Android device and PC on the intended trusted WLAN, bind the PC's WLAN address and permit only the intended peer through host controls.
2. Enter that address/port in Android and verify connecting then connected, without login or pairing.
3. Replace the endpoint, force-stop/reopen Android, and verify the replacement remains saved and is reread.
4. Stop the bridge or interrupt the device network; verify disconnected, actionable error, and a stale previous snapshot.
5. Restart the bridge or restore the network; verify automatic foreground reconciliation to the new process revision, without implying any command succeeded.
6. Observe bridge HTTP traffic. Background Android and confirm no new polls after any in-flight read completes. Foreground it and confirm polling resumes.
7. Repeat connection and network-loss checks over actual Tailscale with the PC's tailnet hostname/address and restrictive tailnet policy. Do not count `adb reverse` as WLAN or Tailscale.
8. With a real pi running in tmux independently, record its PID and session/window identity. Disconnect, background, and force-stop Android; verify the same pi process remains usable from the PC. Stop/restart the bridge and verify again. This checks lifetime independence only, not integration with pi.
9. Verify real IPv6 reachability where supported; parsing and URI construction alone do not prove network routing.
10. In one tmux session, verify Android lists and refreshes PC-created windows. Tap **Open shell** and verify exactly one interactive shell at home in that session, not an Android terminal. An unavailable target must fail without opening a window elsewhere.

## Recorded verification

- **Connection, automated (original slice, before Rust port):** Three server tests and live HTTP/security-warning CLI smoke checks passed. Android passed 12 domain tests plus the existing JVM example, six emulator tests (including MainActivity save/connect, stale disconnect, and recreation), debug assembly, and lint. Lint reported starter/dependency warnings, not errors.
- **Connection, user acceptance:** The user reported the manual checks worked and requested completion. These are not independently observed device results. Real IPv6 routing remains unverified.
- **Single-session workspace, automated:** Seven bridge, 13 Android JVM, and seven emulator tests passed. Rust formatting, Clippy, both debug builds, and Android lint passed. The emulator refreshed PC-created windows through the real bridge and opened exactly one interactive shell at home in the same disposable session. Private-socket tests rejected unavailable and stale targets without retargeting. A later `cargo test --locked --manifest-path server/Cargo.toml --test workspace_http` rerun on tmux 3.8 passed all three tests without app changes.
- **Single-session workspace, user confirmation:** **Open shell** creates a window on the PC; the user had expected an Android terminal. This confirms window creation, not the working directory or the other guarantees.
- **Pane preview:** The emulator connected to the real release bridge over `adb reverse` and previewed a shell and an offline Pi TUI on disposable tmux 3.8. It preserved indentation, PC focus, and pending input. See the [slice verification](plan.md#read-a-selected-pc-tmux-pane-on-android) for rejection, compatibility, privacy, and coverage details. No physical-device or WLAN/Tailscale verification was performed for pane reading.
- **Pane input:** The release bridge on loopback drove the real Pi permission dialog and shared editor on disposable tmux 3.8 with an offline model fixture. See the [slice verification](plan.md#drive-one-existing-pi-pane-through-the-bridge). Android input is not implemented.
