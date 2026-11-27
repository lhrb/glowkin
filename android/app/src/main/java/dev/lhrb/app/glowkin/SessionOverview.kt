package dev.lhrb.app.glowkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    mutating: Boolean,
    actionError: String?,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onOpenShell: () -> Unit,
    onSelectPane: (WorkspaceWindow, PaneTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.snapshot?.workspace
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (workspace is Workspace.Ready && !state.stale) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val count = workspace.windows.size
                        Text("Session: ${workspace.name}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (count == 1) "1 window" else "$count windows", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.stale) Text("Stale snapshot — not current PC state")
                val feedback = state.uncertainMessage ?: when (state.changeOutcome) {
                    ChangeOutcome.APPLIED -> "Shell window opened."
                    ChangeOutcome.TARGET_UNAVAILABLE -> "Could not open a shell: the listed tmux session is no longer available."
                    ChangeOutcome.REJECTED -> "The bridge rejected Open shell. Check the bridge version and settings."
                    else -> null
                }
                actionError?.let { Text(it) }
                feedback?.let { Text(it) }
                if (workspace != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefresh, enabled = !mutating) { Text("Refresh") }
                        if (workspace is Workspace.Ready) {
                            TextButton(onClick = onOpenShell, enabled = !mutating && state.shellTarget != null) {
                                Text("Open shell")
                            }
                        }
                    }
                }
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
                val description = when {
                    target != null -> "Terminal output"
                    window.paneId == null -> "No readable pane reported for this window."
                    else -> "Pane preview needs a current workspace snapshot."
                }
                ListItem(
                    headlineContent = { Text(window.name) },
                    supportingContent = { Text(description) },
                    modifier = if (target == null) Modifier else Modifier.clickable(role = Role.Button) {
                        onSelectPane(window, target)
                    },
                )
            }
        }
    }
}
