package com.farrow.app.data.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatAttachmentTest {
    @Test fun sanitizeAndUnique() {
        assertEquals("book.mobi", ChatAttachment.sanitize("book.mobi"))
        assertEquals("a_b.txt", ChatAttachment.sanitize("a/b.txt"))
        assertEquals("file.hidden", ChatAttachment.sanitize(".hidden"))
        val dir = Files.createTempDirectory("att").toFile()
        try {
            File(dir, "a.txt").writeText("1")
            val u = ChatAttachment.unique(File(dir, "a.txt"))
            assertEquals("a-2.txt", u.name)
            assertTrue(ChatAttachment.messagePrefix(
                ChatAttachment.Saved("Input/a.txt", "/x/Input/a.txt", "a.txt", 3)
            ).contains("Input/a.txt") && ChatAttachment.messagePrefix(
                ChatAttachment.Saved("Input/a.txt", "/x/Input/a.txt", "a.txt", 3)
            ).contains("Output/"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun displayHidesPathAndToolHints() {
        val saved = ChatAttachment.Saved("Input/Fulgrim - Graham McNeill.mobi", "/x", "Fulgrim - Graham McNeill.mobi", 1_468_006)
        val full = ChatAttachment.messagePrefix(saved) + "Translate it to French"
        val d = ChatAttachment.forDisplay(full)
        assertEquals("Fulgrim - Graham McNeill.mobi", d.fileName)
        assertEquals(1_468_006L, d.bytes)
        assertEquals("Translate it to French", d.text)
        assertFalse(d.text.contains("workspace_") || d.text.contains("Documents/Farrow"))
        // Attachment-only: default prompt hidden, chat titled by the file name.
        val only = ChatAttachment.messagePrefix(saved) + ChatAttachment.DEFAULT_PROMPT
        assertEquals("", ChatAttachment.forDisplay(only).text)
        assertEquals("Fulgrim - Graham McNeill", com.farrow.app.domain.usecase.StartConversationUseCase.titleFrom(only))
        assertEquals("Translate it to French", com.farrow.app.domain.usecase.StartConversationUseCase.titleFrom(full))
        // Plain messages untouched.
        assertEquals(com.farrow.app.domain.model.AttachmentText.Display(null, null, "hello"), ChatAttachment.forDisplay("hello"))
        assertEquals("1.4 MB", ChatAttachment.humanSize(1_468_006))
    }
}
