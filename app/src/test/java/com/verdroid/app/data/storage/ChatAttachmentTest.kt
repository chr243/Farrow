package com.verdroid.app.data.storage

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
        assertEquals("Fulgrim - Graham McNeill", com.verdroid.app.domain.usecase.StartConversationUseCase.titleFrom(only))
        assertEquals("Translate it to French", com.verdroid.app.domain.usecase.StartConversationUseCase.titleFrom(full))
        // Plain messages untouched.
        assertEquals(com.verdroid.app.domain.model.AttachmentText.Display(null, null, "hello"), ChatAttachment.forDisplay("hello"))
        assertEquals("1.4 MB", ChatAttachment.humanSize(1_468_006))
    }

    private fun tempFolder(access: Boolean = true): Pair<File, SharedFolder> {
        val dir = Files.createTempDirectory("farrow").toFile()
        return dir to SharedFolder(File(dir, "Documents/Farrow")) { access }
    }

    @Test fun copyCreatesInputAndCopies() {
        val (dir, folder) = tempFolder()
        try {
            assertFalse(folder.input.exists())
            val s = ChatAttachment.copyToInput(folder, "My Book.pdf", "primary:Download/My Book.pdf") { "hello".byteInputStream() }
            assertEquals("Input/My Book.pdf", s.relativePath)
            assertEquals("/storage/emulated/0/Documents/Farrow/Input/My Book.pdf", s.absolutePath)
            assertEquals(5L, s.bytes)
            assertEquals("hello", File(folder.input, "My Book.pdf").readText())
            assertTrue(folder.output.isDirectory)
            // Same name again → no overwrite.
            val s2 = ChatAttachment.copyToInput(folder, "My Book.pdf", null) { "x".byteInputStream() }
            assertEquals("Input/My Book-2.pdf", s2.relativePath)
            assertEquals("hello", File(folder.input, "My Book.pdf").readText())
            assertEquals(listOf("My Book-2.pdf", "My Book.pdf"), folder.input.list()!!.sorted())
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedCopyLeavesNoPartialFile() {
        val (dir, folder) = tempFolder()
        try {
            val broken = object : java.io.InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 10) 65 else throw java.io.IOException("boom")
            }
            assertThrows(IllegalStateException::class.java) { ChatAttachment.copyToInput(folder, "a.txt", null) { broken } }
            assertThrows(IllegalStateException::class.java) { ChatAttachment.copyToInput(folder, "a.txt", null) { null } }
            assertEquals(emptyList<String>(), folder.input.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun noAccessThrowsTypedError() {
        val (dir, folder) = tempFolder(access = false)
        try {
            assertThrows(ChatAttachment.NoAccessException::class.java) {
                ChatAttachment.copyToInput(folder, "a.txt", null) { "x".byteInputStream() }
            }
            assertFalse(folder.root.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun pickFromInputIsReusedNotDuplicated() {
        val (dir, folder) = tempFolder()
        try {
            folder.ensure()
            File(folder.input, "photo.jpg").writeText("img")
            var opened = false
            val s = ChatAttachment.copyToInput(folder, "photo.jpg", "primary:Documents/Farrow/Input/photo.jpg") { opened = true; null }
            assertFalse(opened)
            assertEquals("Input/photo.jpg", s.relativePath)
            assertEquals(1, folder.input.list()!!.size)
            // file:// path form too; nested / escaping paths are not reused.
            assertNotNull(ChatAttachment.existingInInput(folder.input, "/storage/emulated/0/Documents/Farrow/Input/photo.jpg"))
            assertNull(ChatAttachment.existingInInput(folder.input, "primary:Documents/Farrow/Input/sub/photo.jpg"))
            assertNull(ChatAttachment.existingInInput(folder.input, "primary:Documents/Farrow/Input/.."))
            assertNull(ChatAttachment.existingInInput(folder.input, "primary:Documents/Farrow/Output/photo.jpg"))
            assertNull(ChatAttachment.existingInInput(folder.input, "primary:Documents/Farrow/Input/missing.jpg"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun fallbackNames() {
        assertEquals("a.pdf", ChatAttachment.fallbackName("primary:Download/a.pdf"))
        assertEquals("a.pdf", ChatAttachment.fallbackName("primary:a.pdf"))
        assertEquals("attachment", ChatAttachment.fallbackName(null))
        assertEquals("attachment", ChatAttachment.fallbackName("primary:"))
    }
}
