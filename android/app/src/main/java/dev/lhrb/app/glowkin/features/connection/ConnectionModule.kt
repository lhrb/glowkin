package dev.lhrb.app.glowkin.features.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.endpoint.SharedPreferencesEndpointStore
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.http.HttpSnapshotTransport
import dev.lhrb.app.glowkin.features.connection.domain.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class PanePreview(val target: PaneTarget? = null, val text: String? = null, val error: String? = null)

/** One worker serializes persistence and HTTP. Epochs discard obsolete lifecycle/UI deliveries. */
class ConnectionModule(context: Context, private val render: (ConnectionState, String?, Boolean, PanePreview) -> Unit) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var useCase: ConnectionUseCase? = null
    @Volatile private var epoch = 0L
    private var polling: ScheduledFuture<*>? = null
    private var foreground = false
    private var wanted = true
    private var openingShell = false
    // Each selection is distinct, even A → B → A while the first A is in flight.
    private class Selection(val target: PaneTarget)
    @Volatile private var selection: Selection? = null
    private var preview = PanePreview()
    private val transport = HttpSnapshotTransport()

    init {
        worker.execute {
            useCase = ConnectionUseCase(SharedPreferencesEndpointStore(preferences), transport)
        }
    }

    fun start() {
        foreground = true
        schedule()
    }

    fun stop() {
        foreground = false
        invalidate()
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
        if (!foreground || !wanted || !shown.canRead(target)) return false
        selection = Selection(target)
        preview = PanePreview(target)
        render(shown, null, openingShell, preview)
        return true
    }

    fun clearPane() {
        selection = null
        preview = PanePreview()
    }

    fun openShell(shown: ConnectionState) {
        val endpoint = shown.endpoint ?: return
        val target = shown.shellTarget ?: return
        if (!foreground || !wanted || openingShell) return
        val token = epoch
        openingShell = true
        render(shown, null, true, preview)
        worker.execute {
            if (token != epoch) return@execute
            val connection = useCase!!
            connection.attemptChange {
                val current = connection.state
                when {
                    current.endpoint != endpoint || token != epoch -> ChangeOutcome.NOT_CONNECTED
                    current.shellTarget != target -> ChangeOutcome.TARGET_UNAVAILABLE
                    else -> transport.openShell(endpoint, target)
                }
            }
            if (token != epoch) return@execute
            publish(token)
            connection.poll()
            if (token != epoch) connection.disconnect()
            publish(token, finishedOpening = true)
        }
    }

    private fun invalidate(forgetPane: Boolean = false) {
        epoch++
        openingShell = false
        polling?.cancel(false)
        polling = null
        if (forgetPane) clearPane() else preview = PanePreview(selection?.target)
        useCase?.let {
            it.disconnect() // No I/O; invalidates even a currently blocked HTTP response.
            render(it.state, null, false, preview)
        }
    }

    private fun schedule() {
        invalidate()
        if (!foreground) return
        val token = epoch
        polling = worker.scheduleWithFixedDelay({
            if (token == epoch) {
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
                readPreview(token, connection.state)
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun readPreview(token: Long, state: ConnectionState) {
        val chosen = selection ?: return
        val endpoint = state.endpoint ?: return
        if (!state.canRead(chosen.target) || token != epoch || selection !== chosen) return
        val result = transport.readPane(endpoint, chosen.target)
        main.post {
            if (token != epoch || !foreground || selection !== chosen) return@post
            preview = when (result) {
                is PaneRead.Captured -> PanePreview(chosen.target, text = result.text)
                PaneRead.Failed -> PanePreview(chosen.target, error = "Cannot read this pane. Retrying while selected.")
                PaneRead.TargetUnavailable -> {
                    selection = null
                    PanePreview(error = "Pane is no longer available. Select a window again.")
                }
            }
            render(state, null, openingShell, preview)
        }
    }

    private fun publish(token: Long, error: String? = null, connecting: Boolean = false, finishedOpening: Boolean = false) {
        val state = useCase!!.state.let {
            if (connecting) it.copy(status = ConnectionStatus.CONNECTING) else it
        }
        main.post {
            if (token == epoch && foreground) {
                if (finishedOpening) openingShell = false
                if (!connecting && selection?.let { !state.canRead(it.target) } == true) {
                    selection = null
                    preview = PanePreview(error = "Pane is no longer available. Select a window again.")
                }
                render(state, error, openingShell, preview)
            }
        }
    }
}
