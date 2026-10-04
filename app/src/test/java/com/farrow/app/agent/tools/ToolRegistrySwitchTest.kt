package com.farrow.app.agent.tools

import com.farrow.app.data.tools.TermuxPackages
import com.farrow.app.data.tools.ToolEnv
import com.farrow.app.data.tools.ToolStatus
import com.farrow.app.data.tools.ToolSwitches
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
        assertFalse(ToolStatus.of("web_click", none).ready)
        assertTrue(ToolStatus.of("web_scrape", none).text.contains("fallback"))
        assertTrue(ToolStatus.of("run_shell", none).text.contains("Shizuku"))
        assertTrue(ToolStatus.of("run_shell", none.copy(shizukuReady = true)).ready)
        assertFalse(ToolStatus.of("screen_tap", none).ready)
        assertFalse(ToolStatus.of("termux_run", none).ready)
        assertTrue(ToolStatus.of("x_post", none.copy(bridgeUp = true, browserUp = true)).ready)
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

    @Test fun `bridge exec runs a command in the work dir`() {
        val python3 = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, "python3") }.firstOrNull { it.canExecute() }
        org.junit.Assume.assumeTrue(python3 != null)
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map { java.io.File(it) }.first { it.exists() }
        val home = kotlin.io.path.createTempDirectory("exec").toFile()
        val script = "import importlib.util, json, sys\nspec = importlib.util.spec_from_file_location('b', sys.argv[1]); b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)\n" +
            "print(json.dumps([b.cmd_exec({'command': 'echo hi; pwd'}), b.cmd_exec({'command': 'exit 3'}), b.cmd_exec({'command': ''})]))"
        val p = ProcessBuilder(python3!!.absolutePath, "-c", script, asset.absolutePath).apply { environment()["HOME"] = home.absolutePath }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        val arr = Json.parseToJsonElement(out.trim().lines().last()).jsonArray.map { it.jsonObject }
        assertTrue(arr[0]["ok"]!!.jsonPrimitive.boolean)
        assertTrue(arr[0]["stdout"]!!.jsonPrimitive.content.contains("hi\n") && arr[0]["stdout"]!!.jsonPrimitive.content.contains("farrow-work"))
        assertEquals(3, arr[1]["code"]!!.jsonPrimitive.int)
        assertEquals(2, arr[2]["code"]!!.jsonPrimitive.int)
    }
}
