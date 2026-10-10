package com.verdroid.app.data.pdf

import org.junit.Assert.*
import org.junit.Test

class PdfLogicTest {
    private fun fails(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun normalizeAndResolvePageSpecs() {
        assertEquals("all", PdfPages.normalize(null))
        assertEquals("all", PdfPages.normalize("  ALL "))
        assertEquals("1-3,5,7-", PdfPages.normalize(" 1 - 3, 5 ,7-"))
        assertEquals((1..4).toList(), PdfPages.resolve("all", 4))
        assertEquals(listOf(1, 2, 3, 5, 7, 8), PdfPages.resolve("1-3,5,7-", 8))
        assertEquals(listOf(8), PdfPages.resolve("last", 8))
        assertEquals(listOf(6, 7, 8), PdfPages.resolve("6-last", 8))
        assertEquals(listOf(5, 2, 2), PdfPages.resolve("5,2,2", 8)) // order kept, duplicates allowed
        assertEquals(listOf(3, 4), PdfPages.resolve("3-99", 4)) // end clamped
    }

    @Test fun badPageSpecsAreRejected() {
        fails { PdfPages.normalize("0") }
        fails { PdfPages.normalize("5-2") }
        fails { PdfPages.normalize("a-b") }
        fails { PdfPages.normalize("1;rm -rf") }
        fails { PdfPages.normalize((1..101).joinToString(",")) }
        fails { PdfPages.resolve("9", 4) }
        fails { PdfPages.resolve("last-2", 4) }
    }

    @Test fun compressAndRemaining() {
        assertEquals("1-3,5,7-8", PdfPages.compress(listOf(1, 2, 3, 5, 7, 8)))
        assertEquals("", PdfPages.compress(emptyList()))
        assertEquals("4-10", PdfPages.remaining("all", 10, 4))
        assertEquals("7-8", PdfPages.remaining("1-3,7-", 8, 7))
        assertNull(PdfPages.remaining("all", 10, null))
        assertNull(PdfPages.remaining("1-3", 10, 9))
    }

    @Test fun outputNamesStayUnderOutput() {
        assertEquals("Output/x.pdf", PdfNames.outputRel("x", "d.pdf", "pdf"))
        assertEquals("Output/x.pdf", PdfNames.outputRel("Output/x.pdf", "d.pdf", "pdf"))
        assertEquals("Output/sub/x.PDF", PdfNames.outputRel("sub/x.PDF", "d.pdf", "pdf"))
        assertEquals("Output/d.pdf", PdfNames.outputRel(null, "d.pdf", "pdf"))
        assertEquals("Output/notes.txt", PdfNames.outputRel("/storage/emulated/0/Documents/Verdroid/Output/notes", "", "txt"))
        fails { PdfNames.outputRel("../x.pdf", "d", "pdf") }
        fails { PdfNames.outputRel("Output/../Input/x.pdf", "d", "pdf") }
        fails { PdfNames.outputRel("/etc/x.pdf", "d", "pdf") }
        fails { PdfNames.outputRel("Input/x.pdf", "d", "pdf") }
        fails { PdfNames.outputRel("sub/", "d", "pdf") }
        assertEquals("My_Report_v2", PdfNames.safeStem("My Report (v2)"))
        assertEquals("document", PdfNames.safeStem("///"))
        assertEquals("p1-3_5", PdfNames.pagesTag("1-3,5"))
    }

    @Test fun markedTextHasPageMarkers() {
        val t = PdfText.marked(listOf(1 to "Hello ", 2 to "World"))
        assertEquals("--- Page 1 ---\nHello\n\n--- Page 2 ---\nWorld", t)
    }

    @Test fun chunksKeepPagesTogetherAndRespectTheSize() {
        val pages = (1..10).map { it to "word ".repeat(100).trim() } // ~500 chars each
        val chunks = PdfText.chunks(pages, 1_200)
        assertTrue(chunks.all { it.text.length <= 1_200 })
        assertEquals(5, chunks.size) // two pages per chunk
        assertEquals("1-2", chunks.first().pages)
        assertEquals("9-10", chunks.last().pages)
        assertTrue(chunks.first().text.startsWith("--- Page 1 ---"))
        // Every page appears exactly once.
        val text = chunks.joinToString("\n") { it.text }
        (1..10).forEach { assertEquals(1, Regex("--- Page $it ---").findAll(text).count()) }
    }

    @Test fun longPagesAreSplitWithContinuationMarkers() {
        val long = (1..400).joinToString(" ") { "w$it" } + "\n\n" + "x".repeat(3_000)
        val chunks = PdfText.chunks(listOf(1 to long, 2 to "short"), 1_000)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.text.length <= 1_000 })
        assertTrue(chunks[1].text.startsWith("--- Page 1 (cont.) ---"))
        assertEquals(2, chunks.last().lastPage)
        // No text is lost (ignoring markers/whitespace).
        val joined = chunks.joinToString("") { c -> c.text.lineSequence().filterNot { it.startsWith("--- Page") }.joinToString("") }
        assertEquals((long + "short").filterNot(Char::isWhitespace), joined.filterNot(Char::isWhitespace))
        // chunk size is clamped
        assertTrue(PdfText.chunks(listOf(1 to "a".repeat(5_000)), 10).all { it.text.length <= PdfText.MIN_CHUNK })
    }

    @Test fun cutPrefersParagraphsThenSpaces() {
        assertEquals(12, PdfText.cut("aaaaaaaaaa\n\nbbbbbbbbbb", 15))
        assertEquals(6, PdfText.cut("aaaaa bbbbbbbbbb", 10))
        assertEquals(10, PdfText.cut("a".repeat(30), 10))
    }

    @Test fun helperScriptIsSafeToEmbed() {
        val s = VerdroidPdfPy.SOURCE
        assertTrue(s.contains("import pymupdf") && s.contains("import pypdf") && s.contains("pdftotext"))
        assertTrue(s.contains("def cmd_merge") && s.contains("def cmd_annotate") && s.contains("def cmd_pages"))
        assertTrue(s.contains("MARKER = \"${VerdroidPdfPy.MARKER}\""))
        assertFalse(VerdroidPdfPy.FILE.contains("~"))
        assertFalse(VerdroidPdfPy.setupCommand().contains("install python-pymupdf")) // probe only without consent
        assertTrue(VerdroidPdfPy.setupCommand().contains("needs_install"))
        val setup = VerdroidPdfPy.setupCommand(allowInstall = true)
        assertTrue(setup.contains("apt-get -y install python-pymupdf") && setup.contains("pip install -q -U pypdf"))
        assertTrue(setup.contains(VerdroidPdfPy.NO_PYMUPDF_FLAG))
        assertTrue(VerdroidPdfPy.installCommand().contains("base64 -d > '${VerdroidPdfPy.FILE}'"))
    }
}
