package com.verdroid.app.ui.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.verdroid.app.agent.tools.ChartMath
import com.verdroid.app.agent.tools.ChartPngRenderer
import com.verdroid.app.agent.tools.ChartSpec
import com.verdroid.app.agent.tools.ChartSpecs
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** ARGB colors for [ChartPainter]: background, text, grid, and the series palette. */
data class ChartColors(val background: Int, val text: Int, val grid: Int, val series: IntArray) {
    fun of(i: Int) = series[i % series.size]

    companion object {
        val PALETTE = intArrayOf(0xFF4F6BED.toInt(), 0xFFE8743B.toInt(), 0xFF19A979.toInt(), 0xFFED4A7B.toInt(), 0xFF945ECF.toInt(),
            0xFF13A4B4.toInt(), 0xFFD6A31B.toInt(), 0xFF6C8893.toInt(), 0xFFBF399E.toInt(), 0xFF5899DA.toInt(), 0xFF8AB33F.toInt(), 0xFFEE6868.toInt())
        /** PNG export: light theme (declared after PALETTE: companion init order). */
        val LIGHT = ChartColors(0xFFFFFFFF.toInt(), 0xFF1D1B20.toInt(), 0xFFE0DDE6.toInt(), PALETTE)
    }
}

/**
 * Draws a [ChartSpec] on an android.graphics [Canvas]: the chat card (via Compose's `nativeCanvas`) and the PNG export
 * use the same code, so the shared image looks like what the user saw. [d] = pixels per dp.
 */
class ChartPainter(private val spec: ChartSpec, private val colors: ChartColors, private val d: Float) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.text; textSize = 11f * d }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.text; textSize = 15f * d; typeface = Typeface.DEFAULT_BOLD }
    private val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.grid; strokeWidth = 1f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }

    fun draw(c: Canvas, w: Float, h: Float, drawBackground: Boolean = true) {
        if (drawBackground) c.drawColor(colors.background)
        val pad = 10f * d
        var top = pad
        if (spec.title.isNotBlank()) { c.drawText(ellipsize(spec.title, title, w - 2 * pad), pad, top + title.textSize, title); top += title.textSize + 8f * d }
        val legendNames = when (spec.type) { "pie" -> spec.labels; else -> if (spec.series.size > 1) spec.series.map { it.name } else emptyList() }
        if (legendNames.isNotEmpty() && spec.type != "pie") top = legend(c, legendNames, pad, top, w - pad)
        val area = RectF(pad, top, w - pad, h - pad)
        when (spec.type) {
            "pie" -> pie(c, area)
            "table" -> table(c, area)
            else -> xy(c, area)
        }
    }

    private fun legend(c: Canvas, names: List<String>, left: Float, top: Float, right: Float, startIndex: Int = 0): Float {
        var x = left; var y = top + text.textSize
        val sw = 9f * d
        names.forEachIndexed { i, raw ->
            val n = ellipsize(raw.ifBlank { "Series ${i + 1}" }, text, right - left - sw - 6f * d)
            val iw = sw + 4f * d + text.measureText(n) + 12f * d
            if (x + iw > right && x > left) { x = left; y += text.textSize + 6f * d }
            fill.color = colors.of(startIndex + i)
            c.drawRoundRect(RectF(x, y - sw, x + sw, y), 2f * d, 2f * d, fill)
            c.drawText(n, x + sw + 4f * d, y, text)
            x += iw
        }
        return y + 8f * d
    }

    private fun xy(c: Canvas, a: RectF) {
        val scatter = spec.type == "scatter"
        val all = spec.series.flatMap { it.values }
        val yAxis = ChartMath.niceAxis(all.min(), all.max(), includeZero = spec.type == "bar")
        val xs = if (scatter) spec.labels.map { ChartSpecs.number(JsonPrimitive(it)) ?: 0.0 } else emptyList()
        val xAxis = if (scatter) ChartMath.niceAxis(xs.min(), xs.max(), includeZero = false) else null
        val yTickText = yAxis.ticks.map(ChartMath::format)
        val yLabelW = if (spec.yLabel.isNotBlank()) text.textSize + 4f * d else 0f
        val left = a.left + yLabelW + (yTickText.maxOf { text.measureText(it) }) + 6f * d
        val bottomText = text.textSize + 6f * d + if (spec.xLabel.isNotBlank()) text.textSize + 6f * d else 0f
        val plot = RectF(left, a.top + 4f * d, a.right, a.bottom - bottomText)
        if (plot.width() <= 10 || plot.height() <= 10) return
        fun py(v: Double) = (plot.bottom - yAxis.frac(v) * plot.height()).toFloat()
        // grid + y ticks
        yAxis.ticks.forEachIndexed { i, t ->
            val y = py(t)
            c.drawLine(plot.left, y, plot.right, y, grid)
            c.drawText(yTickText[i], left - 6f * d - text.measureText(yTickText[i]), y + text.textSize / 3, text)
        }
        if (spec.yLabel.isNotBlank()) {
            c.save(); c.rotate(-90f, a.left + text.textSize, plot.centerY())
            val t = ellipsize(spec.yLabel, text, plot.height())
            c.drawText(t, a.left + text.textSize - text.measureText(t) / 2, plot.centerY(), text); c.restore()
        }
        if (spec.xLabel.isNotBlank()) {
            val t = ellipsize(spec.xLabel, text, plot.width())
            c.drawText(t, plot.centerX() - text.measureText(t) / 2, a.bottom - 2f * d, text)
        }
        val n = spec.labels.size
        val labelY = plot.bottom + text.textSize + 4f * d
        if (scatter) {
            xAxis!!.ticks.forEach { t ->
                val x = (plot.left + xAxis.frac(t) * plot.width()).toFloat(); val s = ChartMath.format(t)
                c.drawText(s, x - text.measureText(s) / 2, labelY, text)
            }
            spec.series.forEachIndexed { si, s ->
                fill.color = colors.of(si)
                s.values.forEachIndexed { k, v -> c.drawCircle((plot.left + xAxis.frac(xs[k]) * plot.width()).toFloat(), py(v), 4f * d, fill) }
            }
            return
        }
        val slot = plot.width() / n
        val maxLabel = minOf(slot * 3, 90f * d)
        val stride = ChartMath.labelStride(n, slot, spec.labels.maxOf { minOf(text.measureText(it), maxLabel) })
        spec.labels.forEachIndexed { k, l ->
            if (k % stride != 0) return@forEachIndexed
            val s = ellipsize(l, text, maxLabel)
            c.drawText(s, plot.left + slot * (k + 0.5f) - text.measureText(s) / 2, labelY, text)
        }
        if (spec.type == "bar") {
            val groupW = slot * 0.75f
            val bw = groupW / spec.series.size
            val zero = py(0.0.coerceIn(yAxis.min, yAxis.max))
            val showValues = n * spec.series.size <= 16 && bw >= text.measureText("0000")
            spec.series.forEachIndexed { si, s ->
                fill.color = colors.of(si)
                s.values.forEachIndexed { k, v ->
                    val x0 = plot.left + slot * k + (slot - groupW) / 2 + bw * si
                    val y = py(v)
                    c.drawRoundRect(RectF(x0 + 1f * d, minOf(y, zero), x0 + bw - 1f * d, maxOf(y, zero)), 3f * d, 3f * d, fill)
                    if (showValues) {
                        val t = ChartMath.format(v)
                        c.drawText(t, x0 + bw / 2 - text.measureText(t) / 2, if (v >= 0) y - 3f * d else y + text.textSize, text)
                    }
                }
            }
        } else {
            spec.series.forEachIndexed { si, s ->
                stroke.color = colors.of(si); fill.color = colors.of(si)
                val path = Path()
                s.values.forEachIndexed { k, v ->
                    val x = plot.left + slot * (k + 0.5f); val y = py(v)
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, stroke)
                if (n <= 40) s.values.forEachIndexed { k, v -> c.drawCircle(plot.left + slot * (k + 0.5f), py(v), 3.5f * d, fill) }
            }
        }
    }

    private fun pie(c: Canvas, a: RectF) {
        val values = spec.series[0].values
        val sum = values.sum()
        val wide = a.width() > a.height() * 1.4f
        val dia = if (wide) minOf(a.height(), a.width() * 0.5f) else minOf(a.width(), a.height() * 0.6f)
        val oval = if (wide) RectF(a.left, a.top, a.left + dia, a.top + dia) else RectF(a.centerX() - dia / 2, a.top, a.centerX() + dia / 2, a.top + dia)
        ChartMath.pieSlices(values).forEachIndexed { i, (start, sweep) ->
            fill.color = colors.of(i); c.drawArc(oval, start, sweep, true, fill)
        }
        val names = spec.labels.mapIndexed { i, l -> "$l  ${ChartMath.format(values[i])} (${Math.round(values[i] / sum * 100)}%)" }
        if (wide) {
            var y = a.top + text.textSize
            val x = oval.right + 14f * d
            names.forEachIndexed { i, n ->
                if (y > a.bottom) return
                fill.color = colors.of(i)
                c.drawRoundRect(RectF(x, y - 9f * d, x + 9f * d, y), 2f * d, 2f * d, fill)
                c.drawText(ellipsize(n, text, a.right - x - 14f * d), x + 13f * d, y, text)
                y += text.textSize + 7f * d
            }
        } else legend(c, names, a.left, oval.bottom + 6f * d, a.right)
    }

    private fun table(c: Canvas, a: RectF) {
        val (header, rows) = spec.toRows()
        val all = listOf(header) + rows
        val colW = FloatArray(header.size) { k -> minOf(all.maxOf { text.measureText(it.getOrElse(k) { "" }) } + 16f * d, 200f * d) }
        val rowH = text.textSize + 12f * d
        var y = a.top
        all.forEachIndexed { r, row ->
            if (y + rowH > a.bottom) return
            if (r == 0) { fill.color = colors.grid; c.drawRect(a.left, y, a.left + colW.sum(), y + rowH, fill) }
            else if (r % 2 == 0) { fill.color = (colors.grid and 0x00FFFFFF) or 0x66000000; c.drawRect(a.left, y, a.left + colW.sum(), y + rowH, fill) }
            var x = a.left
            row.forEachIndexed { k, cell ->
                val p = if (r == 0) bold else text
                c.drawText(ellipsize(cell, p, colW[k] - 12f * d), x + 8f * d, y + rowH - 6f * d - 2f * d, p)
                x += colW[k]
            }
            y += rowH
        }
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (max <= 0f || p.measureText(s) <= max) return s
        var end = s.length
        while (end > 0 && p.measureText(s.substring(0, end) + "…") > max) end--
        return s.substring(0, end) + "…"
    }
}

/** PNG export (1080 px wide, light theme) for the `chart` tool. */
class AndroidChartPng(private val widthPx: Int = 1080, private val heightPx: Int = 720) : ChartPngRenderer {
    override fun render(spec: ChartSpec, file: File): Boolean {
        val h = if (spec.type == "table") minOf(4000, (spec.labels.size + 2) * 70 + 120) else heightPx
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        return try {
            ChartPainter(spec, ChartColors.LIGHT, widthPx / 400f).draw(Canvas(bmp), widthPx.toFloat(), h.toFloat())
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bmp.recycle() }
    }
}
