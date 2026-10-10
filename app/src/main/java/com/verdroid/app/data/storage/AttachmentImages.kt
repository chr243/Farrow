package com.verdroid.app.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns an attached image (jpg/png/webp/gif, first frame) under Documents/Farrow into a JPEG data URL for vision
 * models: downscaled so the long side is ≤ [MAX_SIDE] px (keeps requests small on free models), transparency
 * flattened onto white. Results are cached by path + size + mtime so each agent step doesn't re-encode.
 */
object AttachmentImages {
    const val MAX_SIDE = 1280
    private const val QUALITY = 85
    private val cache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 6
    }

    fun dataUrl(folder: SharedFolder, relativePath: String): String? {
        if (!folder.hasAccess()) return null
        val f = runCatching { SharedFolderPaths.resolve(folder.root, relativePath) }.getOrNull() ?: return null
        if (!f.isFile) return null
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val url = encode(f) ?: return null
        synchronized(cache) { cache[key] = url }
        return url
    }

    /** Small bitmap for chips (null if not decodable). */
    fun thumbnail(file: File, maxSide: Int): Bitmap? = decode(file, maxSide)

    private fun decode(f: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() } else bmp
    }

    private fun encode(f: File): String? = runCatching {
        val bmp = decode(f, MAX_SIDE) ?: return null
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        bmp.recycle()
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        flat.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}

/** Path check shared by image loading: only files inside the Documents/Farrow tree. */
object SharedFolderPaths {
    fun resolve(root: File, relativePath: String): File {
        val f = File(root, relativePath).canonicalFile
        val r = root.canonicalFile
        require(f.path == r.path || f.path.startsWith(r.path + File.separator)) { "outside Documents/Farrow" }
        return f
    }
}
