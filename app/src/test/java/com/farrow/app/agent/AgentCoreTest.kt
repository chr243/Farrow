package com.farrow.app.agent

import com.farrow.app.agent.context.ContextPolicy
import com.farrow.app.agent.tools.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSandboxTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `resolves paths inside the workspace`() {
        val sb = WorkspaceSandbox(File(tmp.root, "workspace"))
        assertEquals(File(sb.root, "a/b.txt"), sb.resolve("a/b.txt"))
        assertEquals(sb.root, sb.resolve("."))
        assertEquals(sb.root, sb.resolve(""))
        // absolute paths are re-rooted into the workspace
        assertEquals(File(sb.root, "etc/passwd"), sb.resolve("/etc/passwd"))
        assertEquals(File(sb.root, "x.txt"), sb.resolve("a/../x.txt"))
    }

    @Test fun `blocks path traversal`() {
        val sb = WorkspaceSandbox(File(tmp.root, "workspace"))
        listOf("../secret", "../../etc/passwd", "a/../../x", "..\\evil", "..").forEach { p ->
            assertThrows(SecurityException::class.java) { sb.resolve(p) }
        }
        // sibling dir with the same prefix must not pass
        File(tmp.root, "workspace2").mkdirs()
        assertThrows(SecurityException::class.java) { sb.resolve("../workspace2/f") }
    }

    @Test fun `file tools work and reject escapes`() = runTest {
        val sb = WorkspaceSandbox(File(tmp.root, "workspace"))
        val reg = ToolRegistry(listOf(ReadFileTool(sb), WriteFileTool(sb), ListDirTool(sb)) + StubTool.all())
        assertFalse(reg.execute("write_file", """{"path":"notes/a.txt","content":"hello"}""").isError)
        val read = reg.execute("read_file", """{"path":"notes/a.txt"}""")
        assertTrue(read.json.contains("hello"))
        assertTrue(reg.execute("list_dir", """{"path":"notes"}""").json.contains("a.txt"))
        assertTrue(reg.execute("read_file", """{"path":"../../etc/passwd"}""").isError)
        val unknown = reg.execute("no_such_tool", "{}")
        assertTrue(unknown.isError)
        assertTrue(unknown.json.contains("Unknown tool"))
        assertEquals(3 + StubTool.all().size, reg.schemas().size)
    }
}

class ContextPolicyTest {
    @Test fun `token estimate is chars over four`() {
        assertEquals(0, ContextPolicy.estimateTokens(""))
        assertEquals(1, ContextPolicy.estimateTokens("abcd"))
        assertEquals(250, ContextPolicy.estimateTokens("x".repeat(1000)))
    }

    @Test fun `summarization triggers above 60 percent of budget`() {
        assertFalse(ContextPolicy.shouldSummarize(9_600, 16_000))
        assertTrue(ContextPolicy.shouldSummarize(9_601, 16_000))
    }

    @Test fun `selection keeps recent turns and never starts the kept window with a tool result`() {
        val items = (1L..10L).map { ContextPolicy.Item(it, isTool = it == 5L, tokens = 100) }
        // keepRecent=6 -> boundary index 4 (id 5) is a tool result -> move back to id 4
        assertEquals(listOf(1L, 2L, 3L), ContextPolicy.selectForSummary(items, keepRecent = 6))
        assertTrue(ContextPolicy.selectForSummary(items.take(5), keepRecent = 6).isEmpty())
    }
}

class FencedToolCallParserTest {
    private val known = setOf("write_file", "read_file", "list_dir")

    @Test fun `parses fenced json tool call`() {
        val content = "I'll create it.\n```json\n{\"tool\": \"write_file\", \"arguments\": {\"path\": \"a.txt\", \"content\": \"hi\"}}\n```"
        val calls = FencedToolCallParser.parse(content, known)
        assertEquals(1, calls.size)
        assertEquals("write_file", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("a.txt"))
    }

    @Test fun `accepts openai style function object and ignores unknown tools`() {
        val c = "```\n{\"function\": {\"name\": \"list_dir\", \"arguments\": \"{\\\"path\\\": \\\".\\\"}\"}}\n```"
        assertEquals("list_dir", FencedToolCallParser.parse(c, known).single().name)
        assertTrue(FencedToolCallParser.parse("```json\n{\"tool\":\"rm_rf\",\"arguments\":{}}\n```", known).isEmpty())
        assertTrue(FencedToolCallParser.parse("plain answer", known).isEmpty())
    }
}
