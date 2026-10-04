package com.farrow.app.data.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.farrow.app.agent.tools.ImageEncoder
import com.farrow.app.agent.tools.ImageScale
import java.io.ByteArrayOutputStream

/** PNG → JPEG downscaled to fit [maxSide] (subsampled decode first so big full-page shots stay cheap). */
class AndroidImageEncoder : ImageEncoder {
    override fun toJpeg(png: ByteArray, maxSide: Int, quality: Int): ImageEncoder.Encoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (w, h) = ImageScale.fit(src.width, src.height, maxSide)
        val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return ImageEncoder.Encoded(out.toByteArray(), w, h)
    }
}
