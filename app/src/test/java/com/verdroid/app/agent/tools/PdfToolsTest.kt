package com.verdroid.app.agent.tools

import com.verdroid.app.data.pdf.VerdroidPdfPy
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxManager
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.tools.TermuxPackages
import com.verdroid.app.data.tools.ToolEnv
import com.verdroid.app.data.tools.ToolStatus
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

class PdfToolsTest {
    private lateinit var base: File
    private lateinit var folder: SharedFolder

    @Before fun setUp() {
        base = Files.createTempDirectory("pdf").toFile()
        folder = SharedFolder(File(base, "Documents/Farrow")) { true }
        folder.ensure()
        File(folder.input, "a.pdf").writeBytes(ByteArray(8))
        File(folder.input, "b.pdf").writeBytes(ByteArray(8))
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

    private fun ok(json: String) = FakeTermux { TermuxResult("t", "noise\n${VerdroidPdfPy.MARKER}$json\n", "", 0, -1, null) }
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val abs = SharedFolder.DISPLAY_PATH

    @Test fun mergeBuildsCappedCommandWithAbsolutePaths() = runTest {
        val t = ok("""{"ok":true,"output":"x","pages":3}""")
        val r = obj(PdfMergeTool(t, folder).execute(buildJsonObject {
            put("paths", buildJsonArray { add("Input/a.pdf"); add("$abs/Input/b.pdf") }); put("output_name", "both")
        }))
        assertEquals(true, r["ok"]?.jsonPrimitive?.boolean)
        assertEquals("Output/both.pdf", r["path"]?.jsonPrimitive?.content)
        val cmd = t.cmds.single()
        assertFalse(cmd.contains("~/.farrow"))
        assertTrue(cmd.contains("python3 '${VerdroidPdfPy.FILE}' 'merge' '$abs/Input/a.pdf' '$abs/Input/b.pdf' '--out' '$abs/Output/both.pdf'"))
        // helper written + deps set up before the capped python step, which must be last
        assertTrue(cmd.indexOf("base64 -d > '${VerdroidPdfPy.FILE}'") in 0 until cmd.indexOf("python-pymupdf"))
        assertTrue(cmd.indexOf("python-pymupdf") < cmd.indexOf("timeout -k 10 600 python3"))
        assertTrue(cmd.trimEnd().endsWith("exit \"\$rc\""))
        assertTrue(cmd.contains("termux-setup-storage"))
    }

    @Test fun mergeValidatesInputs() = runTest {
        val t = ok("""{"ok":true}""")
        assertTrue(PdfMergeTool(t, folder).execute(buildJsonObject { put("paths", buildJsonArray { add("Input/a.pdf") }) }).contains("at least 2"))
        assertTrue(PdfMergeTool(t, folder).execute(buildJsonObject {
            put("paths", buildJsonArray { add("Input/a.pdf"); add("Input/missing.pdf") }) }).contains("PDF not found"))
        assertTrue(PdfMergeTool(t, folder).execute(buildJsonObject {
            put("paths", buildJsonArray { add("Input/a.pdf"); add("/etc/passwd") }) }).contains("outside"))
        assertTrue(PdfMergeTool(t, folder).execute(buildJsonObject {
            put("paths", buildJsonArray { add("Input/a.pdf"); add("../../x.pdf") }) }).contains("escapes"))
        assertTrue(t.cmds.isEmpty())
    }

    @Test fun mergeInputsAcceptsArraysAndStrings() {
        assertEquals(listOf("a.pdf", "b.pdf"), PdfMergeTool.mergeInputs(buildJsonArray { add(" a.pdf "); add("b.pdf"); add("") }))
        assertEquals(listOf("a.pdf", "b.pdf"), PdfMergeTool.mergeInputs(JsonPrimitive("a.pdf, b.pdf")))
        assertEquals(listOf("a.pdf", "b.pdf"), PdfMergeTool.mergeInputs(JsonPrimitive("[\"a.pdf\",\"b.pdf\"]")))
        assertEquals(emptyList<String>(), PdfMergeTool.mergeInputs(null))
    }

    @Test fun termuxHomePathsPassThroughButNoTraversal() {
        val h = TermuxManager.TERMUX_HOME
        assertEquals("$h/docs/x.pdf", PdfToolBase.termuxHomePath("$h/docs/x.pdf"))
        assertNull(PdfToolBase.termuxHomePath("/data/data/com.other/x.pdf"))
        assertThrows(IllegalArgumentException::class.java) { PdfToolBase.termuxHomePath("$h/../../x.pdf") }
    }

    @Test fun extractPagesRefusesOverwritingTheInputAndBadRanges() = runTest {
        val t = ok("""{"ok":true}""")
        val tool = PdfExtractPagesTool(t, folder)
        File(folder.output, "x.pdf").writeBytes(ByteArray(8))
        assertTrue(tool.execute(buildJsonObject { put("path", "Output/x.pdf"); put("pages", "1"); put("output_name", "x.pdf") }).contains("overwrite"))
        assertTrue(tool.execute(buildJsonObject { put("path", "Input/a.pdf"); put("pages", "3-1") }).contains("end before start"))
        assertTrue(tool.execute(buildJsonObject { put("path", "Input/a.pdf"); put("pages", "1"); put("rotate", 45) }).contains("rotate"))
        assertTrue(tool.execute(buildJsonObject { put("path", "Input/a.pdf"); put("pages", "1"); put("output_name", "../Input/z.pdf") }).contains("Output"))
        assertTrue(t.cmds.isEmpty())
        val r = obj(tool.execute(buildJsonObject { put("path", "Input/a.pdf"); put("pages", "2-3, 1"); put("rotate", 90) }))
        assertEquals("Output/a.p2-3_1.pdf", r["path"]?.jsonPrimitive?.content)
        assertTrue(t.cmds.single().contains("'pages' '$abs/Input/a.pdf' '--pages' '2-3,1' '--out' '$abs/Output/a.p2-3_1.pdf' '--rotate' '90'"))
    }

    @Test fun annotateQuotesTextSafely() = runTest {
        val t = ok("""{"ok":true}""")
        PdfAnnotateTool(t, folder).execute(buildJsonObject {
            put("path", "Input/a.pdf"); put("text", "it's \$(rm -rf /)"); put("page", 2); put("x", 50.5)
        })
        val cmd = t.cmds.single()
        assertTrue(cmd.contains("'--text' 'it'\\''s \$(rm -rf /)'"))
        assertTrue(cmd.contains("'--x' '50.5'") && cmd.contains("'--page' '2'") && cmd.contains("'--mode' 'text'"))
        assertTrue(PdfAnnotateTool(t, folder).execute(buildJsonObject { put("path", "Input/a.pdf"); put("text", "x"); put("mode", "stamp") }).contains("mode"))
    }

    @Test fun extractTextFormatsMarkersChunksAndContinuation() = runTest {
        val helper = """{"ok":true,"backend":"pymupdf","page_count":10,"selected_pages":10,"returned_pages":2,"chars":20,
            "total_chars":100,"truncated":true,"next_page":3,"pages":[{"page":1,"text":"Alpha"},{"page":2,"text":"Beta","cut":true}]}"""
            .replace("\n", "")
        val t = ok(helper)
        val r = obj(PdfExtractTextTool(t, folder).execute(buildJsonObject { put("path", "Input/a.pdf"); put("max_chars", 10) }))
        assertEquals("--- Page 1 ---\nAlpha\n\n--- Page 2 ---\nBeta", r["text"]?.jsonPrimitive?.content)
        assertEquals("3-10", r["next_pages"]?.jsonPrimitive?.content)
        assertTrue(r["hint"]!!.jsonPrimitive.content.contains("pages=\"3-10\""))
        assertTrue(r.containsKey("untrusted"))
        assertTrue(t.cmds.single().contains("'--max-chars' '500'")) // clamped
        val c = obj(PdfExtractTextTool(t, folder).execute(buildJsonObject {
            put("path", "Input/a.pdf"); put("chunk_chars", 2000); put("save_as", "a-text")
        }))
        assertEquals("1-2", c["chunks"]!!.jsonArray.single().jsonObject["pages"]?.jsonPrimitive?.content)
        assertEquals("Output/a-text.txt", c["saved"]?.jsonPrimitive?.content)
        assertTrue(t.cmds.last().contains("'--save' '$abs/Output/a-text.txt'"))
        // helper errors pass through
        val e = obj(PdfExtractTextTool(ok("""{"ok":false,"error":"ValueError: bad"}"""), folder).execute(buildJsonObject { put("path", "Input/a.pdf") }))
        assertEquals("ValueError: bad", e["error"]?.jsonPrimitive?.content)
    }

    @Test fun missingMarkerGivesAHelpfulError() = runTest {
        val t = FakeTermux { TermuxResult("t", "", "ModuleNotFoundError: No module named 'pypdf'", 1, -1, null) }
        val r = obj(PdfInfoTool(t, folder).execute(buildJsonObject { put("path", "Input/a.pdf") }))
        assertEquals(false, r["ok"]?.jsonPrimitive?.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("pdf-tools"))
        val none = obj(PdfInfoTool(FakeTermux { null }, folder).execute(buildJsonObject { put("path", "Input/a.pdf") }))
        assertTrue(none["error"]!!.jsonPrimitive.content.contains("did not answer"))
    }

    @Test fun needsTermuxAndStorage() = runTest {
        val noTermux = object : TermuxRunner {
            override fun isInstalled() = false
            override fun hasRunCommandPermission() = false
            override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? = null
        }
        assertTrue(PdfInfoTool(noTermux, folder).execute(buildJsonObject { put("path", "Input/a.pdf") }).contains("Termux is not installed"))
        val locked = SharedFolder(File(base, "x")) { false }
        assertTrue(PdfInfoTool(ok("{}"), locked).execute(buildJsonObject { put("path", "Input/a.pdf") }).contains("All files access"))
    }

    @Test fun addOnAndStatus() {
        val p = TermuxPackages.ALL.first { it.pkg == "pdf-tools" }
        assertTrue(TermuxPackages.installScript(p).contains("python-pymupdf") && p.detect.contains(VerdroidPdfPy.FILE))
        assertTrue(ToolStatus.of("pdf_merge", ToolEnv(termuxReady = true)).ready)
        assertFalse(ToolStatus.of("pdf_merge", ToolEnv()).ready)
    }

    /** Runs the real helper with a local python3 that has PyMuPDF or pypdf (skipped otherwise, e.g. on CI). */
    @Test fun helperRunsLocallyWhenAPdfLibraryIsAvailable() = runTest {
        val py = System.getenv("FARROW_PDF_PYTHON") ?: "python3"
        val hasLib = runCatching {
            ProcessBuilder(py, "-c", "import importlib.util as u,sys; sys.exit(0 if u.find_spec('pymupdf') or u.find_spec('pypdf') else 1)")
                .start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue(hasLib)
        val script = File(base, "farrow_pdf.py").apply { writeText(VerdroidPdfPy.SOURCE) }
        fun run(vararg a: String): JsonObject {
            val p = ProcessBuilder(listOf(py, script.path) + a).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(60, TimeUnit.SECONDS)
            return obj(out.lineSequence().last { it.startsWith(VerdroidPdfPy.MARKER) }.removePrefix(VerdroidPdfPy.MARKER))
        }
        // A minimal valid 2-page PDF written by hand (no library needed to create it).
        val pdf = File(base, "t.pdf").apply { writeBytes(minimalPdf(listOf("Hello page one", "Second page here"))) }
        val info = run("info", pdf.path)
        assertEquals(2, info["pages"]?.jsonPrimitive?.int)
        val text = run("text", pdf.path, "--pages", "2")
        assertTrue(text.toString().contains("Second page"))
        val merged = File(base, "out/m.pdf").path
        assertEquals(4, run("merge", pdf.path, pdf.path, "--out", merged)["pages"]?.jsonPrimitive?.int)
        assertEquals(1, run("pages", merged, "--pages", "last", "--out", File(base, "out/p.pdf").path)["pages"]?.jsonPrimitive?.int)
        assertEquals(true, run("annotate", pdf.path, "--text", "Stamp", "--out", File(base, "out/a.pdf").path)["ok"]?.jsonPrimitive?.boolean)
        assertEquals(false, run("text", pdf.path, "--pages", "9")["ok"]?.jsonPrimitive?.boolean)
    }

    private fun minimalPdf(pages: List<String>): ByteArray {
        val objs = mutableListOf<String>()
        val n = pages.size
        objs += "<< /Type /Catalog /Pages 2 0 R >>"
        objs += "<< /Type /Pages /Kids [${(0 until n).joinToString(" ") { "${4 + it * 2} 0 R" }}] /Count $n >>"
        objs += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        pages.forEachIndexed { i, t ->
            val stream = "BT /F1 18 Tf 72 720 Td ($t) Tj ET"
            objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + i * 2} 0 R >>"
            objs += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objs.mapIndexed { i, o -> val off = sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n"); off }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
