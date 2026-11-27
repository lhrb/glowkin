package dev.lhrb.app.glowkin.features.connection.domain

import dev.lhrb.app.glowkin.features.connection.domain.ports.EndpointStorePort
import dev.lhrb.app.glowkin.features.connection.domain.ports.SnapshotTransportPort

data class Snapshot(val revision: String, val workspace: Workspace? = null, val paneInputEnabled: Boolean = false)

sealed interface Workspace {
    data class Ready(val target: SessionTarget, val name: String, val windows: List<WorkspaceWindow>) : Workspace
    data object NoSession : Workspace
    data object MultipleSessions : Workspace
    data object Unavailable : Workspace
}

data class SessionTarget(val id: String, val generation: String)
data class WorkspaceWindow(
    val id: String,
    val name: String,
    val paneId: String? = null,
    val folder: WorkspaceFolder? = null,
)
data class WorkspaceFolder(val key: String, val name: String)
data class PaneTarget(val session: SessionTarget, val windowId: String, val paneId: String)

sealed interface PaneRead {
    data class Captured(val text: String) : PaneRead
    data object TargetUnavailable : PaneRead
    data object Failed : PaneRead
}
sealed interface PaneInput {
    class Text(val value: String) : PaneInput {
        init { require(isValid(value)) }

        companion object {
            fun isValid(value: String): Boolean = value.isNotEmpty() && value.length <= 256 &&
                value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' } &&
                Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 256
        }
    }

    enum class Key(val wireName: String) : PaneInput {
        ENTER("enter"), ESCAPE("escape"), UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"),
        BACKSPACE("backspace"), CTRL_O("ctrl-o"),
    }
}

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
enum class ConnectionError { UNREACHABLE, PROTOCOL }
class ConnectionFailure(val error: ConnectionError) : Exception(error.name)
enum class ChangeOutcome { APPLIED, TARGET_UNAVAILABLE, REJECTED, UNCERTAIN, RECONCILIATION_REQUIRED, NOT_CONNECTED }
enum class ChangeKind { OPEN_SHELL, PANE_INPUT, PI_DRAFT }

data class ConnectionState(
    val endpoint: Endpoint? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val snapshot: Snapshot? = null,
    val stale: Boolean = false,
    val error: ConnectionError? = null,
    val needsReconciliation: Boolean = false,
    val changeOutcome: ChangeOutcome? = null,
    val changeKind: ChangeKind = ChangeKind.OPEN_SHELL,
) {
    val shellTarget: SessionTarget?
        get() = (snapshot?.workspace as? Workspace.Ready)?.target
            ?.takeIf { status == ConnectionStatus.CONNECTED && !stale && !needsReconciliation }

    fun paneTarget(window: WorkspaceWindow): PaneTarget? {
        val workspace = snapshot?.workspace as? Workspace.Ready ?: return null
        val pane = window.paneId ?: return null
        if (status != ConnectionStatus.CONNECTED || stale || window !in workspace.windows) return null
        return PaneTarget(workspace.target, window.id, pane)
    }

    fun offersInput(target: PaneTarget, revision: String): Boolean =
        snapshot?.let { it.revision == revision && it.paneInputEnabled } == true && canRead(target)

    fun canRead(target: PaneTarget): Boolean {
        val workspace = snapshot?.workspace as? Workspace.Ready ?: return false
        return status == ConnectionStatus.CONNECTED && !stale && workspace.target == target.session &&
            workspace.windows.any { it.id == target.windowId && it.paneId == target.paneId }
    }
}

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

    fun attemptChange(send: () -> ChangeOutcome): ChangeOutcome = attemptChange(ChangeKind.OPEN_SHELL, send)

    /** Never retain the callback: only a fresh explicit action may retry after reconciliation. */
    fun attemptChange(kind: ChangeKind, send: () -> ChangeOutcome): ChangeOutcome {
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
                when (outcome) {
                    ChangeOutcome.UNCERTAIN -> {
                        // Reads begun before delivery became uncertain cannot reconcile it.
                        generation++
                        state = state.copy(needsReconciliation = true, stale = state.snapshot != null,
                            changeOutcome = outcome, changeKind = kind)
                    }
                    ChangeOutcome.APPLIED, ChangeOutcome.TARGET_UNAVAILABLE, ChangeOutcome.REJECTED -> {
                        if (kind == ChangeKind.OPEN_SHELL) {
                            state = state.copy(changeOutcome = outcome, changeKind = kind)
                        } else if (state.changeKind == kind) {
                            state = state.copy(changeOutcome = null)
                        }
                    }
                    ChangeOutcome.RECONCILIATION_REQUIRED, ChangeOutcome.NOT_CONNECTED -> Unit
                }
            }
        }
        return outcome
    }
}
