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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    val windows = (workspace as? Workspace.Ready)?.windows.orEmpty()
    val sections = remember(windows) { windowSections(windows) }
    val grouped = sections.any { it.heading != null }
    // A malformed snapshot can repeat IDs. Keep every row with positional keys in that case.
    val rowKey: ((WorkspaceWindow) -> String)? = remember(windows) {
        if (windows.distinctBy { it.id }.size == windows.size) ({ it.id }) else null
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (workspace is Workspace.Ready && !state.stale && !grouped) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val count = windows.size
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
        sections.forEach { section ->
            section.heading?.let { label ->
                item {
                    Text(
                        label,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            items(section.windows, key = rowKey) { window ->
                val target = state.paneTarget(window)
                val description = when {
                    target != null -> if (grouped) null else "Terminal output"
                    window.paneId == null -> "No readable pane reported for this window."
                    else -> "Pane preview needs a current workspace snapshot."
                }
                ListItem(
                    headlineContent = { Text(window.name) },
                    supportingContent = description?.let { { Text(it) } },
                    modifier = if (target == null) Modifier else Modifier.clickable(role = Role.Button) {
                        onSelectPane(window, target)
                    },
                )
            }
        }
    }
}

private data class WindowSection(val heading: String?, val windows: List<WorkspaceWindow>)

private fun windowSections(windows: List<WorkspaceWindow>): List<WindowSection> {
    val grouped = windows.groupBy { it.folder?.key }.toMutableMap()
    val ungrouped = grouped.remove(null).orEmpty()
    if (grouped.isEmpty()) return listOf(WindowSection(null, ungrouped))

    // Keys belong to this snapshot, so order groups and suffixes by first appearance.
    val folders = grouped.values.map { it.first().folder!!.name to it }
    val nameCounts = folders.groupingBy { it.first }.eachCount()
    val usedLabels = nameCounts.keys.toMutableSet()
    val ungroupedLabel = "Ungrouped"
    if (ungrouped.isNotEmpty()) usedLabels.add(ungroupedLabel)
    val nextSuffix = mutableMapOf<String, Int>()
    return buildList {
        for ((name, rows) in folders) {
            var label = name
            if (nameCounts.getValue(name) > 1 || (ungrouped.isNotEmpty() && name == ungroupedLabel)) {
                var suffix = nextSuffix.getOrDefault(name, 1)
                do { label = "$name (${suffix++})" } while (!usedLabels.add(label))
                nextSuffix[name] = suffix
            }
            add(WindowSection(label, rows))
        }
        if (ungrouped.isNotEmpty()) add(WindowSection(ungroupedLabel, ungrouped))
    }
}
