package dev.lhrb.app.glowkin.features.connection.domain

import dev.lhrb.app.glowkin.features.connection.domain.ports.EndpointStorePort
import dev.lhrb.app.glowkin.features.connection.domain.ports.SnapshotTransportPort

data class Snapshot(val revision: String)
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
enum class ConnectionError { UNREACHABLE, PROTOCOL }
class ConnectionFailure(val error: ConnectionError) : Exception(error.name)
enum class ChangeOutcome { APPLIED, UNCERTAIN, RECONCILIATION_REQUIRED, NOT_CONNECTED }

data class ConnectionState(
    val endpoint: Endpoint? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val snapshot: Snapshot? = null,
    val stale: Boolean = false,
    val error: ConnectionError? = null,
    val needsReconciliation: Boolean = false,
)

/** Blocking ports belong on a worker. Invalidation never waits for an in-flight read. */
class ConnectionUseCase(private val store: EndpointStorePort, private val transport: SnapshotTransportPort) {
    private val lock = Any()
    private val saveLock = Any()
    private var generation = 0L
    private var endpointGeneration = 0L
    private var changeInFlight = false
    @Volatile var state = ConnectionState(endpoint = store.load())
        private set

    fun saveEndpoint(host: String, port: Int) {
        val endpoint = Endpoint.parse(host, port)
        synchronized(saveLock) {
            store.save(endpoint)
            synchronized(lock) {
                generation++
                endpointGeneration++
                changeInFlight = false
                state = ConnectionState(endpoint = endpoint)
            }
        }
    }

    fun connect() = refresh(connecting = true)
    fun poll() = refresh(connecting = false)

    private fun refresh(connecting: Boolean) {
        val request = synchronized(lock) {
            val endpoint = state.endpoint ?: return
            val token = ++generation
            if (connecting) state = state.copy(status = ConnectionStatus.CONNECTING, error = null)
            endpoint to token
        }
        try {
            val snapshot = transport.fetch(request.first)
            synchronized(lock) {
                if (generation == request.second) state = state.copy(
                    status = ConnectionStatus.CONNECTED, snapshot = snapshot, stale = false,
                    error = null, needsReconciliation = false,
                )
            }
        } catch (failure: ConnectionFailure) {
            synchronized(lock) {
                if (generation == request.second) state = state.copy(
                    status = ConnectionStatus.DISCONNECTED, stale = state.snapshot != null, error = failure.error,
                )
            }
        }
    }

    fun disconnect() = synchronized(lock) {
        generation++
        state = state.copy(status = ConnectionStatus.DISCONNECTED, stale = state.snapshot != null, error = null)
    }

    /** Never retain the callback: only a fresh explicit action may retry after reconciliation. */
    fun attemptChange(send: () -> ChangeOutcome): ChangeOutcome {
        val token = synchronized(lock) {
            if (state.needsReconciliation || changeInFlight) return ChangeOutcome.RECONCILIATION_REQUIRED
            if (state.status != ConnectionStatus.CONNECTED) return ChangeOutcome.NOT_CONNECTED
            changeInFlight = true
            endpointGeneration
        }
        val outcome = try { send() } catch (_: ConnectionFailure) { ChangeOutcome.UNCERTAIN }
        synchronized(lock) {
            if (endpointGeneration == token) {
                changeInFlight = false
                if (outcome == ChangeOutcome.UNCERTAIN) {
                    // Reads begun before delivery became uncertain cannot reconcile it.
                    generation++
                    state = state.copy(needsReconciliation = true, stale = state.snapshot != null)
                }
            }
        }
        return outcome
    }
}
