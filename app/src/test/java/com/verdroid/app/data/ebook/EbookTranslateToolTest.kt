package com.verdroid.app.data.ebook

import com.verdroid.app.agent.tools.EbookTranslateTool
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.tools.TermuxPackages
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EbookTranslateToolTest {
    private lateinit var base: File
    private lateinit var folder: SharedFolder
    private var access = true

    @Before fun setUp() {
        base = Files.createTempDirectory("ebook").toFile()
        folder = SharedFolder(File(base, "Documents/Farrow")) { access }
        folder.ensure()
    }
    @After fun tearDown() { base.deleteRecursively() }

    private class FakeTermux(var answer: (String) -> TermuxResult?) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command
            return answer(command)
        }
    }

    @Test fun packageAndScriptPresent() {
        val p = TermuxPackages.ALL.first { it.pkg == "ebook-translate" }
        assertTrue(TermuxPackages.installScript(p).contains("deep-translator") && TermuxPackages.installScript(p).contains("mobi"))
        assertTrue(EbookTranslatePy.SOURCE.contains("extract_mobi") && EbookTranslatePy.SOURCE.contains("MyMemoryTranslator"))
        assertTrue(EbookTranslatePy.SOURCE.contains("from googletrans import Translator"))
        assertTrue(TermuxPackages.installScript(p).contains("'googletrans>=4.0.2'"))
        assertTrue(EbookTranslatePy.SOURCE.contains("farrow-translate.json"))
    }

    @Test fun toolBuildsCommandAndParsesResult() = runTest {
        File(folder.input, "book.mobi").writeBytes(ByteArray(16))
        val t = FakeTermux {
            TermuxResult("x", "noise\n${EbookTranslatePy.MARKER}{\"ok\":true,\"output\":\"/storage/emulated/0/Documents/Farrow/Output/book.fr.txt\",\"chunks\":3}\n", "", 0, -1, null)
        }
        val r = Json.parseToJsonElement(EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "Input/book.mobi"); put("dest_lang", "fr")
        })).jsonObject
        assertEquals(true, r["ok"]?.jsonPrimitive?.boolean)
        val cmd = t.cmds.single()
        // v1.0.22 bug: the script path was passed as a quoted "~/.farrow/…", which bash never expands.
        assertFalse(cmd.contains("~/.farrow"))
        assertTrue(cmd.contains("python3 '${EbookTranslatePy.FILE}'"))
        assertTrue(cmd.indexOf("base64 -d > '${EbookTranslatePy.FILE}'") in 0 until cmd.indexOf("python3 '"))
        assertTrue(t.cmds.single().contains("Documents/Farrow/Input/book.mobi"))
        assertTrue(t.cmds.single().contains("Documents/Farrow/Output/book.fr.txt"))
        assertTrue(t.cmds.single().contains("'--dest' 'fr'"))
        // Not confirmed yet → estimate only, with the ask-the-user instruction.
        assertTrue(cmd.contains("'--estimate'"))
        assertEquals(true, r["needs_confirmation"]?.jsonPrimitive?.boolean)
        // No install without consent: only the probe runs before the translator.
        assertFalse(cmd.contains("pip install"))
        assertTrue(cmd.indexOf("needs_install") in 0 until cmd.indexOf("python3 '"))
        assertTrue(cmd.contains("pip:mobi"))
        // The consented setup still quotes googletrans (an unquoted >= would be a redirect).
        val setup = EbookTranslatePy.setupCommand("mobi", allowInstall = true)
        assertTrue(setup.contains("pip install -q -U 'googletrans>=4.0.2'"))
        assertTrue(setup.contains("import googletrans,deep_translator,langdetect,mobi"))
        // Confirmed → real run, no --estimate, result passed through.
        EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "Input/book.mobi"); put("dest_lang", "fr"); put("confirmed", true)
        })
        assertFalse(t.cmds.last().contains("'--estimate'"))
        assertTrue(t.cmds.last().contains("timeout -k 30 7200 python3 '"))
    }

    @Test fun confirmationMessageHasEta() {
        val o = EbookTranslateTool(FakeTermux { null }, folder).withConfirmation(buildJsonObject {
            put("ok", true); put("chapters", 12); put("chunks", 300); put("remaining", 300); put("done", 0)
            put("eta_seconds", 800); put("eta_min_seconds", 450); put("eta_max_seconds", 1300)
        })
        assertEquals("13 min (range 8 min–22 min)", o["eta"]!!.jsonPrimitive.content)
        assertEquals(1745, o["suggested_timeout_seconds"]!!.jsonPrimitive.int)
        assertTrue(o["message"]!!.jsonPrimitive.content.contains("confirmed=true"))
        val long = EbookTranslateTool(FakeTermux { null }, folder).withConfirmation(buildJsonObject {
            put("ok", true); put("eta_seconds", 9000); put("eta_max_seconds", 12000)
        })
        assertEquals(7200, long["suggested_timeout_seconds"]!!.jsonPrimitive.int)
        assertTrue(long["message"]!!.jsonPrimitive.content.contains("3 runs with resume=true"))
        assertEquals("2 h 30 min", EbookTranslateTool.formatDuration(9000))
    }

    private fun py(code: String): String {
        val f = File(base, "farrow_ebook_translate.py").apply { writeText(EbookTranslatePy.SOURCE) }
        val p = ProcessBuilder("python3", "-c", "import importlib.util as u; s=u.spec_from_file_location('f', '${f.path}'); " +
            "m=u.module_from_spec(s); s.loader.exec_module(m)\n$code").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); assertEquals(out, 0, p.waitFor()); return out.trim()
    }

    /** User requirement: chunks MUST stay below 5000 chars (Google's limit); settings follow howtotranslate.md. */
    @Test fun chunksNeverReach5000AndSettingsMatchGuide() {
        val maxes = py("import random\nrandom.seed(1)\n" +
            "t='\\n\\n'.join(' '.join('w'*random.randint(1,40) for _ in range(random.randint(1,900))) for _ in range(200))\n" +
            "t2='x'*30000 + '. ' + 'y. '*5000\n" +
            "print(max(len(c) for c in m.chunk_text(t)), max(len(c) for c in m.chunk_text(t2)), max(len(c) for c in m.chunk_text(t, 9000)))")
        val (a, b, c) = maxes.split(" ").map { it.toInt() }
        assertTrue(maxes, a <= 4000 && b <= 4000 && c < 5000)
        assertEquals("4000 0.3 4 5.0 10.0 500 10", py("print(m.CHUNK, m.DELAY[0], m.BATCH_CHAPTERS, *m.BATCH_PAUSE, m.MYMEMORY_MAX, m.BACKOFF_429[0])"))
        assertTrue(py("print(m.USER_AGENT)").startsWith("Mozilla/5.0"))
        // Language codes: families match across engines/langdetect (zh-CN≈zh-cn, pt-BR≈pt, iw≈he, tl≈fil, nb≈no).
        assertEquals("zh-cn zh-tw pt he fil no fr", py("print(*[m.lang_family(c) for c in ['zh-CN','zh-TW','pt-BR','iw','tl','nb','fr']])"))
        // MyMemory must never get a hard-coded English source or a bare target again.
        assertFalse(EbookTranslatePy.SOURCE.contains("s = \"en-GB\" if src"))
        // Plain-text fallback: a "mobi" that the reader cannot open still yields text.
        val fake = File(base, "broken.mobi").apply { writeText("<html><body><h1>Chapter 1</h1><p>" + "Il était une fois un roi. ".repeat(30) + "</p></body></html>") }
        assertTrue(py("print(len(m.extract(m.Path('${fake.path}'), 'mobi')) > 0, bool(m.FALLBACK_USED))") == "True True")
        // 9 chapters, 2 batch pauses (after chapters 4 and 8).
        assertEquals("2", py("print(m.estimate(100, 9)[3])"))
        assertEquals("3", py("print(len(m.split_chapters('Chapter 1\\n\\na\\n\\nChapter 2\\n\\nb\\n\\nChapter 3\\n\\nc')))"))
    }

    /** Runs the real deploy + script with local bash/python3 (Termux home mapped to a temp dir). */
    @Test fun deployWritesScriptAndItRuns() = runTest {
        val home = File(base, "home").apply { mkdirs() }
        File(folder.input, "note.txt").writeText("Chapter 1\n\nHello there.\n\nChapter 2\n\nGoodbye.")
        val t = FakeTermux { cmd ->
            // Skip the first-run apt/pip setup on the build machine.
            val mapped = cmd.replace(EbookTranslatePy.setupCommand("txt"), "true")
                .replace(com.verdroid.app.data.termux.TermuxManager.TERMUX_HOME, home.path)
                .replace(SharedFolder.DISPLAY_PATH, folder.root.path)
            val p = ProcessBuilder("bash", "-c", mapped).start()
            val out = p.inputStream.bufferedReader().readText(); val err = p.errorStream.bufferedReader().readText()
            TermuxResult("x", out, err, p.waitFor(), -1, null)
        }
        val r = Json.parseToJsonElement(EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "Input/note.txt"); put("dest_lang", "fr"); put("timeout_seconds", 60)
        })).jsonObject
        assertTrue(File(home, ".farrow/farrow_ebook_translate.py").length() > 1000)
        // First call = estimate (needs no translation packages for TXT).
        assertEquals(r.toString(), true, r["needs_confirmation"]?.jsonPrimitive?.boolean)
        assertEquals(2, r["chapters"]?.jsonPrimitive?.int)
        assertTrue(r["eta"]!!.jsonPrimitive.content.isNotEmpty())
        val r2 = Json.parseToJsonElement(EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "Input/note.txt"); put("dest_lang", "fr"); put("timeout_seconds", 60); put("confirmed", true)
        })).jsonObject
        // The script ran: either it translated (network + deep_translator present) or it reported missing packages.
        assertTrue(r2.toString(), r2["ok"]?.jsonPrimitive?.boolean == true ||
            r2["error"]?.jsonPrimitive?.content?.contains("Missing Python packages") == true ||
            r2["error"]?.jsonPrimitive?.content?.contains("translate failed") == true)
        assertFalse(r2.toString().contains("No such file"))
    }

    @Test fun addOnInstallAlsoDeploysScript() {
        val s = TermuxPackages.installScript(TermuxPackages.ALL.first { it.pkg == "ebook-translate" })
        assertTrue(s.contains("base64 -d > '${EbookTranslatePy.FILE}'"))
        assertTrue(TermuxPackages.ALL.first { it.pkg == "ebook-translate" }.detect.contains(EbookTranslatePy.FILE))
    }

    @Test fun rejectsEscapeAndMissing() = runTest {
        val t = FakeTermux { error("no") }
        assertTrue(Json.parseToJsonElement(EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "../secret.mobi"); put("dest_lang", "fr")
        })).jsonObject.containsKey("error"))
        assertTrue(t.cmds.isEmpty())
        access = false
        assertTrue(Json.parseToJsonElement(EbookTranslateTool(t, folder).execute(buildJsonObject {
            put("input_path", "Input/a.mobi"); put("dest_lang", "fr")
        })).jsonObject["error"]!!.jsonPrimitive.content.contains("All files"))
    }
}
