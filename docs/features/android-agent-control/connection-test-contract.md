# Connection test contract and builder handoff

This contract covers `connection.md`, not workspace discovery, chat, or command UI.

## Minimal design

Run the bridge independently of Android, Pi, and tmux.
Use a framework-free Kotlin connection domain with synchronous endpoint-store and snapshot-transport ports.
The caller runs blocking operations off Android's main thread and schedules `poll()` while connected.
The domain does not own a thread, retry timer, Android lifecycle, or an agent process.
One-shot `poll()` keeps tests deterministic without prescribing reconnect timing.

HTTP polling reads `GET /v1/connection/snapshot` on the saved host and port.
Success requires HTTP 200 and JSON `{"protocolVersion":1,"revision":"opaque-nonempty-string"}`.
Require an integer version exactly 1 and a nonempty string revision; reject missing, null, mistyped, or incompatible fields.
Ignore unknown fields for forward compatibility.
Treat non-200 responses or invalid snapshots as `PROTOCOL`; treat network failures/timeouts as `UNREACHABLE`.
Revision is opaque, not an ordered counter; a restarted server may return an unrelated value.
Each successful poll replaces the previous snapshot, even when revisions cannot be ordered.
This foundation snapshot deliberately contains no workspace/chat data.
Future slices must extend the authoritative payload and its mapping tests.

**Security:** HTTP address-only access is unauthenticated PC control for trusted networks only.
Any reachable peer may gain control as later capabilities are added.
WLAN alone supplies no authorization; restrict access through host/network controls or Tailscale policy.
Do not publicly expose the bridge.
No login, token, pairing, or background push service belongs in this slice.

## Kotlin APIs expected by tests

Base package: `dev.lhrb.app.glowkin.features.connection`.
All value types below live in `.domain`; ports live in `.domain.ports`.
Names specify the test-facing API, not required source-file organization.

```kotlin
// Value equality is required for Endpoint and Snapshot.
Endpoint.parse(host: String, port: Int): Endpoint
Endpoint.host: String
Endpoint.port: Int
class InvalidEndpoint : IllegalArgumentException

data class Snapshot(val revision: String)
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
enum class ConnectionError { UNREACHABLE, PROTOCOL }
class ConnectionFailure(val error: ConnectionError) : Exception

enum class ChangeOutcome {
    APPLIED, UNCERTAIN, RECONCILIATION_REQUIRED, NOT_CONNECTED
}

interface EndpointStorePort {
    fun load(): Endpoint?
    fun save(endpoint: Endpoint)
}
interface SnapshotTransportPort {
    fun fetch(endpoint: Endpoint): Snapshot // throws ConnectionFailure for expected failures
}

ConnectionUseCase(store: EndpointStorePort, transport: SnapshotTransportPort)
ConnectionUseCase.state // immutable observable current value; concrete type name unrestricted
state.endpoint: Endpoint?
state.status: ConnectionStatus
state.snapshot: Snapshot?
state.stale: Boolean
state.error: ConnectionError?
state.needsReconciliation: Boolean
ConnectionUseCase.saveEndpoint(host: String, port: Int) // throws InvalidEndpoint
ConnectionUseCase.connect()
ConnectionUseCase.poll()
ConnectionUseCase.disconnect()
ConnectionUseCase.attemptChange(send: () -> ChangeOutcome): ChangeOutcome
```

Accept DNS hostnames, WLAN/Tailscale IPv4, raw IPv6, and bracketed IPv6.
Store IPv6 without brackets; the HTTP adapter must add URI authority brackets as needed.
Accept ports 1–65535 inclusive.
Reject URLs, embedded ports, paths, credentials, malformed IP literals, and malformed DNS names.
Validation performs no DNS lookup; reject invalid input before persistence or networking.
The domain tests enumerate representative valid and invalid values, not an exhaustive DNS grammar.

Construction loads the one saved endpoint without connecting.
Saving replaces that endpoint, disconnects locally, and clears data from the old server.
`connect()` exposes CONNECTING before fetching and CONNECTED only after a valid snapshot.
`poll()` fetches current state from the configured server.
Expected failures produce DISCONNECTED plus the typed error, preserving endpoint and previous snapshot.
A retained snapshot is stale after local disconnect or failed polling.
Successful reconciliation replaces the snapshot, clears staleness/error, and establishes CONNECTED.
`disconnect()` performs no remote operation.

`attemptChange` is only a generic delivery foundation; it adds no HTTP mutation route or command UI.
It invokes the supplied attempt once only when connected and reconciled.
If the invoked callback throws `ConnectionFailure(UNREACHABLE)`, the use case must return `UNCERTAIN` and require reconciliation, never assume success or allow an immediate retry.
Adapters may also return `UNCERTAIN` explicitly.
Uncertain delivery returns `UNCERTAIN` and sets `needsReconciliation`.
Another attempt before a successful snapshot returns `RECONCILIATION_REQUIRED` without calling its lambda.
Failed snapshots do not clear this gate.
A successful snapshot clears the gate but does not replay the old lambda or report it as applied.
Only a later explicit caller action may submit another change.
Disconnected attempts return `NOT_CONNECTED` without sending.
The use case must not retain callbacks for automatic replay.

## Android adapter APIs

```kotlin
// .adapters.outbound.endpoint
SharedPreferencesEndpointStore(preferences: android.content.SharedPreferences) : EndpointStorePort
// .adapters.outbound.http
HttpSnapshotTransport(timeoutMillis: Int) : SnapshotTransportPort
```

Instrumented tests use real SharedPreferences and local HTTP sockets, not mocked Android networking.
They check replacement/recreation, uncached polling, anonymous requests, protocol rejection, and refused connections.
Use a bounded connect/read timeout.
The app needs Internet permission and cleartext HTTP enabled for the accepted trusted-network transport.
No new test library is required.
IPv6 validation has JVM coverage; real IPv6 HTTP reachability still needs device/network verification.

## Bridge HTTP contract

Tests launch the Rust bridge binary with its default loopback bind and an explicit port.
The anonymous snapshot endpoint returns `application/json` with the versioned payload above and `Cache-Control: no-store`.
The revision stays stable until the bridge restarts.
Unknown paths return 404.
POST, PUT, PATCH, and DELETE to the snapshot route return 405.
There is no remote disconnect route or process ownership in this foundation.

The process test uses an independent sentinel across client disconnect and bridge shutdown.
It does **not** attach to pi or tmux and cannot establish real agent survival.
Do not label it an agent-survival acceptance test.

## Verification and known gaps

Run server tests from the repository root:

```sh
cargo test --locked --manifest-path server/Cargo.toml
```
Run Android JVM tests:

```sh
cd android
./gradlew :app:testDebugUnitTest --console=plain
```

Run adapter tests with an available emulator/device:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest --console=plain
```

### Original handoff notes

The Gradle baseline failed `:app:checkDebugAarMetadata`: Core 1.19.1 and lifecycle 2.11.0 require compileSdk 37; the starter uses 36.1.
Java 21 is installed; this is not a JVM setup failure.
The builder will align compileSdk only, leaving target/min SDK unchanged.
No build files were changed by the test author.
Production APIs are absent, so import/compilation failures are expected until implementation exists.
An emulator (`emulator-5554`) became available at handoff; adapter execution remains blocked by missing production APIs.
To distinguish missing implementation from the baseline SDK mismatch, this diagnostic command bypassed only AAR metadata validation:

```sh
cd android
./gradlew :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin -x :app:checkDebugAarMetadata --console=plain
```

Both test compilation tasks reached Kotlin and failed on unresolved production APIs.
No tests reached behavioral assertions; these are missing-implementation failures, not passing or behaviorally failing tests.
The metadata bypass is diagnostic only, not a supported build fix.

Remaining acceptance gaps: real pi/tmux survival, Android process restart durability beyond adapter recreation, device WLAN and Tailscale connections, actual network loss/recovery, and lifecycle-driven polling.
These tests do not claim those acceptance checks passed.
