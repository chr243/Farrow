package com.farrow.app.agent.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Serializable
data class ChartSeries(val name: String = "", val values: List<Double>)

/** The `chart` tool's input, validated by [ChartSpecs.parse]; also stored in the tool result for the chat to render. */
@Serializable
data class ChartSpec(
    val type: String,
    val title: String = "",
    @SerialName("x_label") val xLabel: String = "",
    @SerialName("y_label") val yLabel: String = "",
    val labels: List<String> = emptyList(),
    val series: List<ChartSeries>,
) {
    /** type=table (and any chart's data as a table): one row per label, one column per series. */
    fun toRows(): Pair<List<String>, List<List<String>>> {
        val header = listOf(xLabel.ifBlank { "" }) + series.mapIndexed { i, s -> s.name.ifBlank { if (series.size == 1) yLabel.ifBlank { "Value" } else "Series ${i + 1}" } }
        val rows = labels.indices.map { r -> listOf(labels[r]) + series.map { s -> s.values.getOrNull(r)?.let(ChartMath::format) ?: "" } }
        return header to rows
    }
}

/** Parsing + validation of [ChartSpec] from tool arguments (lenient about how models send numbers and arrays). */
object ChartSpecs {
    val TYPES = listOf("bar", "line", "pie", "scatter", "table")
    const val MAX_SERIES = 12
    const val MAX_POINTS = 200
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** "1,299.50", "$12", "€ 3.5", "45%", "-2" → number; null when there is no number. */
    fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        p.doubleOrNull?.takeIf { !p.isString }?.let { return it }
        val s = p.content.trim().replace("\u00a0", "").replace(" ", "")
        val m = Regex("""-?\d[\d,]*(\.\d+)?|-?\.\d+""").find(s) ?: return null
        return m.value.replace(",", "").toDoubleOrNull()
    }

    /** Arrays may come as real JSON arrays or as a JSON string holding one (some models stringify nested args). */
    private fun array(e: JsonElement?): JsonArray? = when (e) {
        is JsonArray -> e
        is JsonPrimitive -> if (e.isString) runCatching { Json.parseToJsonElement(e.content) as? JsonArray }.getOrNull()
            ?: e.content.split(',').map { JsonPrimitive(it.trim()) }.takeIf { e.content.isNotBlank() }?.let(::JsonArray) else null
        else -> null
    }

    private fun text(o: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()

    fun parse(args: JsonObject): Result<ChartSpec> = runCatching {
        val type = text(args, "type").lowercase()
        require(type in TYPES) { "type must be one of ${TYPES.joinToString("|")} (got '${type.ifBlank { "nothing" }}')" }
        val labels = array(args["labels"])?.map { (it as? JsonPrimitive)?.contentOrNull?.trim() ?: it.toString() }.orEmpty()
        val rawSeries = array(args["series"]) ?: throw IllegalArgumentException("series is required: [{\"name\": \"…\", \"values\": [1, 2, 3]}]")
        require(rawSeries.isNotEmpty()) { "series is empty" }
        require(rawSeries.size <= MAX_SERIES) { "at most $MAX_SERIES series" }
        val series = rawSeries.mapIndexed { i, el ->
            val o = when (el) {
                is JsonObject -> el
                is JsonArray -> buildJsonObject { put("values", el) } // a bare values array
                else -> throw IllegalArgumentException("series[$i] must be an object {name, values}")
            }
            val vals = array(o["values"] ?: o["data"]) ?: throw IllegalArgumentException("series[$i].values is required")
            val nums = vals.mapIndexed { k, v -> number(v) ?: throw IllegalArgumentException("series[$i].values[$k] is not a number: $v") }
            require(nums.isNotEmpty()) { "series[$i].values is empty" }
            require(nums.size <= MAX_POINTS) { "series[$i] has more than $MAX_POINTS values" }
            require(nums.all { it.isFinite() }) { "series[$i] has a non-finite value" }
            ChartSeries(text(o, "name", "label"), nums)
        }
        val n = series.maxOf { it.values.size }
        val finalLabels = when {
            labels.isNotEmpty() -> labels
            type == "scatter" -> throw IllegalArgumentException("scatter needs labels = the numeric x values (one per value)")
            else -> (1..n).map { it.toString() }
        }
        series.forEachIndexed { i, s ->
            require(s.values.size == finalLabels.size) { "series[$i] has ${s.values.size} values but there are ${finalLabels.size} labels (one value per label)" }
        }
        when (type) {
            "pie" -> {
                require(series.size == 1) { "pie takes exactly one series (got ${series.size})" }
                require(series[0].values.all { it >= 0 }) { "pie values must not be negative" }
                require(series[0].values.sum() > 0) { "pie values sum to 0" }
            }
            "scatter" -> finalLabels.forEachIndexed { k, l -> requireNotNull(number(JsonPrimitive(l))) { "scatter labels[$k] is not a number: $l" } }
        }
        ChartSpec(type, text(args, "title"), text(args, "x_label", "xLabel"), text(args, "y_label", "yLabel"), finalLabels, series)
    }

    fun toJson(spec: ChartSpec): JsonElement = json.encodeToJsonElement(ChartSpec.serializer(), spec)
    fun fromJson(e: JsonElement?): ChartSpec? = e?.let { runCatching { json.decodeFromJsonElement(ChartSpec.serializer(), it) }.getOrNull() }

    /** The `chart` object of a chart tool result, or null. */
    fun fromResult(resultJson: String?): Pair<ChartSpec, String?>? {
        val o = resultJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val spec = fromJson(o["chart"]) ?: return null
        return spec to (o["image_path"] as? JsonPrimitive)?.contentOrNull
    }
}

/** Axis math shared by the chat renderer and the PNG export. */
object ChartMath {
    data class Axis(val min: Double, val max: Double, val step: Double) {
        val ticks: List<Double> get() {
            val n = ((max - min) / step).toInt()
            return (0..n).map { min + it * step }.map { if (abs(it) < step * 1e-9) 0.0 else it }
        }
        fun frac(v: Double) = if (max == min) 0.5 else (v - min) / (max - min)
    }

    /** "Nice" axis covering [lo]..[hi] with about [target] steps (1/2/5 × 10^k); [includeZero] for bars. */
    fun niceAxis(lo: Double, hi: Double, includeZero: Boolean, target: Int = 5): Axis {
        var a = if (includeZero) minOf(lo, 0.0) else lo
        var b = if (includeZero) maxOf(hi, 0.0) else hi
        if (a == b) { if (a == 0.0) { b = 1.0 } else { val d = abs(a) * 0.1; a -= d; b += d } }
        val raw = (b - a) / target
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
        return Axis(floor(a / step) * step, ceil(b / step) * step, step)
    }

    /** Compact value text: 1234 → "1,234", 1234567 → "1.23M", 0.5 → "0.5", 12.30 → "12.3". */
    fun format(v: Double): String {
        val a = abs(v)
        fun trim(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
        return when {
            a >= 1e9 -> trim("%.2f".format(java.util.Locale.ROOT, v / 1e9)) + "B"
            a >= 1e6 -> trim("%.2f".format(java.util.Locale.ROOT, v / 1e6)) + "M"
            a >= 1e4 || v == floor(v) -> "%,d".format(java.util.Locale.ROOT, Math.round(v))
            a >= 100 -> trim("%.1f".format(java.util.Locale.ROOT, v))
            else -> trim("%.2f".format(java.util.Locale.ROOT, v))
        }
    }

    /** Pie slices as (startDeg, sweepDeg), starting at 12 o'clock (-90°), clockwise; sweeps sum to 360. */
    fun pieSlices(values: List<Double>): List<Pair<Float, Float>> {
        val sum = values.sum()
        var start = -90f
        return values.map { v -> val sweep = (v / sum * 360.0).toFloat(); (start to sweep).also { start += sweep } }
    }

    /** Every k-th x label so they don't overlap ([labelPx] widest label, [slotPx] room per label). */
    fun labelStride(count: Int, slotPx: Float, labelPx: Float): Int =
        if (count <= 1 || slotPx <= 0f) 1 else maxOf(1, ceil((labelPx + 8f) / slotPx).toInt())
}

/** Writes a chart PNG (Android: [com.farrow.app.ui.chart.AndroidChartPng]); null when it could not render. */
fun interface ChartPngRenderer {
    fun render(spec: ChartSpec, file: File): Boolean
}

/**
 * `chart`: the agent's numeric comparisons (prices, scores, shares) rendered natively in the chat as a card (tap =
 * fullscreen, share = PNG). The PNG goes to `filesDir/charts/` so it can be shared or attached later.
 */
class ChartTool(private val dir: File, private val renderer: ChartPngRenderer, private val now: () -> Long = System::currentTimeMillis) : AgentTool {
    override val name = NAME
    override val description = "Show a chart to the user in the chat (rendered natively; the user can open it fullscreen and share it as PNG). " +
        "Use it for numeric comparisons (e.g. prices of several products, values over time, shares of a total). " +
        "type: bar | line | pie | scatter | table. labels = the x categories (scatter: numeric x values); " +
        "series = [{name, values[]}] with one number per label (pie: exactly one series). Numbers must be plain numbers (no currency signs)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("type") { put("type", "string"); putJsonArray("enum") { ChartSpecs.TYPES.forEach { add(it) } } }
            putJsonObject("title") { put("type", "string"); put("description", "Chart title") }
            putJsonObject("x_label") { put("type", "string"); put("description", "X axis label (e.g. Product)") }
            putJsonObject("y_label") { put("type", "string"); put("description", "Y axis label with unit (e.g. Price (EUR))") }
            putJsonObject("labels") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Category labels, one per value") }
            putJsonObject("series") {
                put("type", "array"); put("description", "Data series")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("values") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                    }
                    putJsonArray("required") { add("values") }
                }
            }
        }
        putJsonArray("required") { add("type"); add("series") }
    }

    override suspend fun execute(args: JsonObject): String {
        val spec = ChartSpecs.parse(args).getOrElse { return errorJson("chart: ${it.message}") }
        val file = File(dir, "chart-${now()}.png")
        val png = runCatching { dir.mkdirs(); renderer.render(spec, file) && file.length() > 0 }
            .getOrElse { false }
        return buildJsonObject {
            put("ok", true); put("type", spec.type)
            if (spec.title.isNotBlank()) put("title", spec.title)
            put("points", spec.labels.size); put("series", spec.series.size)
            if (png) put(IMAGE_PATH, file.absolutePath) else put("png_error", "could not export the PNG (the chart still shows in the chat)")
            put("chart", ChartSpecs.toJson(spec))
            put("note", "The chart is shown to the user in the chat. Don't repeat all its numbers; summarize the key comparison.")
        }.toString()
    }

    companion object {
        const val NAME = "chart"
        const val IMAGE_PATH = "image_path"
    }
}
