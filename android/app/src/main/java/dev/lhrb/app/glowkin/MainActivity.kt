package dev.lhrb.app.glowkin

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.lhrb.app.glowkin.features.connection.ConnectionModule
import dev.lhrb.app.glowkin.features.connection.ConnectionViewModel
import dev.lhrb.app.glowkin.features.connection.PanePreview
import dev.lhrb.app.glowkin.features.connection.domain.*
import dev.lhrb.app.glowkin.ui.theme.GlowkinTheme

class MainActivity : ComponentActivity() {
    private lateinit var connection: ConnectionModule
    private var state by mutableStateOf(ConnectionState())
    private var storageError by mutableStateOf<String?>(null)
    private var mutating by mutableStateOf(false)
    private var preview by mutableStateOf(PanePreview())
    private var paneDraft by mutableStateOf("")
    private var previewProtected = false
    private var paneName by mutableStateOf<String?>(null)
    private var recovering by mutableStateOf(true)
    private var connectionRequested by mutableStateOf(false)
    private var reconnectRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectionRequested = savedInstanceState?.getBoolean("connectionRequested") == true
        connection = ViewModelProvider(this)[ConnectionViewModel::class.java].connection
        connection.attachActivity()
        setContent {
            GlowkinTheme {
                // Keep overview position while its composition is replaced by another page.
                val listState = rememberLazyListState()
                val showConnection = recovering || connectionRequested
                val canCloseConnection = connectionRequested && !recovering && state.status == ConnectionStatus.CONNECTED
                val selectedName = paneName
                val returnToOverview = {
                    if (paneName != null) leaveDetail() else closeConnectionPage()
                }
                BackHandler(enabled = selectedName != null || canCloseConnection, onBack = returnToOverview)
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    topBar = {
                        WorkspaceHeader(
                            title = if (showConnection) "PC bridge" else selectedName ?: "Workspace",
                            onBack = returnToOverview.takeIf { selectedName != null || canCloseConnection },
                            onConnection = if (showConnection) null else {
                                {
                                    if (paneName != null) leaveDetail()
                                    connectionRequested = true
                                }
                            },
                        )
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
                    when {
                        showConnection -> ConnectionScreen(
                            state, storageError, mutating,
                            onReplace = { host, port ->
                                connection.replace(host, port)
                                reconnectRequested = true
                            },
                            onRetry = {
                                reconnectRequested = true
                                connection.retry()
                            },
                            onDisconnect = {
                                reconnectRequested = false
                                recovering = true
                                connection.disconnect()
                            },
                            modifier = modifier,
                        )
                        selectedName != null -> {
                            val shown = state
                            val pane = preview
                            PaneDetail(
                                shown, pane, paneDraft, mutating,
                                onDraft = { draft ->
                                    if (pane.selection === preview.selection && preview.offersInput(state) && !mutating) {
                                        paneDraft = draft
                                    }
                                },
                                onInput = { input ->
                                    val chosen = pane.selection
                                    val currentDraft = input !is PaneInput.Text || input.value == paneDraft
                                    val accepted = chosen != null && currentDraft && connection.sendInput(shown, chosen, input)
                                    if (accepted && input is PaneInput.Text) paneDraft = ""
                                    accepted
                                },
                                modifier = modifier,
                            )
                        }
                        else -> SessionOverview(
                            state, mutating, storageError, listState,
                            onRefresh = connection::retry,
                            onOpenShell = { connection.openShell(state) },
                            onSelectPane = { window, target ->
                                if (connection.selectPane(state, target)) paneName = window.name
                            },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    private fun renderConnection(current: ConnectionState, error: String?, busy: Boolean, pane: PanePreview) {
        val offersInput = pane.offersInput(current)
        if ((offersInput || pane.text?.isNotBlank() == true) && !previewProtected) {
            // Protect drafts and previews before composition, even before the first pane read.
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewProtected = true
        }
        if (!offersInput || pane.selection !== preview.selection) paneDraft = ""
        state = current
        storageError = error
        mutating = busy
        preview = pane
        when {
            current.status == ConnectionStatus.CONNECTED -> {
                recovering = false
                if (reconnectRequested) closeConnectionPage()
            }
            current.error != null -> {
                recovering = true
                if (paneName != null) leaveDetail()
            }
        }
    }

    private fun closeConnectionPage() {
        connectionRequested = false
        reconnectRequested = false
    }

    private fun leaveDetail() {
        connection.clearPane()
        preview = PanePreview()
        paneDraft = ""
        paneName = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("connectionRequested", connectionRequested)
    }

    override fun onStart() { super.onStart(); connection.start(::renderConnection) }
    override fun onStop() { connection.stop(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceHeader(title: String, onBack: (() -> Unit)?, onConnection: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back to workspace")
                }
            }
        },
        actions = {
            if (onConnection != null) TextButton(onClick = onConnection) { Text("Connection") }
        },
    )
}

@Composable
private fun ConnectionScreen(
    state: ConnectionState,
    storageError: String?,
    mutating: Boolean,
    onReplace: (String, Int) -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8787") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.endpoint) {
        state.endpoint?.let { host = it.host; port = it.port.toString() }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (state.status) {
            ConnectionStatus.CONNECTING -> "Connecting…"
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.DISCONNECTED -> "Disconnected"
        }, style = MaterialTheme.typography.titleMedium)
        state.endpoint?.let { Text("Saved endpoint: ${it.host} · port ${it.port}") }
        val error = inputError ?: storageError ?: when (state.error) {
            ConnectionError.UNREACHABLE -> "Cannot reach the bridge. Check its address and port, PC server, firewall, and WLAN or Tailscale connection; then retry."
            ConnectionError.PROTOCOL -> "This endpoint is not a compatible v1 bridge. Check the port and update or restart the PC bridge; then retry."
            null -> null
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.uncertainMessage?.let { Text(it) }
        OutlinedTextField(host, { host = it; inputError = null }, label = { Text("Hostname or IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it; inputError = null }, label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try {
                onReplace(host, port.toIntOrNull() ?: throw InvalidEndpoint())
                inputError = null
            } catch (failure: InvalidEndpoint) { inputError = failure.message }
        }) { Text(if (state.endpoint == null) "Save and connect" else "Replace and connect") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry, enabled = state.endpoint != null && !mutating) { Text("Retry") }
            OutlinedButton(onClick = onDisconnect, enabled = state.endpoint != null) { Text("Disconnect") }
        }
        state.snapshot?.let {
            Text(if (state.stale) "Stale snapshot — not current PC state" else "Current bridge snapshot")
            Text("Bridge revision: ${it.revision}")
        }
        Text("Disconnection does not stop PC work or confirm whether a command succeeded.")
        Text("Unauthenticated HTTP PC control: use trusted networks only. Any reachable peer can read eligible panes on bridges that support pane reading. WLAN alone does not authorize peers. Restrict access with firewall or Tailscale policy; never expose publicly.")
    }
}

internal val ConnectionState.uncertainMessage: String?
    get() = if (changeOutcome != ChangeOutcome.UNCERTAIN) null else when (changeKind) {
        ChangeKind.OPEN_SHELL -> "Open shell may have succeeded. Check the PC windows before trying again."
        ChangeKind.PANE_INPUT -> "Input may have been injected. Check the pane before trying again."
    }
