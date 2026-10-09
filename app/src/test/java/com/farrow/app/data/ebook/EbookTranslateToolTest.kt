package com.farrow.app.data.ebook

import com.farrow.app.agent.tools.EbookTranslateTool
import com.farrow.app.data.storage.SharedFolder
import com.farrow.app.data.termux.TermuxResult
import com.farrow.app.data.termux.TermuxRunner
import com.farrow.app.data.tools.TermuxPackages
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
        assertTrue(t.cmds.single().contains("farrow_ebook_translate.py"))
        assertTrue(t.cmds.single().contains("Documents/Farrow/Input/book.mobi"))
        assertTrue(t.cmds.single().contains("Documents/Farrow/Output/book.fr.txt"))
        assertTrue(t.cmds.single().contains("'--dest' 'fr'"))
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
