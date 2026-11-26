package dev.lhrb.app.glowkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lhrb.app.glowkin.features.connection.domain.ChangeOutcome
import dev.lhrb.app.glowkin.features.connection.domain.ConnectionState
import dev.lhrb.app.glowkin.features.connection.domain.PaneTarget
import dev.lhrb.app.glowkin.features.connection.domain.Workspace
import dev.lhrb.app.glowkin.features.connection.domain.WorkspaceWindow

@Composable
internal fun SessionOverview(
    state: ConnectionState,
    openingShell: Boolean,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onOpenShell: () -> Unit,
    onSelectPane: (WorkspaceWindow, PaneTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.snapshot?.workspace
    Column(modifier.fillMaxSize()) {
        Text(
            "Session overview",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (workspace is Workspace.Ready) {
                        Text("Session: ${workspace.name}", style = MaterialTheme.typography.titleLarge)
                    }
                    if (state.stale) Text("Stale snapshot — not current PC state")
                    if (workspace != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onRefresh, enabled = !openingShell) { Text("Refresh") }
                            if (workspace is Workspace.Ready) {
                                Button(onClick = onOpenShell, enabled = !openingShell && state.shellTarget != null) {
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
                        is Workspace.Ready -> if (workspace.windows.isEmpty()) Text("No windows reported for this session.")
                        Workspace.NoSession -> Text("Start a tmux session on the PC to see its windows here.")
                        Workspace.MultipleSessions -> Text("Glowkin supports one tmux session at a time. Manage sessions on the PC.")
                        Workspace.Unavailable -> Text("Cannot read tmux. Check that tmux and a session are running on the PC.")
                        null -> Text(if (state.snapshot == null) {
                            "No workspace snapshot. Check Connection to reconnect."
                        } else {
                            "This bridge does not report tmux windows. Update the bridge to use workspace controls."
                        })
                    }
                }
            }
            if (workspace is Workspace.Ready) {
                val windows = workspace.windows
                // A malformed snapshot can repeat IDs. Keep every row with positional keys.
                val key: ((WorkspaceWindow) -> String)? = if (windows.distinctBy { it.id }.size == windows.size) {
                    { it.id }
                } else null
                items(windows, key = key) { window ->
                    val target = state.paneTarget(window)
                    val explanation = when {
                        target != null -> null
                        window.paneId == null -> "No readable pane reported for this window."
                        else -> "Pane preview needs a current workspace snapshot."
                    }
                    ListItem(
                        headlineContent = {
                            Text(window.name, color = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = explanation?.let { { Text(it) } },
                        modifier = if (target == null) Modifier else Modifier.clickable(role = Role.Button) {
                            onSelectPane(window, target)
                        },
                    )
                }
            }
        }
    }
}
