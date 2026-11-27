# Connection bridge deployment and verification

## Scope and security

Android lists windows, opens a PC shell, and previews eligible panes in one existing tmux session. An opt-in bridge also lets Android send text and keys to the selected pane. With the optional Pi chat extension, Android can read a Pi conversation and edit its shared draft; submit from the PC. There is no session creation or window closing. Connected means the bridge is reachable, not that an agent is available.

**This is unauthenticated PC access for trusted networks only.** Any reachable peer can list windows and their reported folder names, read eligible panes, and request a shell in the single session without Android. The standalone binary defaults to read-only pane access. `server/run-server.sh` sets `--enable-pane-input`, so **any reachable peer can type into eligible panes and potentially execute commands as the pane's user**. The bridge warns before binding. There is no login, pairing, token, TLS, or application authorization.

Loading the Pi chat extension makes the eligible pane’s recent conversation, tool output, and unsent draft available to any peer that can reach the bridge, including on a bridge with input disabled. With `--enable-pane-input`, any reachable peer can also edit that draft. The structured chat API never submits it. The separate pre-existing pane input API still sends raw keys to eligible panes when enabled; do not rely on the chat UI to restrict a peer.

Keep loopback unless remote access is needed. Bind only the intended interface and restrict peers with host firewall rules or tailnet policy. WLAN alone does not establish trust; isolate peers on shared WLAN or use restricted Tailscale. Tailscale encrypts transport but does not add application authentication. Do not expose the port publicly, use public ingress, or relax global firewall policy for this example.

## Try the single-session workflow

1. On the PC, start `tmux` if needed and leave one session running. Make a test window with `Ctrl+b c`. Android will not pick among multiple sessions.
2. From the repo root, run `./server/run-server.sh --bind 192.168.1.10` with the PC's trusted WLAN or Tailscale IP. The script builds and runs the release bridge with pane input enabled and forwards CLI flags. Omit `--bind` for loopback only. For read-only pane access, run the standalone binary without `--enable-pane-input`.
3. If needed, install the debug app on a connected device: `cd android && ./gradlew :app:installDebug` (compile SDK 37; target 36; minimum 28).
4. Enter the PC IP and port `8787`. Tap **Save and connect** or **Replace and connect**. In **Workspace**, tap **Refresh** to see PC-created windows. Tap **Open shell** and check that exactly one interactive shell opens at home on the PC. Return to endpoint controls via **Connection**.
5. In the PC test window, run `printf 'first line\n    second line\n'`. Tap that window in Android. Check indentation and foreground refresh without changes to PC focus or input. Press Back to stop pane reads and return to the list.

## Run the standalone binary

Build once; Cargo, Python, and Pi are not needed at runtime. Run the bridge in a PC terminal or OS-managed service. Android does not own the bridge, tmux, or Pi. Ctrl-C stops only the bridge.

```sh
cargo build --release --locked --manifest-path server/Cargo.toml
server/target/release/glowkin-bridge                                        # loopback only
server/target/release/glowkin-bridge --enable-pane-input                    # opt in, still loopback only
server/target/release/glowkin-bridge --bind 192.168.1.10 --port 8787          # trusted WLAN
server/target/release/glowkin-bridge --bind 100.101.102.103 --port 8787       # Tailscale, started separately
server/target/release/glowkin-bridge --bind fd7a:115c:a1e0::1234 --port 8787  # unbracketed CLI IPv6
```

Use the PC's actual interface address. One interface bind does not cover other interfaces. Port defaults to `8787` and must be 1–65535.

Verify the listener with `curl --fail http://127.0.0.1:8787/v1/connection/snapshot`. Substitute the chosen bind address if needed; bracket IPv6 in the URL.

## v1 HTTP contract

- `GET /v1/connection/snapshot`: 200, `application/json`, `Cache-Control: no-store`, at most 64 KiB. Requires integer `protocolVersion: 1` and a nonempty `revision` string stable for the bridge lifetime. Android ignores unknown fields. Older bridges without `workspace` still connect without workspace controls.
- Snapshot `paneInputEnabled` is an optional boolean reflecting `--enable-pane-input`. Android needs explicit `true` and a current pane target; absent or `false` is read-only. A malformed or duplicate capability is a protocol error. The bridge omits the field if adding it would exceed the existing snapshot budget after fitting the v1 workspace as before.
- Workspace status is `ready`, `no_session`, `multiple_sessions`, or `unavailable`. A ready session lists `id`, `name`, `generation`, and `windows` with `id`, `name`, and optional `paneId`. Only eligible single-pane windows advertise `paneId`; capture hooks or inventory limits can suppress it. No pane contents are in inventory.
- A window may include `"folder":{"key":"<opaque nonempty string>","name":"<nonblank display basename>"}`. Equal keys identify byte-for-byte equal tmux working directories within that snapshot; different directories can have the same display name. Keys are snapshot-local, can change on refresh, and are not pane targets. The bridge sends the basename only, not any parent path. Folder metadata requires a reliable working directory from a live sole pane, independent of `paneId` eligibility. The bridge omits it for split windows, unavailable paths, and names it cannot display reliably. At either the tmux output or HTTP body limit it drops folder metadata before sacrificing any baseline inventory or capabilities.
- `POST /v1/workspace/open-shell`: send `generation` and `sessionId` from the current snapshot. 204 acknowledges detached window creation, not shell readiness. 409 means the exact single-session target was rejected with no fallback. 503 means uncertain delivery; [reconcile before retrying](decisions.md#connection-and-delivery).
- `POST /v1/workspace/read-pane`: read-only. Add `windowId` and `paneId` from the same snapshot. 200 returns only the visible grid as UTF-8 `text/plain` with `Cache-Control: no-store`, at most 64 KiB. 409 rejects stale or ineligible targets; 503 means capture failed, including over-limit captures. Neither returns partial content.
- All POST bodies are fixed-length `application/json`; the shell and read routes cap them at 256 bytes. Browser request headers (`Origin`, `Referer`, or `Sec-Fetch-*`) get 403; there are no CORS headers. Invalid, timed-out, oversized, or wrong-media-type bodies get 400, 408, 413, or 415 respectively.
- Unknown routes and queries return 404. Wrong methods on known routes return 405 for supported verbs; unsupported verbs return 501. There is no remote disconnect route.

## Pane input API

`POST /v1/workspace/send-input` is a mutation, available only with `--enable-pane-input`. A local client can use `curl --json` with the four pane target fields from one current snapshot. Replace these example IDs:

```sh
curl -i --json '{"generation":"1234-1700000000","sessionId":"$0","windowId":"@1","paneId":"%1","input":{"text":"hello π"}}' \
  http://127.0.0.1:8787/v1/workspace/send-input
```

The JSON body limit is 2,048 bytes. Send exactly one `input` variant:

- `{"text":"hello π"}` inserts 1–256 UTF-8 bytes literally on one line, with no implicit Enter. Control characters and Unicode line/paragraph separators are rejected.
- `{"key":"enter"}` sends explicit Enter. The other keys are `escape`, `up`, `down`, `left`, `right`, `backspace`, and `ctrl-o`.

Only the exact live, input-enabled pane in a single-pane window in the one existing session can receive input. Panes in tmux modes are ineligible. Delivery does not change PC focus. Input goes to the current application, not a specific prompt. PC and remote typing share an editor; remote Enter can submit PC-typed text. Read the pane to see echoed text, not hidden keystrokes. The bridge does not log or save input, but tmux history and terminal apps can. Do not enter secrets through this endpoint.

- **204:** tmux injected input, not necessarily that Pi or another application processed it.
- **409:** The target was rejected and no input was injected.
- **503 or lost response:** Delivery is uncertain. Never replay; [reconcile before a fresh action](decisions.md#connection-and-delivery).
- **403:** Input is disabled or browser request headers were present. The common POST errors above also apply.

## Pi conversation and shared draft

With Pi 1.6.0 on Linux, load [`pi-chat/extension.ts`](../../../pi-chat/extension.ts) in a normal interactive Pi running in an eligible tmux pane. For a disposable session, run `pi -e /absolute/path/to/glowkin/pi-chat/extension.ts` from that pane. To attach after a one-time `/reload`, configure that absolute path in Pi’s `extensions` settings first. Run the bridge as the same OS user against its configured tmux server. The extension does not replace Pi or start the bridge.

Select the pane in Android. Start a non-sensitive draft on the PC, edit it on Android, then submit on the PC. The submitted prompt should appear once from Pi’s conversation. Android polls while the chat is open. If a PC edit wins, review the current draft before editing again. If the Pi session becomes unavailable, the chat pauses; that is not evidence the process exited. Android and the bridge do not log or store the conversation or draft; Pi’s own history settings still apply.

- `POST /v1/workspace/read-pi-chat`: Send the four pane target fields. After the first read, send the returned `instanceId` to bind subsequent reads to that Pi instance. `200` returns a bounded recent conversation, tool text, optional observed running subagents, draft and draft revision, and the current activity and edit capability. Responses are `no-store`. `404` on initial read indicates no structured chat; `410` on a bound read confirms that instance quit. `409` rejects an unavailable exact target or instance; `503` means the chat read failed.
- `POST /v1/workspace/edit-pi-draft`: Send the same exact target and `instanceId`, `bridgeRevision` from the connection snapshot, `draftRevision` from the chat read, and `text`. Text can be empty and uses at most 16 KiB of UTF-8; line feeds and tabs are allowed, but other control characters and Unicode line/paragraph separators are not. `204` acknowledges a draft edit, not a submission. `403` means input is disabled or a browser was rejected. `409` means the target or revision changed or edits are blocked. After `503` or a lost response, read authoritative Pi state before another edit; never replay.

## Android

Enter a hostname or IP and separate port (1–65535), without a scheme, path, credentials, or embedded port. Raw and bracketed IPv6 are accepted; the saved host omits brackets. Validation does not resolve DNS.

**Save and connect** or **Replace and connect** saves one endpoint across restarts and clears the old snapshot. Invalid edits or connection failure do not erase the saved endpoint. **Retry** uses it without saving draft edits. **Disconnect** stops local polling only.

Android connects only after a valid snapshot. A failed read shows an actionable error, marks retained state stale, and retries while foregrounded. A non-200 or invalid snapshot means check the port and bridge version. A transport failure does not imply Pi stopped or a mutation succeeded.

Foreground polling waits two seconds after each read. Connect and read timeouts are three seconds each. Requests disable redirects and caching and carry no application credentials. Foreground entry reconciles the saved endpoint unless the user disconnected during this activity. Backgrounding or destroying the activity stops scheduled reads and invalidates in-flight UI delivery. There is no background service, push, saved snapshot, or command queue.

Pane previews show the visible screen, not scrollback or a terminal. Neither Android nor the bridge logs or saves pane contents. Android secures the window before showing a nonblank preview or input controls, hiding Recents content and blocking screen capture until the activity closes. See [preview and delivery decisions](decisions.md).

With `--enable-pane-input`, open an eligible window, type a one-line suffix, and tap **Insert text**. Check the combined PC and Android draft in the preview. Tap **Enter** separately to submit the pane's current input, including text typed on the PC. The same controls can send Escape, four arrows, Backspace, and Ctrl+O to whatever currently has focus inside that pane. They do not address a particular prompt or change the PC's focused window.

An acknowledgement means the bridge injected the input, not that the pane's app processed it. If delivery is uncertain, check the pane before acting again. A new successful snapshot read must reconcile the connection before input or **Open shell** can send again; Android never replays the action.

## Development and automated checks

```sh
cargo fmt --manifest-path server/Cargo.toml -- --check
cargo clippy --locked --manifest-path server/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path server/Cargo.toml
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Server tests exercise the binary and disposable tmux targets. The shutdown sentinel is not a Pi/tmux survival test. JVM tests cover validation, persistence orchestration, stale state, reconciliation, and obsolete responses. Instrumentation uses SharedPreferences and local HTTP sockets.

To reach a loopback bridge from the emulator at `127.0.0.1:8787`:

```sh
adb -s emulator-5554 reverse tcp:8787 tcp:8787
# Remove after verification.
adb -s emulator-5554 reverse --remove tcp:8787
```

## Manual acceptance checks

1. On a physical device, run the single-session workflow over trusted WLAN with host access restricted to intended peers. Verify connection without login, refresh of PC-created windows, and exactly one shell at home. An unavailable target must not open a window elsewhere.
2. Replace the endpoint and force-stop/reopen Android. Verify the replacement persists and is reread.
3. Interrupt the network or stop the bridge. Verify disconnected, actionable error, and stale state. Restore access; verify automatic foreground reconciliation and a new revision after bridge restart, not inferred mutation success.
4. Observe bridge traffic through background/foreground transitions. Allow an in-flight read to finish; no new polls should start in the background.
5. Repeat connection and network-loss checks over actual Tailscale with restrictive policy. Verify IPv6 routing where supported. Emulator forwarding and IPv6 parsing do not prove network reachability.
6. Record the PID and tmux identity of an independently running Pi. Verify it remains usable from the PC after Android disconnect, backgrounding, and force-stop, and after bridge stop/restart. This checks lifetime independence, not Pi integration.

## Recorded verification

**User feedback**

- **Connection:** User reported manual checks worked and requested completion. Device results were not independently observed.
- **Workspace:** User confirmed **Open shell** opened a PC window, not the Android terminal they expected. This does not confirm the working directory or other guarantees.
- **Pane preview:** Review pending independently of input. **Bridge input:** Accepted. **Android input:** Emulator workflow observed; review pending.

**Observed checks** (historical counts, not a fresh run)

| Slice | Automated checks passed | Observed behavior |
| --- | --- | --- |
| Connection (before Rust port) | 3 server tests, 13 JVM tests, 6 emulator tests, Android debug assembly and lint | Live HTTP and CLI security-warning smoke checks; Android save/connect, stale disconnect, and recreation. |
| Workspace | 7 bridge, 13 JVM, and 7 emulator tests; Rust fmt, Clippy, debug builds, Android lint | Emulator refreshed PC-created windows through the real bridge and opened one interactive shell at home in the same disposable session. Private-socket tests rejected unavailable/stale targets without fallback. Later tmux 3.8 rerun of `cargo test --locked --manifest-path server/Cargo.toml --test workspace_http` passed all 3 tests then present. |
| Pane preview | 13 bridge, 13 JVM, and 10 emulator tests; Rust fmt, strict Clippy, debug/release builds, Android assembly and lint (15 unrelated warnings, no errors) | Emulator over `adb reverse` to release bridge on loopback, disposable tmux 3.8: shell and offline Pi previews preserved indentation, PC focus, and pending input. Split, multiple-session, and disconnect cases cleared preview. Open shell still created one detached interactive home shell. A synthetic marker stayed out of inventory, app storage, and logs; Recents was black. Regressions caught wrong-window capture, obsolete same-pane responses, and capture-hook focus changes before fixes. Coverage includes target replacement/movement, capture bounds, and both inventory limits. |
| Bridge input | 25 tests on tmux 3.8; Rust fmt, strict Clippy, debug/release builds | Private tmux verified default-off, exact UTF-8 and all eight keys, unchanged focus, request and target rejection, no send-keys hooks or interference from a read-only attached client, and one write with no replay after lost acknowledgement. The default-off test first failed against the missing route. Inventory limits, Open shell, and pane reads still passed. |
| Android input | 26 bridge, 15 JVM, and 25 emulator tests; Rust fmt, strict Clippy, debug/release builds, Android debug assembly and lint | Verified snapshot limits and read-only compatibility, exact single sends, input rejection versus uncertainty, no replay, the shared Open shell gate, draft clearing, and recreation without restoring drafts. The recreation test first failed because the older build lost the uncertainty message. |

**Offline Pi demo:** Release bridge on loopback in an isolated network namespace with disposable tmux 3.8, Pi 1.6.0, permission-system 42.2.1, and a local model fixture. Ctrl+O expanded permission details, Down/Up moved selection, Enter approved a harmless fixture write, and Escape denied another without writing. Pane read showed a PC draft followed by the literal remote suffix ` + remote π` without submission. Separate Enter delivered the combined text. The attached client stayed on another window.

**Android Pi demo:** Pixel 6 emulator over `adb reverse` to the release bridge on loopback with `--enable-pane-input`. Android inserted a suffix into a PC-started draft. The combined text appeared in the preview without submission, and separate Enter submitted it. Ctrl+O, Down, Up, and Enter expanded, selected, and approved a harmless offline Pi permission request; Escape dismissed the next one. The attached PC client kept focus on another window.

**Gaps:** No real IPv6 routing evidence. Pane preview and input were not verified on a physical device or over WLAN/Tailscale. The Pi demos used an offline model fixture, not a live model.
