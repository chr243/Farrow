package com.farrow.app.ui.components

/** Column alignment from a GFM separator cell (`:--`, `:-:`, `--:`). */
enum class MdAlign { START, CENTER, END }

/** A GitHub-flavoured Markdown table: [header] cells, per-column [align], body [rows] (padded to the column count). */
data class MdTable(val header: List<String>, val align: List<MdAlign>, val rows: List<List<String>>) {
    val columns: Int get() = header.size
}

/** A non-code Markdown block split into plain lines and tables. */
sealed interface MdSegment {
    data class Lines(val text: String) : MdSegment
    data class Table(val table: MdTable) : MdSegment
}

/** Pure GFM table parsing (unit-tested); rendering is [MarkdownTableView]. */
object MarkdownTables {
    private val SEP_CELL = Regex("^\\s*:?-+:?\\s*$")

    /**
     * Cells of one table row: an optional leading/trailing `|` is dropped, `\|` is a literal pipe, and a pipe inside
     * an inline `code span` doesn't split. Cells are trimmed.
     */
    fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cur.append('|'); i++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { cells += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        cells += cur.toString().trim()
        return cells
    }

    /** Alignments when [line] is a separator row (`| --- | :-: |`), else null. */
    fun separator(line: String): List<MdAlign>? {
        if (!line.contains('-')) return null
        val t = line.trim()
        // A lone "---" is a horizontal rule / setext heading, not a one-column table separator.
        if (!t.contains('|')) return null
        val cells = splitRow(t)
        if (cells.isEmpty() || cells.any { !SEP_CELL.matches(it) }) return null
        return cells.map { c ->
            val l = c.startsWith(":"); val r = c.endsWith(":")
            when { l && r -> MdAlign.CENTER; r -> MdAlign.END; else -> MdAlign.START }
        }
    }

    private fun isRow(line: String) = line.isNotBlank() && line.contains('|')

    /** Splits [text] (no fenced code inside) into plain-line runs and tables, in order. */
    fun segments(text: String): List<MdSegment> {
        val lines = text.lines()
        val out = mutableListOf<MdSegment>()
        val plain = mutableListOf<String>()
        fun flush() { if (plain.isNotEmpty()) { out += MdSegment.Lines(plain.joinToString("\n")); plain.clear() } }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val align = if (isRow(line) && i + 1 < lines.size) separator(lines[i + 1]) else null
            val header = align?.let { splitRow(line) }
            if (header != null && header.size == align.size) {
                flush()
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && isRow(lines[j]) && separator(lines[j]) == null) {
                    val cells = splitRow(lines[j])
                    rows += List(header.size) { k -> cells.getOrElse(k) { "" } }
                    j++
                }
                out += MdSegment.Table(MdTable(header, align, rows))
                i = j
            } else {
                plain += line
                i++
            }
        }
        flush()
        return out
    }
}
