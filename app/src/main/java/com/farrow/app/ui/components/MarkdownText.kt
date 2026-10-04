package com.farrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimal Markdown: fenced code blocks, #/##/### headings, bullets, **bold**, *italic*, `code`. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = splitBlocks(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            if (b.isCode) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)).horizontalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Text(CodeHighlighter.highlight(b.text, b.lang, CodeColors.of(MaterialTheme.colorScheme)), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
                }
            } else {
                b.text.lines().forEach { line -> MarkdownLine(line, color) }
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String, color: Color) {
    val trimmed = line.trimStart()
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    fun inline(t: String) = inline(t, bg)
    when {
        trimmed.startsWith("### ") -> Text(inline(trimmed.removePrefix("### ")), color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        trimmed.startsWith("## ") -> Text(inline(trimmed.removePrefix("## ")), color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        trimmed.startsWith("# ") -> Text(inline(trimmed.removePrefix("# ")), color = color, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(inline("•  " + trimmed.drop(2)), color = color, style = MaterialTheme.typography.bodyLarge)
        else -> Text(inline(line), color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class Block(val text: String, val isCode: Boolean, val lang: String?)

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val regex = Regex("```([\\w-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```")
    var last = 0
    for (m in regex.findAll(text)) {
        val before = text.substring(last, m.range.first).trim('\n')
        if (before.isNotBlank()) out += Block(before, false, null)
        out += Block(m.groupValues[2].trimEnd(), true, m.groupValues[1].ifBlank { null })
        last = m.range.last + 1
    }
    val rest = text.substring(last).trim('\n')
    if (rest.isNotBlank() || out.isEmpty()) out += Block(rest, false, null)
    return out
}

private val inlineRegex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

fun inline(text: String, codeBg: Color = Color(0x14000000)): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(text)) {
        append(text.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groupValues[2]) }
            m.groups[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
