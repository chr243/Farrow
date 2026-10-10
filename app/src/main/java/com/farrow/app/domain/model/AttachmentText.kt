package com.farrow.app.domain.model

import java.util.Locale

/**
 * The model-facing attachment line ("Attached file: Input/x.mobi (n bytes). It is under Documents/Farrow — use …") and
 * the display-side helpers that hide it. Stored messages and the task prompt keep the full line (it goes to the API);
 * every UI path (bubble, header, chat list, stories, chat heads, notifications, avatar glyph) uses these instead.
 * Pure Kotlin so domain use cases and the data mappers can share it.
 */
object AttachmentText {
    /** Sent when the user attaches a file without typing anything. */
    const val DEFAULT_PROMPT = "Please work with the attached file."

    fun prefix(relativePath: String, bytes: Long): String =
        "Attached file: $relativePath ($bytes bytes). It is under Documents/Farrow — use workspace_*, pdf_* (PDFs) or ebook_translate on that path. Deliverables go in Output/.\n\n"

    /** [path] is the attachment's path relative to Documents/Farrow (e.g. Input/photo.jpg). */
    data class Display(val fileName: String?, val bytes: Long?, val text: String, val path: String? = null) {
        val isImage: Boolean get() = fileName != null && isImageName(fileName)
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
    /** Images the chat sends to vision models (and shows as thumbnails). */
    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private val PREFIX_RE = Regex("^\\s*Attached file: (.+?) \\((\\d+) bytes\\)\\. It is under Documents/Farrow[^\\n]*(\\n\\n?|$)")
    /** A stored title made from the raw prefix by the old titleFrom (first 6 words, maybe with "…"). */
    private val RAW_TITLE_RE = Regex("^\\s*Attached file:\\s*(.*)$")

    /** File name + the user's own text; the default prompt is hidden when it was only added because nothing was typed. */
    fun forDisplay(content: String): Display {
        val m = PREFIX_RE.find(content) ?: return Display(null, null, content)
        val rest = content.substring(m.range.last + 1)
        return Display(m.groupValues[1].substringAfterLast('/'), m.groupValues[2].toLongOrNull(),
            if (rest.trim() == DEFAULT_PROMPT) "" else rest, m.groupValues[1])
    }

    /** One-line text for previews/notifications: the user's text, else "📎 <file name>". */
    fun preview(content: String): String {
        val d = forDisplay(content)
        return when {
            d.fileName == null -> content
            d.text.isBlank() -> "📎 ${d.fileName}"
            else -> "📎 ${d.fileName} · ${d.text.trim()}"
        }
    }

    /** Text without the attachment line (for topic detection etc.): file name + user text. */
    fun plain(content: String): String {
        val d = forDisplay(content)
        return if (d.fileName == null) content else listOf(d.fileName, d.text.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun stripExtension(name: String): String =
        name.substringBeforeLast('.', name).takeIf { it.isNotBlank() && name.length - it.length in 2..6 } ?: name

    /**
     * Title shown in the UI. Chats created before v1.0.26 stored "Attached file: Input/Book - Author.mobi…" as their
     * title; those are rebuilt from the prompt (or, failing that, cleaned from the raw title).
     */
    fun cleanTitle(title: String, prompt: String): String {
        val raw = RAW_TITLE_RE.find(title) ?: return title
        val d = forDisplay(prompt)
        if (d.fileName != null) {
            val text = d.text.trim()
            return if (text.isEmpty()) stripExtension(d.fileName).take(60) else titleWords(text)
        }
        val rest = raw.groupValues[1].removeSuffix("…").trim().removePrefix("Input/")
            .substringBefore(" (").trim()
        return stripExtension(rest).ifBlank { "Attachment" }.take(60)
    }

    /** First six words (the classic title rule). */
    fun titleWords(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val t = words.take(6).joinToString(" ")
        return if (words.size > 6) "$t…" else t.ifBlank { "New conversation" }
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    }
}
