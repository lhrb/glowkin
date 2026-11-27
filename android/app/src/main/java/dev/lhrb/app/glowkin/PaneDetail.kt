package dev.lhrb.app.glowkin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lhrb.app.glowkin.features.connection.PanePreview
import dev.lhrb.app.glowkin.features.connection.domain.ConnectionState
import dev.lhrb.app.glowkin.features.connection.domain.PaneInput
import dev.lhrb.app.glowkin.features.connection.domain.PaneInput.Key

@Composable
internal fun PaneDetail(
    previewState: ConnectionState,
    preview: PanePreview,
    draft: String,
    mutating: Boolean,
    onDraft: (String) -> Unit,
    onInput: (PaneInput) -> Boolean,
    modifier: Modifier,
) {
    val offersInput = preview.offersInput(previewState)
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val controlsHeight = maxHeight * 0.68f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Terminal output", style = MaterialTheme.typography.titleMedium)
            Text(when {
                previewState.needsReconciliation -> "Input paused until a fresh snapshot arrives."
                offersInput -> "Pane input enabled"
                else -> "Read-only pane"
            }, style = MaterialTheme.typography.titleSmall)
            (preview.inputFeedback ?: previewState.uncertainMessage)?.let { Text(it) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val text = preview.text
                when {
                    preview.error != null -> Text(preview.error)
                    previewState.needsReconciliation -> Unit
                    text == null -> Text("Reading pane…")
                    text.isBlank() -> Text("Pane is blank.")
                    else -> Text(text, fontFamily = FontFamily.Monospace, softWrap = false,
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            }
            if (offersInput) {
                PaneControls(draft, !mutating && !previewState.needsReconciliation, onDraft, onInput,
                    Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun PaneControls(
    draft: String,
    enabled: Boolean,
    onDraft: (String) -> Unit,
    onInput: (PaneInput) -> Boolean,
    modifier: Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = PaneInput.Text.isValid(draft)
    Column(modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 256) onDraft(it) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("Text to insert") },
            singleLine = true,
            isError = draft.isNotEmpty() && !valid,
            supportingText = if (draft.isNotEmpty() && !valid) {
                { Text("Use one line of up to 256 UTF-8 bytes without control characters.") }
            } else null,
            // Request a keyboard without suggestions; keep the draft visible for review and opt out of autofill.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
                imeAction = ImeAction.Done),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = enabled && valid, onClick = {
                if (onInput(PaneInput.Text(draft))) keyboard?.hide()
            }) { Text("Insert text") }
            Button(enabled = enabled, onClick = { onInput(Key.ENTER) }) { Text("Enter") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaneKey(Key.ESCAPE, "Escape", enabled, onInput)
            PaneKey(Key.BACKSPACE, "Backspace", enabled, onInput)
            PaneKey(Key.CTRL_O, "Ctrl+O", enabled, onInput)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaneKey(Key.UP, "↑", enabled, onInput, "Up")
            PaneKey(Key.DOWN, "↓", enabled, onInput, "Down")
            PaneKey(Key.LEFT, "←", enabled, onInput, "Left")
            PaneKey(Key.RIGHT, "→", enabled, onInput, "Right")
        }
        Text("Actions go to the pane’s current input, not a specific prompt. Enter can submit PC-typed text. Don’t enter secrets.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PaneKey(key: Key, label: String, enabled: Boolean, onInput: (PaneInput) -> Boolean, description: String? = null) {
    val modifier = if (description == null) Modifier else Modifier.semantics { contentDescription = description }
    OutlinedButton(modifier = modifier, enabled = enabled, onClick = { onInput(key) }) { Text(label) }
}
