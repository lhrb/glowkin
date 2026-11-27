package dev.lhrb.app.glowkin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// A text-only subset of Markdown. Unsupported syntax stays literal; nothing is interactive.
@Composable
internal fun PiChatMarkdown(text: String, modifier: Modifier) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Prose -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is MarkdownBlock.Code -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.language?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        Text(
                            text = block.text,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Prose(val text: AnnotatedString) : MarkdownBlock
    data class Code(val text: String, val language: String?) : MarkdownBlock
}

private val fenceStart = Regex("""^ {0,3}(`{3,}(?!`)|~{3,}(?!~))\s*(.*)$""")
private val heading = Regex("""^ {0,3}#{1,6}\s+(.*)$""")
private val bullet = Regex("""^(\s*)[-+*]\s+""")
private val inline = Regex("""(?<!`)`([^`\n]+)`(?!`)|\*\*([^*\n]+)\*\*|__([^_\n]+)__|\*([^*\n]+)\*|(?<!\w)_([^_\n]+)_(?!\w)""")

private fun markdownBlocks(text: String): List<MarkdownBlock> = buildList {
    val lines = text.lineSequence().iterator()
    val prose = mutableListOf<String>()
    while (lines.hasNext()) {
        val line = lines.next()
        val opening = fenceStart.matchEntire(line)
        val fence = opening?.groupValues?.get(1)
        val info = opening?.groupValues?.get(2).orEmpty()
        if (fence == null || (fence.first() == '`' && '`' in info)) {
            prose.add(line)
            continue
        }
        if (prose.any { it.isNotBlank() }) add(MarkdownBlock.Prose(proseText(prose)))
        prose.clear()
        val language = info.trim().takeWhile { !it.isWhitespace() }.ifEmpty { null }
        val code = mutableListOf<String>()
        while (lines.hasNext()) {
            val codeLine = lines.next()
            val indent = codeLine.indexOfFirst { it != ' ' }
            if (indent in 0..3) {
                val closing = codeLine.substring(indent).trimEnd()
                if (closing.length >= fence.length && closing.all { it == fence.first() }) break
            }
            code.add(codeLine)
        }
        add(MarkdownBlock.Code(code.joinToString("\n"), language))
    }
    if (prose.any { it.isNotBlank() }) add(MarkdownBlock.Prose(proseText(prose)))
}

private fun proseText(lines: List<String>): AnnotatedString = buildAnnotatedString {
    val first = lines.indexOfFirst { it.isNotBlank() }
    val last = lines.indexOfLast { it.isNotBlank() }
    for (index in first..last) {
        if (index > first) append('\n')
        val line = lines[index]
        val title = heading.matchEntire(line)
        if (title != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(title.groupValues[1]) }
        } else {
            val marker = bullet.find(line)
            if (marker != null) {
                append(marker.groupValues[1])
                append("• ")
                appendInline(line.substring(marker.range.last + 1))
            } else {
                appendInline(line)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var end = 0
    for (match in inline.findAll(text)) {
        append(text.substring(end, match.range.first))
        val groups = match.groupValues
        val style = when {
            groups[1].isNotEmpty() -> SpanStyle(fontFamily = FontFamily.Monospace)
            groups[2].isNotEmpty() || groups[3].isNotEmpty() -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }
        withStyle(style) { append(groups.drop(1).first { it.isNotEmpty() }) }
        end = match.range.last + 1
    }
    append(text.substring(end))
}
