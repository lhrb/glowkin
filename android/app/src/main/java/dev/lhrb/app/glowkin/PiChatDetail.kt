package dev.lhrb.app.glowkin

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lhrb.app.glowkin.ui.theme.GlowkinTheme

internal data class PiChatMessage(val id: String, val role: PiChatRole, val text: String, val tool: String? = null)

internal enum class PiChatRole { USER, ASSISTANT, TOOL }

@Composable
internal fun PiChatDetail(
    messages: List<PiChatMessage>,
    subagents: List<String>?,
    draft: String?,
    canEdit: Boolean,
    notice: String?,
    queued: Boolean,
    onDraftChange: (String) -> Unit,
    modifier: Modifier,
) {
    val runningSubagents = subagents.orEmpty()
    // A zero-height end marker lets even a tall final entry scroll fully into view.
    val endIndex = maxOf(messages.size, 1) + if (runningSubagents.isEmpty()) 0 else 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = endIndex)
    val followUpdate = remember(messages, runningSubagents) {
        // Sample the previous layout before the updated conversation is measured.
        Snapshot.withoutReadObservation { !listState.canScrollForward }
    }
    LaunchedEffect(messages, runningSubagents) {
        if (followUpdate) listState.requestScrollToItem(endIndex)
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (messages.isEmpty()) {
                item { Text("No messages to show.", style = MaterialTheme.typography.bodyMedium) }
            }
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                Box(Modifier.padding(top = if (index == 0) 0.dp else 24.dp)) {
                    when (message.role) {
                        PiChatRole.USER -> PiUserBubble(message.text)
                        PiChatRole.ASSISTANT -> Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        PiChatRole.TOOL -> PiToolResult(message)
                    }
                }
            }
            if (runningSubagents.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Running subagents", style = MaterialTheme.typography.titleSmall)
                        runningSubagents.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            item { Spacer(Modifier.height(0.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            notice?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (queued) {
                Text("Work queued on PC", style = MaterialTheme.typography.bodySmall)
            }
            if (draft == null) {
                Text("Draft unavailable. Send on PC.", style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (canEdit) onDraftChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = !canEdit,
                    label = { Text("Draft") },
                    supportingText = { Text(if (canEdit) "Send on PC" else "Read-only draft. Send on PC.") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.None,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PiUserBubble(text: String) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth * 0.8f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PiToolResult(message: PiChatMessage, initiallyExpanded: Boolean = false) {
    var expanded by remember(message.id) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message.tool?.let { "Tool result: $it" } ?: "Tool result", style = MaterialTheme.typography.titleSmall)
        if (expanded) PiChatMarkdown(message.text, Modifier.fillMaxWidth())
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Collapse" else "Expand")
        }
    }
}

private val demoToolResult = PiChatMessage(
    "tool", PiChatRole.TOOL,
    """
        ### Export location
        The **daily summary** is generated by `make report`.

        ```kotlin
        fun export() {
            val path = "reports/daily/a-long-file-name-that-scrolls-within-this-block.txt"
            println(path)
        }
        ```
    """.trimIndent(),
    "Read file",
)

@Preview(name = "Conversation", widthDp = 400, heightDp = 720)
@Preview(name = "Narrow, large text, dark", widthDp = 320, heightDp = 640, fontScale = 1.4f,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PiChatDetailPreview() {
    GlowkinTheme(dynamicColor = false) {
        Surface {
            PiChatDetail(
                messages = listOf(
                    PiChatMessage("user", PiChatRole.USER, "Where is the daily export?"),
                    PiChatMessage("assistant", PiChatRole.ASSISTANT,
                        "I’ll check the reporting configuration and see where it writes the daily summary."),
                    demoToolResult,
                ),
                subagents = listOf("Report configuration check"),
                draft = "Also check yesterday’s report.\nUse the local archive.",
                canEdit = true,
                notice = null,
                queued = true,
                onDraftChange = {},
                modifier = Modifier,
            )
        }
    }
}

@Preview(name = "Expanded tool result", widthDp = 320)
@Composable
private fun PiExpandedToolPreview() {
    GlowkinTheme(dynamicColor = false) {
        Surface {
            Box(Modifier.padding(16.dp)) {
                PiToolResult(demoToolResult, initiallyExpanded = true)
            }
        }
    }
}
