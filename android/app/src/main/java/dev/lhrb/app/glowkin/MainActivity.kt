package dev.lhrb.app.glowkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lhrb.app.glowkin.features.connection.ConnectionModule
import dev.lhrb.app.glowkin.features.connection.domain.*
import dev.lhrb.app.glowkin.ui.theme.GlowkinTheme

class MainActivity : ComponentActivity() {
    private lateinit var connection: ConnectionModule
    private var state by mutableStateOf(ConnectionState())
    private var storageError by mutableStateOf<String?>(null)
    private var openingShell by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connection = ConnectionModule(applicationContext) { current, error, opening ->
            state = current
            storageError = error
            openingShell = opening
        }
        setContent {
            GlowkinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    ConnectionScreen(state, storageError, openingShell, connection, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onStart() { super.onStart(); connection.start() }
    override fun onStop() { connection.stop(); super.onStop() }
    override fun onDestroy() { connection.close(); super.onDestroy() }
}

@Composable
private fun ConnectionScreen(state: ConnectionState, storageError: String?, openingShell: Boolean, connection: ConnectionModule, modifier: Modifier) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8787") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.endpoint) {
        state.endpoint?.let { host = it.host; port = it.port.toString() }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PC bridge", style = MaterialTheme.typography.headlineMedium)
        Text(when (state.status) {
            ConnectionStatus.CONNECTING -> "Connecting…"
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.DISCONNECTED -> "Disconnected"
        }, style = MaterialTheme.typography.titleLarge)
        state.endpoint?.let { Text("Saved endpoint: ${it.host} · port ${it.port}") }
        OutlinedTextField(host, { host = it; inputError = null }, label = { Text("Hostname or IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it; inputError = null }, label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try {
                connection.replace(host, port.toIntOrNull() ?: throw InvalidEndpoint())
                inputError = null
            } catch (failure: InvalidEndpoint) { inputError = failure.message }
        }) { Text(if (state.endpoint == null) "Save and connect" else "Replace and connect") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = connection::retry, enabled = state.endpoint != null && !openingShell) { Text("Retry") }
            OutlinedButton(onClick = connection::disconnect, enabled = state.endpoint != null) { Text("Disconnect") }
        }
        val error = inputError ?: storageError ?: when (state.error) {
            ConnectionError.UNREACHABLE -> "Cannot reach the bridge. Check its address and port, PC server, firewall, and WLAN or Tailscale connection; then retry."
            ConnectionError.PROTOCOL -> "This endpoint is not a compatible v1 bridge. Check the port and update or restart the PC bridge; then retry."
            null -> null
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.snapshot?.let {
            Text(if (state.stale) "Stale snapshot — not current PC state" else "Current bridge snapshot")
            Text("Bridge revision: ${it.revision}")
        }
        if (state.snapshot != null) WorkspaceSection(state, openingShell, connection)
        Text("Disconnection does not stop PC work or confirm whether a command succeeded.")
        Text("Unauthenticated HTTP PC control: use trusted networks only. WLAN alone does not authorize peers. Restrict access with firewall or Tailscale policy; never expose publicly.")
    }
}

@Composable
private fun WorkspaceSection(state: ConnectionState, openingShell: Boolean, connection: ConnectionModule) {
    Text("tmux workspace", style = MaterialTheme.typography.titleLarge)
    val workspace = state.snapshot?.workspace
    if (workspace != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = connection::retry, enabled = !openingShell) { Text("Refresh") }
            if (workspace is Workspace.Ready) {
                Button(onClick = { connection.openShell(state) }, enabled = !openingShell && state.shellTarget != null) {
                    Text("Open shell")
                }
            }
        }
    }
    val feedback = when (state.changeOutcome) {
        ChangeOutcome.APPLIED -> "Shell window opened."
        ChangeOutcome.TARGET_UNAVAILABLE -> "Could not open a shell: the listed tmux session is no longer available."
        ChangeOutcome.REJECTED -> "The bridge rejected Open shell. Check the bridge version and settings."
        ChangeOutcome.UNCERTAIN -> "Open shell may have succeeded. Check the PC windows before trying again."
        else -> null
    }
    feedback?.let { Text(it) }
    when (workspace) {
        is Workspace.Ready -> {
            Text("Session: ${workspace.name}")
            workspace.windows.forEach { Text(it.name) }
        }
        Workspace.NoSession -> Text("Start a tmux session on the PC to see its windows here.")
        Workspace.MultipleSessions -> Text("Glowkin supports one tmux session at a time. Manage sessions on the PC.")
        Workspace.Unavailable -> Text("Cannot read tmux. Check that tmux and a session are running on the PC.")
        null -> Text("This bridge does not report tmux windows. Update the bridge to use workspace controls.")
    }
}
