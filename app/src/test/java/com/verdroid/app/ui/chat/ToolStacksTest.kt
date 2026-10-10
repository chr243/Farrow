package com.verdroid.app.ui.chat

import com.verdroid.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ToolStacksTest {
    private fun msg(id: Long, role: MessageRole, content: String? = null) =
        ChatMessage(id, 1, role, content, MessageKind.NORMAL, null, null, null, null, false, null, id)
    private fun call(id: Long, mid: Long, name: String, st: ToolCallStatus = ToolCallStatus.SUCCESS) =
        ToolCallRecord(id, 1, mid, "c$id", name, "{}", "{}", st, "native", id, id)

    @Test fun `consecutive termux_run turns fold into one stack, others stay separate`() {
        val visible = listOf(
            msg(1, MessageRole.USER, "do it"),
            msg(2, MessageRole.ASSISTANT, "Let me check"),
            msg(3, MessageRole.ASSISTANT), msg(4, MessageRole.ASSISTANT), msg(5, MessageRole.ASSISTANT),
            msg(6, MessageRole.ASSISTANT), msg(7, MessageRole.ASSISTANT, "Done"),
        )
        val calls = listOf(
            call(10, 2, "termux_run"), call(11, 3, "termux_run"), call(12, 4, "termux_run", ToolCallStatus.ERROR),
            call(13, 4, "termux_run"), call(14, 5, "web_search"), call(15, 6, "termux_run"),
        ).groupBy { it.messageId }
        val rows = ToolStacks.rows(visible, calls)
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), rows.map { it.key })
        assertEquals(listOf(listOf(10L, 11L, 12L, 13L)), rows[1].groups.map { g -> g.map { it.id } })
        assertEquals(ToolCallStatus.ERROR, ToolStacks.status(rows[1].groups.single()))
        assertEquals(listOf(listOf(14L)), rows[2].groups.map { g -> g.map { it.id } }) // non-stackable tool breaks the run
        assertEquals(1, rows[3].groups.single().size)
        assertTrue(rows[4].groups.isEmpty())
    }

    @Test fun `non-stackable tools are never merged`() {
        val visible = listOf(msg(1, MessageRole.ASSISTANT), msg(2, MessageRole.ASSISTANT))
        val calls = listOf(call(1, 1, "web_search"), call(2, 1, "web_search"), call(3, 2, "web_search")).groupBy { it.messageId }
        val rows = ToolStacks.rows(visible, calls)
        assertEquals(2, rows.size); assertEquals(listOf(1, 1), rows[0].groups.map { it.size })
    }
}
