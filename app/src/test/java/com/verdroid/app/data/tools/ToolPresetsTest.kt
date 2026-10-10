package com.verdroid.app.data.tools

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolPresetsTest {
    private class Echo(override val name: String) : AgentTool {
        override val description = "Echo"
        override val parameters = buildJsonObject { put("type", "object") }
        var calls = 0
        override suspend fun execute(args: JsonObject): String { calls++; return """{"ok":true}""" }
    }

    @Test fun `tools map to presets and uncategorized tools stay on`() {
        assertEquals(ToolPreset.WEB, ToolPreset.of("web_search"))
        assertEquals(ToolPreset.WEB, ToolPreset.of("selenium_open"))
        assertEquals(ToolPreset.FILES, ToolPreset.of("workspace_read"))
        assertEquals(ToolPreset.FILES, ToolPreset.of("pdf_merge"))
        assertEquals(ToolPreset.FILES, ToolPreset.of("ebook_translate"))
        assertEquals(ToolPreset.TERMUX, ToolPreset.of("termux_python"))
        assertEquals(ToolPreset.DEVICE, ToolPreset.of("run_shell"))
        assertEquals(ToolPreset.DEVICE, ToolPreset.of("screen_tap"))
        assertEquals(ToolPreset.DEVICE, ToolPreset.of("git_push"))
        listOf("memory_save", "skill_get", "chart", "crypto_ticker", "mcp__x__y").forEach {
            assertNull(it, ToolPreset.of(it)); assertTrue(ToolPreset.allowed(it, ToolPreset.entries.toSet()))
        }
    }

    @Test fun `all on by default and the prompt note only appears when something is off`() {
        assertTrue(ToolPreset.allowed("web_search", emptySet()))
        assertEquals("", ToolPreset.promptNote(emptySet()))
        val note = ToolPreset.promptNote(setOf(ToolPreset.DEVICE, ToolPreset.WEB))
        assertTrue(note, note.contains("Web, Device"))
        assertEquals(setOf(ToolPreset.TERMUX), ToolPreset.decode(ToolPreset.encode(setOf(ToolPreset.TERMUX)) + "BOGUS"))
    }

    @Test fun `registry hides and refuses tools of an off preset only in that chat`() = runTest {
        val web = Echo("web_search"); val mem = Echo("memory_save")
        val off = mutableMapOf(7L to setOf(ToolPreset.WEB))
        val reg = ToolRegistry(listOf(web, mem), chatFilter = { id, n -> ToolPreset.allowed(n, off[id].orEmpty()) })
        assertEquals(setOf("memory_save"), reg.namesFor(7))
        assertEquals(1, reg.schemas(7).size)
        assertEquals(setOf("web_search", "memory_save"), reg.namesFor(8))
        val r = reg.execute("web_search", "{}", 7)
        assertTrue(r.isError); assertTrue(r.json, r.json.contains("Web tool preset")); assertEquals(0, web.calls)
        assertFalse(reg.execute("web_search", "{}", 8).isError)
        off.clear()
        assertFalse(reg.execute("web_search", "{}", 7).isError)
    }
}
