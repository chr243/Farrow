package com.farrow.app.domain.model

import com.farrow.app.data.local.TaskEntity
import com.farrow.app.data.repository.toDomain
import com.farrow.app.domain.usecase.StartConversationUseCase
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
}
