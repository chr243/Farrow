package com.verdroid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimal Markdown: fenced code blocks, GFM tables, #/##/### headings, bullets, **bold**, *italic*, `code`. */
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
                MarkdownTables.segments(b.text).forEach { seg ->
                    when (seg) {
                        is MdSegment.Lines -> seg.text.lines().forEach { line -> MarkdownLine(line, color) }
                        is MdSegment.Table -> MarkdownTableView(seg.table, color, Modifier.padding(vertical = 2.dp))
                    }
                }
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

/**
 * GFM table: horizontally scrollable grid with a header row (secondaryContainer, bold), zebra body rows, inline
 * Markdown in cells and per-column alignment. Columns are as wide as their widest cell (capped at [maxCellWidth],
 * longer cells wrap); every cell of a row gets the row's height so backgrounds line up.
 */
@Composable
fun MarkdownTableView(table: MdTable, color: Color, modifier: Modifier = Modifier, maxCellWidth: androidx.compose.ui.unit.Dp = 220.dp) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val codeBg = cs.surfaceContainerHighest
    val zebra = cs.surfaceContainerHighest.copy(alpha = 0.55f)
    val cols = table.columns
    val rows = listOf(table.header) + table.rows
    val maxPx = with(LocalDensity.current) { maxCellWidth.roundToPx() }
    Box(modifier.clip(shape).border(1.dp, cs.outlineVariant, shape).horizontalScroll(rememberScrollState())) {
        Layout(content = {
            rows.forEachIndexed { r, row ->
                for (c in 0 until cols) {
                    val header = r == 0
                    val bg = when { header -> cs.secondaryContainer; r % 2 == 0 -> zebra; else -> Color.Transparent }
                    val a = table.align.getOrElse(c) { MdAlign.START }
                    Box(Modifier.background(bg).padding(horizontal = 9.dp, vertical = 5.dp),
                        contentAlignment = when (a) { MdAlign.START -> Alignment.CenterStart; MdAlign.CENTER -> Alignment.Center; MdAlign.END -> Alignment.CenterEnd }) {
                        Text(inline(row.getOrElse(c) { "" }, codeBg),
                            color = if (header) cs.onSecondaryContainer else color,
                            fontWeight = if (header) FontWeight.Bold else null,
                            textAlign = when (a) { MdAlign.START -> TextAlign.Start; MdAlign.CENTER -> TextAlign.Center; MdAlign.END -> TextAlign.End },
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }) { measurables, _ ->
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m -> val c = i % cols; widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxPx)) }
            val heights = IntArray(rows.size)
            measurables.forEachIndexed { i, m -> val r = i / cols; heights[r] = maxOf(heights[r], m.minIntrinsicHeight(widths[i % cols])) }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until cols) { placeables[r * cols + c].place(x, y); x += widths[c] }
                    y += heights[r]
                }
            }
        }
    }
}
