package com.farrow.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class MarkdownTablesTest {
    private fun tables(s: String) = MarkdownTables.segments(s).filterIsInstance<MdSegment.Table>().map { it.table }

    @Test fun `basic table between text`() {
        val segs = MarkdownTables.segments("Here:\n| Name | Price |\n|---|---|\n| A | 1 |\n| B | 2 |\nDone.")
        assertEquals(3, segs.size)
        assertEquals(MdSegment.Lines("Here:"), segs[0])
        val t = (segs[1] as MdSegment.Table).table
        assertEquals(listOf("Name", "Price"), t.header)
        assertEquals(listOf(listOf("A", "1"), listOf("B", "2")), t.rows)
        assertEquals(MdSegment.Lines("Done."), segs[2])
    }

    @Test fun `alignment from separator`() {
        assertEquals(listOf(MdAlign.START, MdAlign.CENTER, MdAlign.END, MdAlign.START), MarkdownTables.separator("|:--|:-:|--:|---|"))
        assertNull(MarkdownTables.separator("| a | b |"))
        assertNull(MarkdownTables.separator("---"))
    }

    @Test fun `escaped pipe and pipe inside code`() {
        assertEquals(listOf("a | b", "c"), MarkdownTables.splitRow("| a \\| b | c |"))
        assertEquals(listOf("`x|y`", "z"), MarkdownTables.splitRow("| `x|y` | z |"))
        assertEquals(listOf("a", "b"), MarkdownTables.splitRow("a | b"))
    }

    @Test fun `rows are padded or cut to the header width`() {
        val t = tables("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |").single()
        assertEquals(listOf(listOf("1", "", ""), listOf("1", "2", "3")), t.rows)
    }

    @Test fun `header without separator or lone rule is not a table`() {
        assertTrue(tables("a | b\nc | d").isEmpty())
        assertTrue(tables("text\n---\nmore").isEmpty())
        assertTrue(tables("| a | b |\n|---|").isEmpty()) // column count mismatch
    }

    @Test fun `table without body rows and inline markdown kept`() {
        val t = tables("| **Bold** | [l](https://x.y) |\n| --- | --- |").single()
        assertEquals(listOf("**Bold**", "[l](https://x.y)"), t.header); assertTrue(t.rows.isEmpty())
    }
}
