package com.verdroid.app.agent.tools

import com.verdroid.app.data.ebook.EbookTranslatePy
import com.verdroid.app.data.pdf.VerdroidPdfPy
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallConsentTest {
    private lateinit var base: File
    private lateinit var folder: SharedFolder

    @Before fun setUp() {
        base = Files.createTempDirectory("consent").toFile()
        folder = SharedFolder(File(base, "Documents/Verdroid")) { true }
        folder.ensure()
        File(folder.input, "a.pdf").writeBytes(ByteArray(8))
        File(folder.input, "book.mobi").writeText("x")
    }
    @After fun tearDown() { base.deleteRecursively() }

    private class FakeTermux(var answer: (String) -> TermuxResult?) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command; return answer(command)
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun specs(vararg s: String) = s.map { PackageSpec(it.substringBefore(':'), it.substringAfter(':')) }
    private fun names(cmd: String) = InstallConsent.detectShell(cmd).map { it.toString() }

    @Test fun detectsShellInstalls() {
        assertEquals(listOf("pip:requests", "pip:bs4"), names("pip install -U requests bs4"))
        assertEquals(listOf("pip:pymupdf"), names("cd x && python3 -m pip install --user pymupdf 2>&1 | tail -n 5"))
        assertEquals(listOf("pip:requirements:req.txt"), names("pip3 install -r req.txt"))
        assertEquals(listOf("pip:x"), names("uv pip install x"))
        assertEquals(listOf("apt:ffmpeg", "apt:jq"), names("yes | pkg install -y ffmpeg jq"))
        assertEquals(listOf("apt:poppler"), names("DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=--force-confold -y install poppler"))
        assertEquals(listOf("apt:upgrade (all upgradable packages)"), names("pkg upgrade -y"))
        assertEquals(listOf("apt:python"), names("timeout 900 /data/data/com.termux/files/usr/bin/apt install python"))
        assertEquals(listOf("npm:left-pad"), names("npm i -g left-pad"))
        assertEquals(listOf("npm:(project dependencies)"), names("cd app; npm install"))
        assertEquals(listOf("npm:serve"), names("yarn global add serve"))
        assertEquals(listOf("gem:jekyll", "cargo:ripgrep"), names("gem install jekyll; cargo install ripgrep"))
        assertEquals(listOf("pip:yt-dlp"), names("bash -lc 'pip install yt-dlp'"))
        assertEquals(listOf("pip:x"), names("echo \$(pip install x)"))
        assertEquals(listOf("deb:./a.deb"), names("dpkg -i ./a.deb"))
        // Not installs.
        listOf("ls -la", "pip list", "pip show requests", "apt list --installed", "pkg search ffmpeg", "echo 'pip install x'",
            "grep install README", "npm run build", "python3 -m http.server", "apt-get update", "yt-dlp -x URL")
            .forEach { assertEquals(it, emptyList<String>(), names(it)) }
    }

    @Test fun detectsPythonInstalls() {
        fun py(code: String) = InstallConsent.detectPython(code).map { it.toString() }
        assertEquals(listOf("pip:requests"), py("import subprocess, sys\nsubprocess.run([sys.executable, '-m', 'pip', 'install', 'requests'])"))
        assertEquals(listOf("pip:bs4"), py("import os\nos.system(\"pip install bs4\")"))
        assertEquals(listOf("apt:ffmpeg"), py("subprocess.check_call(['pkg', 'install', '-y', 'ffmpeg'])"))
        assertEquals(listOf("pip:(via pip's Python API)"), py("import pip\npip.main(['install', 'x'])").filter { it.contains("API") })
        assertEquals(emptyList<String>(), py("import requests\nprint('hello install')\nx = ['a', 'b']"))
    }

    @Test fun idDecideAndPayloads() {
        val p = specs("apt:poppler", "pip:pypdf")
        val id = InstallConsent.installId("pdf", p)
        assertEquals(8, id.length)
        assertEquals(id, InstallConsent.installId("pdf", p.reversed()))
        assertNotEquals(id, InstallConsent.installId("pdf", specs("apt:poppler")))
        assertNotEquals(id, InstallConsent.installId("termux_run", p))
        fun args(c: Boolean?, i: String?) = buildJsonObject { c?.let { put("confirm_install", it) }; i?.let { put("install_id", it) } }
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.decide(args(null, null), "pdf", p))
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.decide(args(true, null), "pdf", p)) // must have seen the list
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.decide(args(true, "deadbeef"), "pdf", p))
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.decide(args(true, id), "pdf", p))
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.decide(buildJsonObject { put("confirm_install", "true"); put("install_id", id) }, "pdf", p))
        assertEquals(InstallConsent.Decision.Deny, InstallConsent.decide(args(false, id), "pdf", p))
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.decide(args(null, null), "pdf", emptyList()))

        val pend = InstallConsent.pending("pdf_info", "pdf", p, "the PDF library")
        assertEquals(false, pend["ok"]!!.jsonPrimitive.boolean)
        assertEquals(true, pend["needs_install_confirmation"]!!.jsonPrimitive.boolean)
        assertEquals(id, pend["install_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("apt:poppler", "pip:pypdf"), pend["packages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("about 17 MB, usually 1–5 min in Termux", pend["estimate"]!!.jsonPrimitive.content)
        val msg = pend["message"]!!.jsonPrimitive.content
        assertTrue(msg, msg.startsWith("Nothing installed yet") && msg.contains("confirm_install=true") && msg.contains("install_id=\"$id\""))
        assertTrue(msg.contains("apt: poppler; pip: pypdf"))
        assertTrue(InstallConsent.estimate(specs("pip:weird")).startsWith("size/time unknown"))
        val d = InstallConsent.denied("pdf_info", p)
        assertEquals(true, d["install_denied"]!!.jsonPrimitive.boolean)
        assertEquals(specs("apt:a", "pip:b>=1"), PackageSpec.parseList(" apt:a  pip:b>=1 bogus apt:a"))
    }

    @Test fun termuxRunAsksBeforeInstalling() = runTest {
        val t = FakeTermux { TermuxResult("t", "done", "", 0, -1, null) }
        val tool = TermuxRunTool(t)
        val cmd = "pip install requests && python3 x.py"
        val pend = obj(tool.execute(buildJsonObject { put("command", cmd) }))
        assertEquals(true, pend["needs_install_confirmation"]!!.jsonPrimitive.boolean)
        assertTrue(t.cmds.isEmpty()) // nothing ran
        val id = pend["install_id"]!!.jsonPrimitive.content
        // Model can't skip the ask: confirm without the id → asked again.
        assertTrue(obj(tool.execute(buildJsonObject { put("command", cmd); put("confirm_install", true) })).containsKey("needs_install_confirmation"))
        // Declined → nothing runs.
        assertEquals(true, obj(tool.execute(buildJsonObject { put("command", cmd); put("confirm_install", false) }))["install_denied"]!!.jsonPrimitive.boolean)
        assertTrue(t.cmds.isEmpty())
        // Agreed → runs as is.
        val ok = obj(tool.execute(buildJsonObject { put("command", cmd); put("confirm_install", true); put("install_id", id) }))
        assertEquals(0, ok["exit_code"]!!.jsonPrimitive.int)
        assertTrue(t.cmds.single().contains("pip install requests"))
        // A different package list needs a new yes.
        assertTrue(obj(tool.execute(buildJsonObject { put("command", "pip install flask"); put("confirm_install", true); put("install_id", id) }))
            .containsKey("needs_install_confirmation"))
        // Normal commands are untouched.
        assertEquals(0, obj(tool.execute(buildJsonObject { put("command", "ls") }))["exit_code"]!!.jsonPrimitive.int)
    }

    @Test fun termuxPythonAsksForScriptsThatInstall() = runTest {
        val t = FakeTermux { TermuxResult("t", "", "", 0, -1, null) }
        val sandbox = WorkspaceSandbox(File(base, "ws").apply { mkdirs() })
        val tool = TermuxPythonTool(t, sandbox)
        val r = obj(tool.execute(buildJsonObject { put("path", "s.py"); put("code", "import os\nos.system('pip install bs4')") }))
        assertEquals(listOf("pip:bs4"), r["packages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(t.cmds.isEmpty())
        tool.execute(buildJsonObject { put("path", "s.py"); put("confirm_install", true); put("install_id", r["install_id"]!!.jsonPrimitive.content) })
        assertEquals(1, t.cmds.size)
        tool.execute(buildJsonObject { put("path", "t.py"); put("code", "print(1)") })
        assertEquals(2, t.cmds.size)
    }

    @Test fun pdfProbesThenInstallsOnlyAfterYes() = runTest {
        val probe = "${VerdroidPdfPy.MARKER}{\"ok\":false,\"needs_install\":true,\"missing\":\"apt:python-pymupdf\"}"
        val t = FakeTermux { c ->
            if (c.contains("apt-get -y install python-pymupdf")) TermuxResult("t", "${VerdroidPdfPy.MARKER}{\"ok\":true,\"pages\":2}\n", "", 0, -1, null)
            else TermuxResult("t", probe + "\n", "", 5, -1, null)
        }
        val tool = PdfInfoTool(t, folder)
        val pend = obj(tool.execute(buildJsonObject { put("path", "Input/a.pdf") }))
        assertEquals(true, pend["needs_install_confirmation"]!!.jsonPrimitive.boolean)
        assertEquals("pdf_info", pend["tool"]!!.jsonPrimitive.content)
        assertFalse(t.cmds.single().contains("apt-get -y install"))
        val id = pend["install_id"]!!.jsonPrimitive.content
        assertTrue(obj(tool.execute(buildJsonObject { put("path", "Input/a.pdf"); put("confirm_install", false) })).containsKey("install_denied"))
        assertEquals(2, t.cmds.size) // denial = only the probe ran
        // The same yes covers the whole pdf_* family (same library).
        val ok = obj(PdfMergeTool(t, folder).execute(buildJsonObject {
            put("paths", buildJsonArray { add("Input/a.pdf"); add("Input/a.pdf") }); put("output_name", "m.pdf")
            put("confirm_install", true); put("install_id", id)
        }))
        assertEquals(ok.toString(), true, ok["ok"]!!.jsonPrimitive.boolean)
        assertTrue(t.cmds.last().contains("apt-get -y install python-pymupdf"))
    }

    @Test fun ebookAsksBeforeInstallingThenEstimates() = runTest {
        val miss = "pip:googletrans>=4.0.2 pip:mobi"
        val t = FakeTermux { c ->
            if (c.contains("pip install -q -U")) TermuxResult("t", "${EbookTranslatePy.MARKER}{\"ok\":true,\"chapters\":3,\"remaining\":9,\"eta_seconds\":60}\n", "", 0, -1, null)
            else TermuxResult("t", "${EbookTranslatePy.MARKER}{\"ok\":false,\"needs_install\":true,\"missing\":\"$miss\"}\n", "", 5, -1, null)
        }
        val tool = EbookTranslateTool(t, folder)
        fun a(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("input_path", "Input/book.mobi"); put("dest_lang", "fr"); extra() }
        val pend = obj(tool.execute(a()))
        assertEquals(listOf("pip:googletrans>=4.0.2", "pip:mobi"), pend["packages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(pend["reason"]!!.jsonPrimitive.content.contains("estimate"))
        assertFalse(t.cmds.single().contains("pip install"))
        val est = obj(tool.execute(a { put("confirm_install", true); put("install_id", pend["install_id"]!!.jsonPrimitive.content) }))
        assertEquals(est.toString(), true, est["needs_confirmation"]!!.jsonPrimitive.boolean) // then the usual ETA step
        assertTrue(t.cmds.last().contains("'--estimate'"))
        assertTrue(obj(tool.execute(a { put("confirmed", true); put("confirm_install", false) })).containsKey("install_denied"))
    }

    /** The real probe scripts, with no python3 / pdftotext on PATH: they must list what's missing and exit 5. */
    @Test fun probesRunInBash() {
        fun bash(script: String): Pair<Int, String> {
            val p = ProcessBuilder("bash", "-c", script).also { it.environment()["PATH"] = "/nonexistent" }.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); return p.waitFor() to out
        }
        val (rc, out) = bash(VerdroidPdfPy.probeCommand() + "\necho SHOULD_NOT_RUN")
        assertEquals(out, 5, rc)
        val o = obj(out.lines().first { it.startsWith(VerdroidPdfPy.MARKER) }.removePrefix(VerdroidPdfPy.MARKER))
        assertEquals(specs("apt:python", "apt:python-pip", "apt:python-pymupdf"), InstallConsent.missingFrom(o))
        val (rc2, out2) = bash(EbookTranslatePy.probeCommand("pdf"))
        assertEquals(out2, 5, rc2)
        val o2 = obj(out2.lines().first { it.startsWith(EbookTranslatePy.MARKER) }.removePrefix(EbookTranslatePy.MARKER))
        assertEquals(specs("apt:python", "apt:python-pip", "pip:googletrans>=4.0.2", "pip:deep-translator", "pip:langdetect", "apt:poppler"),
            InstallConsent.missingFrom(o2))
        // Nothing missing → silent, continues.
        val (rc3, out3) = bash("verdroid_m=\"\"\n" + InstallConsent.probeExit("M=") + "\necho go")
        assertEquals(0, rc3); assertEquals("go", out3.trim())
    }
}
