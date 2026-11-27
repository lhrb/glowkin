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
internal class PaneSelection(val target: PaneTarget, val revision: String, route: PiPaneRoute = PiPaneRoute.Chat()) {
    var readStarted = false // Worker only; a poll already in progress may read first.
    @Volatile var route = route // Written by the network lease holder.

    fun matches(state: ConnectionState): Boolean = state.snapshot?.revision == revision && state.canRead(target)
}

internal data class PanePreview(
    val selection: PaneSelection? = null,
    val text: String? = null,
    val error: String? = null,
    val inputFeedback: String? = null,
    val chat: PiPaneRoute.Chat? = null,
    val draftEdit: PiDraftEdit? = null,
    val terminalControlsPending: Boolean = false,
) {
    val showsChat: Boolean get() = chat != null && !terminalControlsPending

    fun offersInput(state: ConnectionState): Boolean =
        chat == null && selection?.let { state.offersInput(it.target, it.revision) } == true

    fun editablePiDraft(state: ConnectionState): PiDraftPermit? =
        chat?.editableDraft?.takeIf { !state.needsReconciliation && selection?.matches(state) == true }
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
    private var pendingTerminalInput: PaneSelection? = null
    // A mutation reserves the idle HTTP worker before handing it any input. Reads try the same
    // reservation on the worker, so even already scheduled reads cannot jump ahead of it.
    private val network = Semaphore(1)
    @Volatile private var selection: PaneSelection? = null
    private var preview = PanePreview()
    private var draftDispatch: Runnable? = null
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
        // Preserve disabled controls only for the exact terminal whose input still owns the worker.
        val pending = pendingTerminalInput
        val showPendingControls = pending?.target == target && pending.revision == revision
        preview = PanePreview(chosen, chat = PiPaneRoute.Chat(), terminalControlsPending = showPendingControls)
        render(shown, null, mutating, preview)
        worker.execute {
            readWhenIdle {
                if (!chosen.readStarted) readPreview(token, useCase!!.state, chosen)
            }
        }
        return true
    }

    fun clearPane() {
        cancelDraftDispatch()
        selection = null
        preview = PanePreview()
    }

    private fun cancelDraftDispatch() {
        draftDispatch?.let(main::removeCallbacks)
        draftDispatch = null
    }

    fun editPiDraft(chosen: PaneSelection, shownPermit: PiDraftPermit, text: String) {
        val state = useCase?.state ?: return
        if (!foreground || !wanted || mutating || selection !== chosen ||
            preview.editablePiDraft(state) !== shownPermit ||
            (chosen.route as? PiPaneRoute.Chat)?.editableDraft !== shownPermit || !isValidPiDraft(text)) return
        cancelDraftDispatch()
        val edit = PiDraftEdit(shownPermit, text).takeIf { it.text != it.basis.text }
        preview = preview.copy(draftEdit = edit, inputFeedback = null)
        render(state, null, false, preview)
        if (edit != null) {
            val task = Runnable {
                draftDispatch = null
                commitPiDraft(chosen, edit)
            }
            draftDispatch = task
            main.postDelayed(task, 300)
        }
    }

    private fun commitPiDraft(chosen: PaneSelection, edit: PiDraftEdit) {
        val current = useCase?.state ?: return
        val endpoint = current.endpoint ?: return
        if (selection !== chosen || preview.draftEdit !== edit) return
        val accepted = dispatch(ChangeKind.PI_DRAFT, chosen, prepare = {
            val latest = chosen.route as? PiPaneRoute.Chat
            val fresh = useCase!!.state
            if (latest?.accepts(edit) != true || !chosen.matches(fresh) ||
                fresh.endpoint != endpoint || fresh.needsReconciliation) false
            else {
                val syncing = latest.copy(pause = PiChatPause.SYNCING)
                chosen.route = syncing
                preview = preview.copy(chat = syncing)
                true
            }
        }) { fresh ->
            if (selection !== chosen || fresh.endpoint != endpoint || !chosen.matches(fresh)) {
                ChangeOutcome.NOT_CONNECTED
            } else transport.editPiDraft(endpoint, chosen.target, chosen.revision, edit)
        }
        if (!accepted) {
            preview = preview.copy(draftEdit = null, inputFeedback = "Draft not updated. Check the current PC draft.")
            display(useCase!!.state)
        }
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
        if (selection !== chosen || chosen.route != PiPaneRoute.Terminal || preview.chat != null ||
            !shown.offersInput(chosen.target, chosen.revision) || shown.needsReconciliation) return false
        val current = useCase?.state ?: return false
        if (current.endpoint != endpoint || !current.offersInput(chosen.target, chosen.revision) || current.needsReconciliation) return false
        val accepted = dispatch(ChangeKind.PANE_INPUT, chosen, prepare = {
            pendingTerminalInput = chosen
            true
        }) { fresh ->
            if (selection !== chosen || chosen.route != PiPaneRoute.Terminal || fresh.endpoint != endpoint ||
                !fresh.offersInput(chosen.target, chosen.revision)) {
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
        prepare: () -> Boolean = { true },
        send: (ConnectionState) -> ChangeOutcome,
    ): Boolean {
        if (!foreground || !wanted || mutating || !network.tryAcquire()) return false
        if (!prepare()) {
            network.release()
            return false
        }
        val token = epoch
        val connection = useCase!!
        mutating = true
        if (chosen != null) preview = preview.copy(inputFeedback = null)
        render(connection.state, null, true, preview)
        worker.execute {
            val outcome = try {
                connection.attemptChange(kind) {
                    if (token != epoch) ChangeOutcome.NOT_CONNECTED else send(connection.state)
                }.also {
                    if (kind == ChangeKind.PI_DRAFT && chosen != null) {
                        val chat = chosen.route as? PiPaneRoute.Chat
                        if (chat != null) chosen.route = chat.afterWrite(it)
                    }
                }
            } finally {
                network.release()
            }
            val after = connection.state
            main.post {
                mutating = false
                if (kind == ChangeKind.PANE_INPUT) pendingTerminalInput = null
                if (token == epoch && chosen != null && selection === chosen) {
                    if (kind == ChangeKind.PI_DRAFT) showDraftOutcome(outcome) else showInputOutcome(outcome)
                }
                if (foreground) {
                    val current = if (token == epoch) after else connection.state
                    display(current, connecting = current.status == ConnectionStatus.CONNECTING)
                }
                if (token == epoch && chosen != null && selection === chosen && kind == ChangeKind.PI_DRAFT) {
                    // Read the exact Pi instance anew; a workspace snapshot alone cannot unlock draft edits.
                    worker.execute { refresh(token) }
                }
            }
            if (token == epoch && (kind == ChangeKind.OPEN_SHELL ||
                    (outcome == ChangeOutcome.UNCERTAIN && kind != ChangeKind.PI_DRAFT))) {
                worker.execute { refresh(token) }
            }
        }
        return true
    }

    private fun showDraftOutcome(outcome: ChangeOutcome) {
        val chat = preview.chat ?: return
        preview = preview.copy(chat = chat.afterWrite(outcome),
            draftEdit = preview.draftEdit.takeIf { outcome == ChangeOutcome.APPLIED })
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
        pendingTerminalInput = null
        polling?.cancel(false)
        polling = null
        if (forgetPane) clearPane() else {
            cancelDraftDispatch()
            selection = selection?.let {
                val route = when (val previous = it.route) {
                    PiPaneRoute.Terminal -> previous
                    is PiPaneRoute.Chat -> PiPaneRoute.Chat(previous.instanceId)
                }
                PaneSelection(it.target, it.revision, route)
            }
            preview = PanePreview(selection, chat = selection?.route as? PiPaneRoute.Chat)
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
        if (token != epoch || selection !== chosen) return
        val route = chosen.route
        if (!chosen.matches(state)) {
            if (route is PiPaneRoute.Chat) {
                val pause = if (route.pause == PiChatPause.UNCERTAIN) route.pause else PiChatPause.UNAVAILABLE
                chosen.route = PiPaneRoute.Chat(route.instanceId, pause = pause)
            }
            return
        }
        chosen.readStarted = true
        if (route is PiPaneRoute.Chat) {
            val result = transport.readPiChat(endpoint, chosen.target, route.instanceId)
            if (token != epoch || selection !== chosen) return
            val next = route.afterRead(result)
            chosen.route = next
            if (next is PiPaneRoute.Chat) {
                main.post {
                    if (token != epoch || !foreground || selection !== chosen || chosen.route !== next) return@post
                    val previous = preview.draftEdit
                    val pending = previous?.takeIf(next::accepts)
                    if (pending == null) cancelDraftDispatch()
                    val feedback = if (previous != null && pending == null && next.editableDraft != null &&
                        preview.chat?.pause != PiChatPause.SYNCING) {
                        "Phone edits were discarded. Review the current PC draft."
                    } else null
                    preview = preview.copy(chat = next, draftEdit = pending, inputFeedback = feedback,
                        terminalControlsPending = false)
                    render(state, null, mutating, preview)
                }
                return
            }
            main.post {
                if (token != epoch || !foreground || selection !== chosen) return@post
                cancelDraftDispatch()
                preview = PanePreview(chosen)
                render(state, null, mutating, preview)
            }
        }
        if (token != epoch || selection !== chosen) return
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
        val chat = preview.chat
        if (chat != null) {
            val chosen = preview.selection
            if (chosen?.matches(state) != true || state.needsReconciliation) {
                cancelDraftDispatch()
                val pause = if (state.needsReconciliation) PiChatPause.UNCERTAIN else PiChatPause.UNAVAILABLE
                preview = preview.copy(chat = PiPaneRoute.Chat(chat.instanceId, pause = pause), draftEdit = null)
            }
        } else if (state.needsReconciliation && state.stale && state.status == ConnectionStatus.CONNECTED) {
            // Uncertainty invalidates displayed pixels, not the target itself. Revalidate after the read.
            preview = PanePreview(selection)
        } else if (!connecting && selection?.let { !it.matches(state) } == true) {
            selection = null
            preview = PanePreview(error = "Pane is no longer available. Select a window again.")
        }
        render(state, error, mutating, preview)
    }
}
