package com.farrow.app.agent.tools

import com.farrow.app.data.storage.SharedFolder
import com.farrow.app.data.termux.FarrowSeleniumPy
import com.farrow.app.data.termux.TermuxResult
import com.farrow.app.data.termux.TermuxRunner
import com.farrow.app.data.tools.TermuxPackages
import com.farrow.app.data.tools.ToolEnv
import com.farrow.app.data.tools.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SeleniumToolsTest {
    private class FakeTermux(var answer: (String, String) -> TermuxResult?) : TermuxRunner {
        val commands = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            commands += command; return answer(command, tag)
        }
    }

    /** Runs the command with the local bash (HOME = temp dir), like Termux would. */
    private fun localBash(home: File, extraPath: String? = null) = FakeTermux { cmd, tag ->
        val pb = ProcessBuilder("bash", "-c", cmd).directory(home)
        pb.environment()["HOME"] = home.path
        pb.environment()["TMPDIR"] = File(home, "tmp").apply { mkdirs() }.path
        extraPath?.let { pb.environment()["PATH"] = it + ":" + pb.environment()["PATH"] }
        System.getenv("FARROW_LOCAL_PYTHONPATH")?.let { pb.environment()["PYTHONPATH"] = it }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText(); val err = p.errorStream.bufferedReader().readText()
        p.waitFor(180, TimeUnit.SECONDS)
        TermuxResult(tag, out, err, p.exitValue(), -1, null)
    }

    private lateinit var base: File
    private lateinit var folder: SharedFolder
    @Before fun setUp() { base = Files.createTempDirectory("farrow-sel").toFile(); folder = SharedFolder(File(base, "Documents/Farrow")) { true } }
    @After fun tearDown() { base.deleteRecursively() }
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun openBuildsHelperCommandAndParsesResult() = runTest {
        val t = FakeTermux { _, tag -> TermuxResult(tag, "noise\n${FarrowSeleniumPy.MARKER}{\"ok\":true,\"title\":\"T\",\"text\":\"hello\"}\n", "", 0, -1, null) }
        val r = obj(SeleniumOpenTool(t, folder).execute(buildJsonObject {
            put("url", "https://example.com/a?b='c'"); put("wait_for", "#main"); put("save_as", "page")
        }))
        assertEquals("T", r["title"]?.jsonPrimitive?.content)
        assertTrue(r.containsKey("untrusted"))
        val cmd = t.commands.single()
        assertTrue(cmd.startsWith("mkdir -p ~/.farrow && echo "))
        assertTrue(cmd.contains("python3 ~/.farrow/farrow_selenium.py 'open' 'https://example.com/a?b='\\''c'\\''' '--wait' '2' '--wait-for' '#main'"))
        assertTrue(cmd.contains("'--save' '${SharedFolder.DISPLAY_PATH}/Output/page.html'"))
        assertTrue(cmd.contains("termux-setup-storage"))
    }

    @Test fun rejectsBadUrlsAndPathEscapes() = runTest {
        val t = FakeTermux { _, _ -> error("must not run") }
        assertTrue(obj(SeleniumOpenTool(t, folder).execute(buildJsonObject { put("url", "file:///etc/passwd") })).containsKey("error"))
        assertTrue(obj(SeleniumScreenshotTool(t, folder).execute(buildJsonObject {
            put("url", "https://example.com"); put("save_as", "../../escape.png") })).containsKey("error"))
        assertTrue(obj(SeleniumPageSourceTool(t, folder).execute(buildJsonObject {
            put("url", "https://example.com"); put("save_as", "/data/data/com.farrow.app/x") })).containsKey("error"))
        assertTrue(t.commands.isEmpty())
    }

    @Test fun helperFailureIsReported() = runTest {
        val t = FakeTermux { _, tag -> TermuxResult(tag, "", "bash: python3: command not found", 127, -1, null) }
        val r = obj(SeleniumPageSourceTool(t, folder).execute(buildJsonObject { put("url", "https://example.com") }))
        assertEquals(false, r["ok"]?.jsonPrimitive?.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("chromium-selenium"))
    }

    @Test fun termuxPythonSavesScriptInPrivateWorkspaceAndShipsIt() = runTest {
        val sb = WorkspaceSandbox(File(base, "files/workspace"))
        val t = FakeTermux { _, tag -> TermuxResult(tag, "42\n", "", 0, -1, null) }
        val r = obj(TermuxPythonTool(t, sb).execute(buildJsonObject {
            put("path", "scrapers/s.py"); put("code", "print(6*7)"); putJsonArray("args") { add("a b") }
        }))
        assertEquals(0, r["exit_code"]?.jsonPrimitive?.int)
        assertEquals("print(6*7)", File(base, "files/workspace/scrapers/s.py").readText())
        val cmd = t.commands.single()
        assertTrue(cmd.contains("FARROW_OUTPUT=${SharedFolder.DISPLAY_PATH}/Output"))
        assertTrue(cmd.endsWith("python3 \"\${TMPDIR:-\$PREFIX/tmp}/farrow-scripts\"/s.py 'a b'"))
        assertTrue(obj(TermuxPythonTool(t, sb).execute(buildJsonObject { put("path", "../x.py"); put("code", "1") })).containsKey("error"))
        assertTrue(obj(TermuxPythonTool(t, sb).execute(buildJsonObject { put("path", "x.sh"); put("code", "1") })).containsKey("error"))
    }

    @Test fun packageAndStatusWiring() {
        val sel = TermuxPackages.ALL.first { it.pkg == "chromium-selenium" }
        val s = TermuxPackages.installScript(sel)
        assertTrue(s.contains("install x11-repo tur-repo") && s.contains("chromium") && s.contains("pip install -U selenium"))
        assertTrue(s.contains("import selenium") && s.contains("INSTALLED=1"))
        assertTrue(TermuxPackages.detectQuery().contains("STORAGE=1"))
        assertFalse(ToolStatus.of("selenium_open", ToolEnv()).ready)
        assertTrue(ToolStatus.of("termux_python", ToolEnv(termuxReady = true)).ready)
    }

    /** End-to-end with local bash + python3 (opt-in: FARROW_LOCAL_BASH=1; selenium parts need FARROW_LOCAL_CHROMIUM_PATH, optional FARROW_LOCAL_PYTHONPATH). */
    @Test fun localBashEndToEnd() = runTest {
        assumeTrue(System.getenv("FARROW_LOCAL_BASH") == "1")
        val home = File(base, "home").apply { mkdirs() }
        val sb = WorkspaceSandbox(File(base, "files/workspace"))
        val py = obj(TermuxPythonTool(localBash(home), sb).execute(buildJsonObject {
            put("path", "scrapers/env.py")
            put("code", "import os, sys, farrow_selenium\nprint(os.environ['FARROW_OUTPUT'], sys.argv[1:], os.getcwd())")
            putJsonArray("args") { add("x y"); add("it's") }
        }))
        assertEquals(py.toString(), 0, py["exit_code"]?.jsonPrimitive?.int)
        assertTrue(py.toString(), py["stdout"]!!.jsonPrimitive.content.contains("Output ['x y', \"it's\"] ${home.path}/farrow-work"))
        val chromium = System.getenv("FARROW_LOCAL_CHROMIUM_PATH") ?: return@runTest
        val open = obj(SeleniumOpenTool(localBash(home, chromium), folder).execute(buildJsonObject { put("url", "https://example.com") }))
        assertEquals(open.toString(), "Example Domain", open["title"]?.jsonPrimitive?.content)
        // Shared storage is not writable here, so saving reports the termux-setup-storage hint.
        val shot = obj(SeleniumScreenshotTool(localBash(home, chromium), folder).execute(buildJsonObject { put("url", "https://example.com") }))
        assertTrue(shot.toString(), shot["error"]!!.jsonPrimitive.content.contains("termux-setup-storage"))
    }
}
