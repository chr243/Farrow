package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

/** Downscales a PNG to a JPEG (Android: BitmapFactory; tests: a fake). */
interface ImageEncoder {
    data class Encoded(val jpeg: ByteArray, val width: Int, val height: Int)
    fun toJpeg(png: ByteArray, maxSide: Int, quality: Int = 80): Encoded?
}

object ImageScale {
    /** Size that fits [maxSide] on the longer edge, keeping the aspect ratio (never upscales). */
    fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val long = maxOf(w, h)
        if (long <= maxSide) return w to h
        val f = maxSide.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }
}

/**
 * web_screenshot: screenshot of the internal browser page (same bridge path as the X/FB error cards). The image is saved
 * under app storage and shown in the tool card. If the active model accepts images, the next model message carries it
 * (≈1024 px JPEG data URL, see [ScreenshotAttach]); otherwise the result lists URL, title, visible text and clickables.
 */
class WebScreenshotTool(
    private val bridge: BridgeClient,
    private val dir: File,
    private val encoder: ImageEncoder,
    private val activeModelSeesImages: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool {
    override val name = NAME
    override val description = "Internal browser (no accessibility permission needed): take a screenshot of the current web page. " +
        "If your model can see images, the screenshot is attached to the next message; otherwise you get the page URL, title, " +
        "visible text and a list of clickable elements."
    override val parameters = schema(emptyList(),
        "full_page" to prop("boolean", "Capture the whole page, not just the viewport"),
        "selector" to prop("string", "Optional CSS selector: capture only this element"))

    override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        if (!bridge.isAvailable()) return errorJson("Browser bridge is not running. Open Settings > Internal browser setup.")
        val full = args.bool("full_page") == true
        val selector = args.str("selector")?.takeIf { it.isNotBlank() }
        val shot = try { bridge.screenshotShot(full, selector) } catch (e: IOException) { Result.failure(e) }
        val s = shot.getOrElse { return errorJson("screenshot failed: ${it.message}") }
        val info = pageInfo()
        dir.mkdirs()
        val base = "shot-${ctx.taskId}-${clock()}"
        val png = File(dir, "$base.png").apply { writeBytes(s.png) }
        val enc = runCatching { encoder.toJpeg(s.png, MAX_SIDE) }.getOrNull()
        val jpg = enc?.let { File(dir, "$base.jpg").apply { writeBytes(it.jpeg) } }
        val sees = runCatching { activeModelSeesImages() }.getOrDefault(false)
        val attach = sees && jpg != null
        return buildJsonObject {
            put("ok", true)
            put("url", info?.get("url") ?: JsonNull); put("title", info?.get("title") ?: JsonNull)
            put("mode", s.mode)
            if (s.notes.isNotEmpty()) put("notes", JsonArray(s.notes.map { JsonPrimitive(it) }))
            put(IMAGE_PATH, png.absolutePath)
            jpg?.let { put(MODEL_IMAGE_PATH, it.absolutePath) }
            enc?.let { put("width", it.width); put("height", it.height) }
            put(ATTACHED, attach)
            if (attach) put("note", "The screenshot is attached to the next message as an image.")
            else {
                put("text_excerpt", info?.get("text") ?: JsonPrimitive(""))
                put("clickables", info?.get("clickables") ?: JsonArray(emptyList()))
                put("note", "The current model can't see images, so here is the page as text instead.")
            }
        }.toString()
    }

    private suspend fun pageInfo(): JsonObject? = try {
        val r = bridge.eval(PAGE_INFO_JS, 20)
        r.valueString()?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (e: Exception) { null }

    companion object {
        const val NAME = "web_screenshot"
        const val MAX_SIDE = 1024
        const val IMAGE_PATH = "image_path"
        const val MODEL_IMAGE_PATH = "model_image_path"
        const val ATTACHED = "image_attached"

        /** URL, title, ~2000 chars of visible text and up to 40 visible clickable elements. */
        val PAGE_INFO_JS = """(()=>{const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);
            return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};
            const t=e=>String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim().slice(0,80);
            const out=[];for(const e of document.querySelectorAll('a[href],button,[role="button"],input,textarea,select,[onclick]')){
              if(out.length>=40)break;if(!vis(e))continue;const o={tag:e.tagName.toLowerCase(),text:t(e)};
              if(e.id)o.id=e.id;const tid=e.getAttribute('data-testid');if(tid)o.testid=tid;
              if(e.href)o.href=String(e.href).slice(0,200);if(e.type&&e.tagName==='INPUT')o.type=e.type;out.push(o)}
            return JSON.stringify({url:location.href,title:document.title,
              text:String(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,2000),clickables:out})})()""".trimIndent()
    }
}

/** Attaches the screenshot of a just-finished web_screenshot call to the next model request (only that one). */
object ScreenshotAttach {
    /**
     * [trailingToolResults] = tool results after the last assistant message (not yet seen by the model). Returns the
     * JPEG paths of web_screenshot results flagged image_attached.
     */
    fun pending(trailingToolResults: List<Pair<String?, String?>>): List<String> = trailingToolResults.mapNotNull { (name, content) ->
        if (name != WebScreenshotTool.NAME || content == null) return@mapNotNull null
        val o = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return@mapNotNull null
        if (o[WebScreenshotTool.ATTACHED]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
        o[WebScreenshotTool.MODEL_IMAGE_PATH]?.jsonPrimitive?.contentOrNull
    }

    fun dataUrl(jpeg: ByteArray) = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
}
