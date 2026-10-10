package com.farrow.app.data.storage

import com.farrow.app.domain.model.AttachmentText

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Copies a user-picked SAF [Uri] into Documents/Farrow/Input for the agent (ebook_translate, workspace_*, …). */
object ChatAttachment {
    data class Saved(val relativePath: String, val absolutePath: String, val displayName: String, val bytes: Long)

    fun saveToInput(context: Context, folder: SharedFolder, uri: Uri): Saved {
        if (!folder.hasAccess()) throw IllegalStateException("All files access is required to attach files into Documents/Farrow/Input")
        if (!folder.ensure()) throw IllegalStateException("Could not create Documents/Farrow")
        val cr = context.contentResolver
        val rawName = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val name = sanitize(rawName)
        val dest = unique(File(folder.input, name))
        cr.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read the selected file")
        val rel = "${SharedFolder.INPUT}/${dest.name}"
        return Saved(rel, "${SharedFolder.DISPLAY_PATH}/$rel", dest.name, dest.length())
    }

    /** Message line the chat prepends so the agent sees a sandboxed path. */
    /** Model-facing line; the UI hides it via [AttachmentText]. */
    fun messagePrefix(saved: Saved): String = AttachmentText.prefix(saved.relativePath, saved.bytes)

    const val DEFAULT_PROMPT = AttachmentText.DEFAULT_PROMPT
    fun forDisplay(content: String): AttachmentText.Display = AttachmentText.forDisplay(content)
    fun humanSize(bytes: Long): String = AttachmentText.humanSize(bytes)

    internal fun sanitize(name: String): String {
        val base = name.replace(Regex("[\\\\/\\u0000]"), "_").trim().ifEmpty { "attachment" }
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ \\-()\\[\\]+]"), "_").take(120).trim().ifEmpty { "attachment" }
        return if (cleaned.startsWith(".")) "file$cleaned" else cleaned
    }

    internal fun unique(file: File): File {
        if (!file.exists()) return file
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            val c = File(file.parentFile, "$stem-$i$ext")
            if (!c.exists()) return c
            i++
        }
    }
}
