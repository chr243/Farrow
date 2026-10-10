package com.verdroid.app.domain.model

import com.verdroid.app.data.local.TaskEntity
import com.verdroid.app.data.repository.toDomain
import com.verdroid.app.domain.usecase.StartConversationUseCase
import org.junit.Assert.*
import org.junit.Test

class AttachmentTextTest {
    private val file = "Fulgrim - Graham McNeill.mobi"
    private val only = AttachmentText.prefix("Input/$file", 1_468_006) + AttachmentText.DEFAULT_PROMPT
    private val withText = AttachmentText.prefix("Input/$file", 1_468_006) + "Translate to French please"

    @Test fun `legacy raw titles are cleaned for every UI path`() {
        val legacy = "Attached file: Input/Fulgrim - Graham McNeill.mobi…"
        assertEquals("Fulgrim - Graham McNeill", AttachmentText.cleanTitle(legacy, only))
        assertEquals("Translate to French please", AttachmentText.cleanTitle(legacy, withText))
        assertEquals("Fulgrim - Graham McNeill", AttachmentText.cleanTitle(legacy, ""))
        assertEquals("My chat", AttachmentText.cleanTitle("My chat", only))
    }

    @Test fun `new titles never contain the attachment line`() {
        assertEquals("Fulgrim - Graham McNeill", StartConversationUseCase.titleFrom(only))
        assertEquals("Translate to French please", StartConversationUseCase.titleFrom(withText))
        assertEquals("a b c d e f…", StartConversationUseCase.titleFrom("a b c d e f g"))
    }

    @Test fun `mapper hands the UI a clean title`() {
        val e = TaskEntity(title = "Attached file: Input/Fulgrim - Graham McNeill.mobi…", prompt = only, type = "CHAT",
            status = "IDLE", subtitle = "Done", createdAt = 1, updatedAt = 1)
        val t = e.toDomain()
        assertEquals("Fulgrim - Graham McNeill", t.title)
        assertEquals(only, t.prompt)
    }

    @Test fun `previews and topic text hide path and tool hints`() {
        assertEquals("📎 $file", AttachmentText.preview(only))
        assertEquals("📎 $file · Translate to French please", AttachmentText.preview(withText))
        assertFalse(AttachmentText.plain(only).contains("workspace_") || AttachmentText.plain(only).contains("Documents/Farrow"))
        assertEquals("hi", AttachmentText.preview("hi"))
        val d = AttachmentText.forDisplay("  " + withText)
        assertEquals(file, d.fileName); assertEquals("Translate to French please", d.text)
    }

    @Test fun `attachment hint never steers to translation and asks when there is no instruction`() {
        for (path in listOf("Input/$file", "Input/report.pdf", "Input/a.png", "Input/notes.docx", "Input/x.bin", "Input/noext")) {
            val p = AttachmentText.prefix(path, 10)
            assertFalse(p, p.contains("ebook_translate on that path"))
            assertTrue(p.contains("ask what to do") && p.contains("no translation unless asked"))
            assertTrue(p.endsWith("\n\n") && p.trimEnd().lines().size == 1)
            assertTrue(AttachmentText.options(path).size in 3..6)
            assertFalse(AttachmentText.options(path).first().contains("translat"))
        }
        assertTrue(AttachmentText.options("Input/r.PDF").any { it.contains("pdf_extract_text") })
        assertFalse(AttachmentText.options("Input/a.jpg").any { it.contains("translat") })
        assertFalse(AttachmentText.DEFAULT_PROMPT.contains("work with", ignoreCase = true))
        assertTrue(AttachmentText.DEFAULT_PROMPT.contains("Ask me"))
    }

    @Test fun `new and legacy default prompts are hidden in the UI`() {
        val p = AttachmentText.prefix("Input/report.pdf", 5)
        assertEquals("", AttachmentText.forDisplay(p + AttachmentText.DEFAULT_PROMPT).text)
        assertEquals("", AttachmentText.forDisplay(p + "Please work with the attached file.").text)
        assertEquals("📎 report.pdf", AttachmentText.preview(p + AttachmentText.DEFAULT_PROMPT))
        assertEquals("Input/report.pdf", AttachmentText.forDisplay(p + "Summarise").path)
        // Messages stored by v1.0.27 (old hint wording) still parse.
        val old = "Attached file: Input/b.mobi (9 bytes). It is under Documents/Farrow — use workspace_*, pdf_* (PDFs) or ebook_translate on that path. Deliverables go in Output/.\n\nhi"
        assertEquals("b.mobi", AttachmentText.forDisplay(old).fileName); assertEquals("hi", AttachmentText.forDisplay(old).text)
    }
}
