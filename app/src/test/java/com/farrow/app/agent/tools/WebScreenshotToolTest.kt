package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import com.farrow.app.data.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WebScreenshotToolTest {
    private val server = MiniHttpServer()
    private val dir = Files.createTempDirectory("shots").toFile()
    private var lastArgs: JsonObject? = null
    @After fun stop() { server.stop(); dir.deleteRecursively() }

    private val fakeEncoder = object : ImageEncoder {
        override fun toJpeg(png: ByteArray, maxSide: Int, quality: Int) = ImageEncoder.Encoded(byteArrayOf(1, 2, 3), 1024, 576)
    }

    private fun bridge(): BridgeClient {
        server.route("/health") { _, res -> res.reply(200, """{"ok":true,"version":"1.9.0","tbp":true,"daemon":true}""") }
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val body = when (o["cmd"]!!.jsonPrimitive.content) {
                "screenshot" -> { lastArgs = o["args"]?.jsonObject
                    """{"ok":true,"code":0,"stdout":"","stderr":"","data":{"png_base64":"iVBORw0K","mode":"full_page","notes":[]}}""" }
                "eval" -> """{"ok":true,"code":0,"stdout":"","stderr":"","data":{"result":${JsonPrimitive("""{"url":"https://example.com/","title":"Example","text":"Hello page","clickables":[{"tag":"a","text":"More","href":"https://iana.org"}]}""")}}}"""
                else -> """{"ok":false,"code":1,"stdout":"","stderr":"unknown cmd"}"""
            }
            res.reply(200, body)
        }
        server.start()
        return BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })
    }

    private fun tool(b: BridgeClient, sees: Boolean) = WebScreenshotTool(b, dir, fakeEncoder, { sees }, clock = { 42L })

    @Test fun `vision model gets the image attached and the card gets a file`() = runBlocking {
        val b = bridge()
        assertTrue(b.isAvailable())
        val r = Json.parseToJsonElement(tool(b, true).execute(buildJsonObject { put("full_page", true); put("selector", "#main") }, ToolContext(7))).jsonObject
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(true, r[WebScreenshotTool.ATTACHED]!!.jsonPrimitive.boolean)
        assertEquals("https://example.com/", r["url"]!!.jsonPrimitive.content)
        assertNull(r["clickables"])
        assertTrue(java.io.File(r[WebScreenshotTool.IMAGE_PATH]!!.jsonPrimitive.content).exists())
        assertTrue(r[WebScreenshotTool.MODEL_IMAGE_PATH]!!.jsonPrimitive.content.endsWith("shot-7-42.jpg"))
        assertEquals(true, lastArgs!!["full_page"]!!.jsonPrimitive.boolean)
        assertEquals("#main", lastArgs!!["selector"]!!.jsonPrimitive.content)
    }

    @Test fun `text-only model gets url title text and clickables`() = runBlocking {
        val b = bridge()
        val r = Json.parseToJsonElement(tool(b, false).execute(JsonObject(emptyMap()), ToolContext(7))).jsonObject
        assertEquals(false, r[WebScreenshotTool.ATTACHED]!!.jsonPrimitive.boolean)
        assertEquals("Hello page", r["text_excerpt"]!!.jsonPrimitive.content)
        assertEquals("More", r["clickables"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(r["note"]!!.jsonPrimitive.content.contains("can't see images"))
    }

    @Test fun `only the trailing flagged screenshot is attached`() {
        val yes = """{"ok":true,"image_attached":true,"model_image_path":"/a.jpg"}"""
        val no = """{"ok":true,"image_attached":false,"model_image_path":"/b.jpg"}"""
        assertEquals(listOf("/a.jpg"), ScreenshotAttach.pending(listOf("web_screenshot" to yes, "web_screenshot" to no, "web_scrape" to yes)))
        assertTrue(ScreenshotAttach.dataUrl(byteArrayOf(1)).startsWith("data:image/jpeg;base64,"))
    }

    @Test fun `scale fits 1024 on the long side`() {
        assertEquals(1024 to 576, ImageScale.fit(1920, 1080, 1024))
        assertEquals(576 to 1024, ImageScale.fit(1080, 1920, 1024))
        assertEquals(800 to 600, ImageScale.fit(800, 600, 1024))
    }

    @Test fun `image messages go out as content parts and are stripped for text-only models`() {
        val json = Json { explicitNulls = false; encodeDefaults = true }
        val req = ChatRequest("m", listOf(ApiMessage("user", "look", images = listOf("data:image/jpeg;base64,AA")), ApiMessage("user", "hi")))
        val o = json.encodeToJsonElement(ChatRequest.serializer(), req).jsonObject
        val m0 = o["messages"]!!.jsonArray[0].jsonObject
        assertNull(m0["images"])
        val parts = m0["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AA", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("hi", o["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        val stripped = ImageStrip.strip(req.messages)
        assertNull(stripped[0].images)
        assertTrue(stripped[0].content!!.contains("can't see images"))
    }

    @Test fun `model metadata parsing and caching`() = runBlocking {
        val or = """{"data":[{"id":"openrouter/free","architecture":{"input_modalities":["text","image"]}},{"id":"qwen/qwen3-coder:free","architecture":{"input_modalities":["text"]}}]}"""
        val kilo = """{"data":[{"id":"kilo-auto/free","architecture":{"input_modalities":["text"]}}]}"""
        var fetches = 0
        val saved = mutableMapOf<String, Boolean>()
        val store = object : CapsStore {
            override fun load() = 0L to saved.toMap()
            override fun save(at: Long, caps: Map<String, Boolean>) { saved.clear(); saved.putAll(caps) }
        }
        var now = 1_000L
        val caps = VisionCaps({ url -> fetches++; if (url.contains("kilo")) kilo else or }, store, clock = { now })
        assertTrue(caps.supportsImages("openrouter/free"))
        assertFalse(caps.supportsImages("qwen/qwen3-coder:free"))
        assertFalse(caps.supportsImages("kilo:kilo-auto/free"))
        assertFalse(caps.supportsImages("unknown/model"))
        assertEquals(2, fetches) // cached
        assertEquals(true, saved["openrouter/free"])
        now += VisionCaps.TTL_MS + 1
        caps.supportsImages("openrouter/free")
        assertEquals(4, fetches)
        assertEquals(mapOf("x" to true), ModelCaps.parse("""{"data":[{"id":"x","architecture":{"modality":"text+image->text"}}]}"""))
    }
}
