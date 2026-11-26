package dev.lhrb.app.glowkin.features.connection

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.endpoint.SharedPreferencesEndpointStore
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.http.HttpSnapshotTransport
import dev.lhrb.app.glowkin.features.connection.domain.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Each selection is distinct, even A → B → A or across foreground epochs.
internal class PaneSelection(val target: PaneTarget, val revision: String) {
    var readStarted = false // Worker only; a poll already in progress may read first.

    fun matches(state: ConnectionState): Boolean = state.snapshot?.revision == revision && state.canRead(target)
}

internal data class PanePreview(
    val selection: PaneSelection? = null,
    val text: String? = null,
    val error: String? = null,
    val inputFeedback: String? = null,
) {
    fun offersInput(state: ConnectionState): Boolean =
        selection?.let { state.offersInput(it.target, it.revision) } == true
}

// Rotation must not start a second worker or forget an outstanding delivery. No draft or view is retained.
internal class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    val connection = ConnectionModule(application)
    override fun onCleared() { connection.close() }
}

/** One worker serializes persistence and HTTP. Epochs discard obsolete lifecycle/UI deliveries. */
internal class ConnectionModule(context: Context) {
    private var listener: ((ConnectionState, String?, Boolean, PanePreview) -> Unit)? = null
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var useCase: ConnectionUseCase? = null
    @Volatile private var epoch = 0L
    private var polling: ScheduledFuture<*>? = null
    private var foreground = false
    private var wanted = true
    private var mutating = false
    // A mutation reserves the idle HTTP worker before handing it any input. Reads try the same
    // reservation on the worker, so even already scheduled reads cannot jump ahead of it.
    private val network = Semaphore(1)
    @Volatile private var selection: PaneSelection? = null
    private var preview = PanePreview()
    private val transport = HttpSnapshotTransport()

    init {
        worker.execute {
            useCase = ConnectionUseCase(SharedPreferencesEndpointStore(preferences), transport)
        }
    }

    fun attachActivity() {
        wanted = true
        clearPane()
    }

    fun start(render: (ConnectionState, String?, Boolean, PanePreview) -> Unit) {
        listener = render
        foreground = true
        schedule()
    }

    fun stop() {
        foreground = false
        invalidate()
        listener = null
    }

    fun close() {
        stop()
        worker.shutdownNow()
    }

    fun disconnect() {
        wanted = false
        invalidate(forgetPane = true)
    }

    fun retry() {
        wanted = true
        schedule()
    }

    fun replace(host: String, port: Int) {
        // Validate before disturbing the currently configured connection.
        val endpoint = Endpoint.parse(host, port)
        wanted = true
        invalidate(forgetPane = true)
        val token = epoch
        worker.execute {
            if (token != epoch) return@execute
            try {
                useCase!!.saveEndpoint(endpoint.host, endpoint.port)
                publish(token)
                main.post { if (token == epoch && foreground) schedule() }
            } catch (failure: IllegalStateException) {
                publish(token, failure.message)
            }
        }
    }

    fun selectPane(shown: ConnectionState, target: PaneTarget): Boolean {
        val revision = shown.snapshot?.revision ?: return false
        val chosen = PaneSelection(target, revision)
        val current = useCase?.state ?: return false
        if (!foreground || !wanted || !chosen.matches(shown) || !chosen.matches(current) || current.endpoint != shown.endpoint) return false
        val token = epoch
        selection = chosen
        preview = PanePreview(chosen)
        render(shown, null, mutating, preview)
        worker.execute {
            readWhenIdle {
                if (!chosen.readStarted) readPreview(token, useCase!!.state, chosen)
            }
        }
        return true
    }

    fun clearPane() {
        selection = null
        preview = PanePreview()
    }

    fun openShell(shown: ConnectionState) {
        val endpoint = shown.endpoint ?: return
        val target = shown.shellTarget ?: return
        val revision = shown.snapshot?.revision ?: return
        val current = useCase?.state ?: return
        if (current.endpoint != endpoint || current.snapshot?.revision != revision || current.shellTarget != target) return
        val accepted = dispatch(ChangeKind.OPEN_SHELL) { fresh ->
            if (fresh.endpoint == endpoint && fresh.snapshot?.revision == revision && fresh.shellTarget == target) {
                transport.openShell(endpoint, target)
            } else ChangeOutcome.NOT_CONNECTED
        }
        if (!accepted && foreground && wanted && !mutating) display(current, "Bridge is busy. Try again.")
    }

    fun sendInput(shown: ConnectionState, chosen: PaneSelection, input: PaneInput): Boolean {
        val endpoint = shown.endpoint ?: return false
        if (selection !== chosen || !shown.offersInput(chosen.target, chosen.revision) || shown.needsReconciliation) return false
        val current = useCase?.state ?: return false
        if (current.endpoint != endpoint || !current.offersInput(chosen.target, chosen.revision) || current.needsReconciliation) return false
        val accepted = dispatch(ChangeKind.PANE_INPUT, chosen) { fresh ->
            if (selection !== chosen || fresh.endpoint != endpoint || !fresh.offersInput(chosen.target, chosen.revision)) {
                ChangeOutcome.NOT_CONNECTED
            } else {
                transport.sendInput(endpoint, chosen.target, input)
            }
        }
        if (!accepted && foreground && !mutating) {
            preview = preview.copy(inputFeedback = "Bridge is busy. Try again.")
            render(shown, null, false, preview)
        }
        return accepted
    }

    private fun dispatch(
        kind: ChangeKind,
        chosen: PaneSelection? = null,
        send: (ConnectionState) -> ChangeOutcome,
    ): Boolean {
        if (!foreground || !wanted || mutating || !network.tryAcquire()) return false
        val token = epoch
        val connection = useCase!!
        mutating = true
        if (chosen != null) preview = preview.copy(inputFeedback = null)
        render(connection.state, null, true, preview)
        worker.execute {
            val outcome = try {
                connection.attemptChange(kind) {
                    if (token != epoch) ChangeOutcome.NOT_CONNECTED else send(connection.state)
                }
            } finally {
                network.release()
            }
            val after = connection.state
            main.post {
                mutating = false
                if (token == epoch && chosen != null && selection === chosen) showInputOutcome(outcome)
                if (foreground) {
                    val current = if (token == epoch) after else connection.state
                    display(current, connecting = current.status == ConnectionStatus.CONNECTING)
                }
            }
            // No request is held for reconciliation. The next task starts a separate snapshot.
            if (token == epoch && (kind == ChangeKind.OPEN_SHELL || outcome == ChangeOutcome.UNCERTAIN)) {
                worker.execute { refresh(token) }
            }
        }
        return true
    }

    private fun showInputOutcome(outcome: ChangeOutcome) {
        val message = when (outcome) {
            ChangeOutcome.APPLIED -> "Input injected. Check the pane for its effect."
            ChangeOutcome.TARGET_UNAVAILABLE -> "Input rejected: the pane is no longer available for input."
            ChangeOutcome.REJECTED -> "The bridge rejected input. No input was injected."
            else -> return // Uncertainty is connection-wide; stale attempts have no detail result.
        }
        if (outcome == ChangeOutcome.TARGET_UNAVAILABLE) {
            selection = null
            preview = PanePreview(error = "Pane is no longer available. Select a window again.")
        }
        preview = preview.copy(inputFeedback = message)
    }

    private fun invalidate(forgetPane: Boolean = false) {
        epoch++
        polling?.cancel(false)
        polling = null
        if (forgetPane) clearPane() else {
            selection = selection?.let { PaneSelection(it.target, it.revision) }
            preview = PanePreview(selection)
        }
        useCase?.let {
            it.disconnect() // No I/O; invalidates even a currently blocked HTTP response.
            render(it.state, null, mutating, preview)
        }
    }

    private fun schedule() {
        invalidate()
        if (!foreground) return
        val token = epoch
        polling = worker.scheduleWithFixedDelay({ refresh(token) }, 0, 2, TimeUnit.SECONDS)
    }

    private inline fun readWhenIdle(read: () -> Unit) {
        if (!network.tryAcquire()) return
        try { read() } finally { network.release() }
    }

    private fun refresh(token: Long) = readWhenIdle {
        if (token != epoch) return
        val connection = useCase!!
        if (wanted && connection.state.endpoint != null) {
            if (connection.state.status != ConnectionStatus.CONNECTED) {
                publish(token, connecting = true)
                connection.connect()
            } else {
                connection.poll()
            }
        }
        // Stop may race the instant just before connect registers its read.
        // The worker is serial, so no newer request can be invalidated here.
        if (token != epoch) connection.disconnect()
        publish(token)
        selection?.let { readPreview(token, connection.state, it) }
    }

    private fun readPreview(token: Long, state: ConnectionState, chosen: PaneSelection) {
        val endpoint = state.endpoint ?: return
        if (!chosen.matches(state) || token != epoch || selection !== chosen) return
        chosen.readStarted = true
        val result = transport.readPane(endpoint, chosen.target)
        main.post {
            if (token != epoch || !foreground || selection !== chosen) return@post
            preview = when (result) {
                is PaneRead.Captured -> preview.copy(text = result.text, error = null)
                PaneRead.Failed -> preview.copy(text = null, error = "Cannot read this pane. Retrying while selected.")
                PaneRead.TargetUnavailable -> {
                    selection = null
                    PanePreview(error = "Pane is no longer available. Select a window again.")
                }
            }
            render(state, null, mutating, preview)
        }
    }

    private fun publish(token: Long, error: String? = null, connecting: Boolean = false) {
        val state = useCase!!.state.let {
            if (connecting) it.copy(status = ConnectionStatus.CONNECTING) else it
        }
        main.post {
            if (token == epoch && foreground) display(state, error, connecting)
        }
    }

    private fun render(state: ConnectionState, error: String?, busy: Boolean, pane: PanePreview) {
        listener?.invoke(state, error, busy, pane)
    }

    private fun display(state: ConnectionState, error: String? = null, connecting: Boolean = false) {
        if (state.needsReconciliation && state.stale && state.status == ConnectionStatus.CONNECTED) {
            // Uncertainty invalidates displayed pixels, not the target itself. Revalidate after the read.
            preview = PanePreview(selection)
        } else if (!connecting && selection?.let { !it.matches(state) } == true) {
            selection = null
            preview = PanePreview(error = "Pane is no longer available. Select a window again.")
        }
        render(state, error, mutating, preview)
    }
}
