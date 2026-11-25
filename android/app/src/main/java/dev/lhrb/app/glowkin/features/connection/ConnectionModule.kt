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
class ConnectionModule(context: Context, private val render: (ConnectionState, String?) -> Unit) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var useCase: ConnectionUseCase? = null
    @Volatile private var epoch = 0L
    private var polling: ScheduledFuture<*>? = null
    private var foreground = false
    private var wanted = true

    init {
        worker.execute {
            useCase = ConnectionUseCase(SharedPreferencesEndpointStore(preferences), HttpSnapshotTransport())
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
        useCase?.let { render(it.state, null) }
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

    private fun invalidate() {
        epoch++
        polling?.cancel(false)
        polling = null
        useCase?.disconnect() // No I/O; invalidates even a currently blocked HTTP response.
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

    private fun publish(token: Long, error: String? = null, connecting: Boolean = false) {
        val state = useCase!!.state.let {
            if (connecting) it.copy(status = ConnectionStatus.CONNECTING) else it
        }
        main.post { if (token == epoch && foreground) render(state, error) }
    }
}
