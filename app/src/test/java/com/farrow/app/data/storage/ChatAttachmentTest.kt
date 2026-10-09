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
}
