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

/** One worker serializes persistence and HTTP. Epochs discard obsolete lifecycle/UI deliveries. */
class ConnectionModule(context: Context, private val render: (ConnectionState, String?, Boolean) -> Unit) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var useCase: ConnectionUseCase? = null
    @Volatile private var epoch = 0L
    private var polling: ScheduledFuture<*>? = null
    private var foreground = false
    private var wanted = true
    private var openingShell = false
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
        invalidate()
    }

    fun retry() {
        wanted = true
        schedule()
    }

    fun replace(host: String, port: Int) {
        // Validate before disturbing the currently configured connection.
        val endpoint = Endpoint.parse(host, port)
        wanted = true
        invalidate()
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

    fun openShell(shown: ConnectionState) {
        val endpoint = shown.endpoint ?: return
        val target = shown.shellTarget ?: return
        if (!foreground || !wanted || openingShell) return
        val token = epoch
        openingShell = true
        render(shown, null, true)
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

    private fun invalidate() {
        epoch++
        openingShell = false
        polling?.cancel(false)
        polling = null
        useCase?.let {
            it.disconnect() // No I/O; invalidates even a currently blocked HTTP response.
            if (foreground) render(it.state, null, false)
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
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun publish(token: Long, error: String? = null, connecting: Boolean = false, finishedOpening: Boolean = false) {
        val state = useCase!!.state.let {
            if (connecting) it.copy(status = ConnectionStatus.CONNECTING) else it
        }
        main.post {
            if (token == epoch && foreground) {
                if (finishedOpening) openingShell = false
                render(state, error, openingShell)
            }
        }
    }
}
