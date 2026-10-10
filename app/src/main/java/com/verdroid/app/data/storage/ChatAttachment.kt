package com.verdroid.app.data.storage

import com.verdroid.app.domain.model.AttachmentText

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Every chat "+" pick lands in Documents/Verdroid/Input for the agent, which asks what to do with it unless the user said.
 * The Android part only resolves the SAF [Uri]; the copy itself ([copyToInput]) is pure java.io and JVM-tested.
 */
object ChatAttachment {
    data class Saved(val relativePath: String, val absolutePath: String, val displayName: String, val bytes: Long)

    /** Thrown when All files access is missing; the chat offers a Grant button and retries the same pick. */
    class NoAccessException : IllegalStateException(
        "Verdroid needs All files access to put attachments in Documents/Verdroid/Input")

    fun saveToInput(context: Context, folder: SharedFolder, uri: Uri): Saved {
        if (!folder.hasAccess()) throw NoAccessException()
        val cr = context.contentResolver
        // Keep read access across process death / the All-files settings round trip (OpenDocument grants are persistable).
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val queried = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val docId = runCatching { if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null }.getOrNull()
        val sourcePath = if (uri.scheme == "file") uri.path else docId
        return copyToInput(folder, queried?.takeIf { it.isNotBlank() } ?: fallbackName(uri.lastPathSegment), sourcePath) {
            cr.openInputStream(uri)
        }
    }

    /**
     * Copies [open]'s stream into Input/ (creating Documents/Verdroid/Input/Output when missing) under a sanitised,
     * non-clashing name. Writes to a hidden temp file and renames, so a failed/cancelled copy never leaves a partial file.
     * When [sourcePath] (a file path or an ExternalStorage doc id like `primary:Documents/Verdroid/Input/a.pdf`) already
     * points at a file in Input/, that file is reused instead of duplicated.
     */
    fun copyToInput(folder: SharedFolder, rawName: String, sourcePath: String?, open: () -> InputStream?): Saved {
        if (!folder.hasAccess()) throw NoAccessException()
        folder.ensure()
        val inputDir = folder.input
        if (!inputDir.isDirectory && !inputDir.mkdirs() && !inputDir.isDirectory)
            throw IllegalStateException("Could not create ${SharedFolder.DISPLAY_PATH}/${SharedFolder.INPUT}")
        existingInInput(inputDir, sourcePath)?.let { return saved(it) }

        val dest = unique(File(inputDir, sanitize(rawName)))
        val tmp = File(inputDir, ".${dest.name}.${System.nanoTime()}.part")
        try {
            val input = open() ?: throw IllegalStateException("Could not read the selected file")
            input.use { src -> tmp.outputStream().use { out -> src.copyTo(out) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = false)
                tmp.delete()
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (e is IOException) throw IllegalStateException("Could not copy the file into Input/: ${e.message}", e)
            throw e
        }
        if (!dest.isFile) throw IllegalStateException("Copy into Input/ failed")
        return saved(dest)
    }

    private fun saved(f: File): Saved {
        val rel = "${SharedFolder.INPUT}/${f.name}"
        return Saved(rel, "${SharedFolder.DISPLAY_PATH}/$rel", f.name, f.length())
    }

    /** The Input/ file [sourcePath] refers to, if the user picked something that is already in Input/. */
    internal fun existingInInput(inputDir: File, sourcePath: String?): File? {
        val p = sourcePath?.replace('\\', '/')?.substringAfter(':') ?: return null // "primary:Documents/…" → "Documents/…"
        val marker = "Documents/Verdroid/${SharedFolder.INPUT}/"
        val idx = p.indexOf(marker)
        if (idx < 0) return null
        val rest = p.substring(idx + marker.length)
        if (rest.isEmpty() || rest.contains('/')) return null // only direct children of Input/
        val f = File(inputDir, rest)
        val ok = runCatching { f.canonicalFile.parentFile == inputDir.canonicalFile }.getOrDefault(false)
        return f.takeIf { ok && it.isFile }
    }

    /** Name from a Uri's last segment when the provider gives no DISPLAY_NAME (`primary:Download/a.pdf` → `a.pdf`). */
    internal fun fallbackName(lastSegment: String?): String =
        lastSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "attachment"

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
