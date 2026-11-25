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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connection = ConnectionModule(applicationContext) { current, error ->
            state = current
            storageError = error
        }
        setContent {
            GlowkinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    ConnectionScreen(state, storageError, connection, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onStart() { super.onStart(); connection.start() }
    override fun onStop() { connection.stop(); super.onStop() }
    override fun onDestroy() { connection.close(); super.onDestroy() }
}

@Composable
private fun ConnectionScreen(state: ConnectionState, storageError: String?, connection: ConnectionModule, modifier: Modifier) {
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
            OutlinedButton(onClick = connection::retry, enabled = state.endpoint != null) { Text("Retry") }
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
        Text("Connection only: workspace and agent inventory is not available yet. Disconnection does not mean agents stopped or commands succeeded.")
        Text("Unauthenticated HTTP PC control: use trusted networks only. WLAN alone does not authorize peers. Restrict access with firewall or Tailscale policy; never expose publicly.")
    }
}
