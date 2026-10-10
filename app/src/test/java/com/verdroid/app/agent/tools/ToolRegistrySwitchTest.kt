package com.verdroid.app.agent.tools

import com.verdroid.app.data.tools.TermuxPackages
import com.verdroid.app.data.tools.ToolEnv
import com.verdroid.app.data.tools.ToolStatus
import com.verdroid.app.data.tools.ToolSwitches
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolRegistrySwitchTest {
    private class Echo(override val name: String) : AgentTool {
        override val description = "Echo tool. Second sentence."
        override val parameters = schema(emptyList())
        var calls = 0
        override suspend fun execute(args: JsonObject): String { calls++; return """{"ok":true}""" }
    }

    @Test fun `disabled tools are hidden from the model and refused when called`() = runTest {
        val a = Echo("a"); val b = Echo("b")
        val off = mutableSetOf("b")
        val reg = ToolRegistry(listOf(a, b), ToolSwitches { it !in off })
        assertEquals(setOf("a"), reg.names)
        assertEquals(listOf("a"), reg.schemas().map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        val r = reg.execute("b", "{}")
        assertTrue(r.isError); assertTrue(r.json, r.json.contains("turned off")); assertEquals(0, b.calls)
        assertFalse(reg.execute("a", "{}").isError); assertEquals(1, a.calls)
        off.clear() // toggles apply immediately
        assertEquals(setOf("a", "b"), reg.names)
        assertFalse(reg.execute("b", "{}").isError)
    }

    @Test fun `status says ready or what is missing`() {
        val none = ToolEnv()
        assertTrue(ToolStatus.of("read_file", none).ready)
        assertTrue(ToolStatus.of("web_fetch", none).ready)
        assertTrue(ToolStatus.of("run_shell", none).text.contains("Shizuku"))
        assertTrue(ToolStatus.of("run_shell", none.copy(shizukuReady = true)).ready)
        assertFalse(ToolStatus.of("screen_tap", none).ready)
        assertTrue(ToolStatus.of("crypto_ticker", none).ready)
        assertFalse(ToolStatus.of("termux_run", none).ready)
        assertTrue(ToolStatus.of("termux_run", none.copy(termuxReady = true)).ready)
        assertEquals("Echo tool.", ToolStatus.short("Echo tool. Second sentence."))
    }

    @Test fun `package detection query and parsing`() {
        val q = TermuxPackages.detectQuery()
        assertTrue(q.contains("command -v yt-dlp") && q.contains("P_yt_dlp") && q.endsWith("echo PROBE=ok"))
        val parsed = TermuxPackages.parseDetect("P_ffmpeg=1\nP_imagemagick=0\nP_yt_dlp=1\nPROBE=ok\n")!!
        assertEquals(true, parsed["ffmpeg"]); assertEquals(false, parsed["imagemagick"]); assertEquals(true, parsed["yt-dlp"]); assertEquals(false, parsed["jq"])
        assertNull(TermuxPackages.parseDetect(""))
        val s = TermuxPackages.installScript(TermuxPackages.ALL.first { it.pkg == "jq" })
        assertTrue(s.contains("install jq") && s.contains("INSTALLED=") && s.contains("--force-confold"))
    }
}
