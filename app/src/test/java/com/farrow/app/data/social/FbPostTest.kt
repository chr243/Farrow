package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** fb_post (facebook.json v2) against a fake bridge that behaves like the Facebook home page + "Create post" dialog. */
class FbPostTest {
    private val server = MiniHttpServer()
    private val cmds = mutableListOf<String>()
    private val navs = mutableListOf<String>()
    @Volatile var url = "https://www.facebook.com/"
    @Volatile var popup = false
    /** The opener is only on this URL (null = everywhere). */
    @Volatile var openerOnlyOn: String? = null
    @Volatile var dialogOpens = true
    @Volatile var dialogOpen = false
    @Volatile var postClickBroken = false
    @Volatile var posted = false
    @Volatile var editor = ""
    @Volatile var lastFind = ""
    val keys = mutableListOf<String>()

    @After fun stop() = server.stop()

    private fun config(shrink: Boolean = false): SiteConfig {
        val f = listOf("src/main/assets/selectors/facebook.json", "app/src/main/assets/selectors/facebook.json").map(::File).first { it.exists() }
        var raw = f.readText()
        if (shrink) raw = raw.replace("45000", "3000")
        return Json { ignoreUnknownKeys = true }.decodeFromString(SiteConfig.serializer(), raw)
    }

    private fun reply(ok: Boolean, data: JsonElement? = null, stderr: String = "", stdout: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", stdout); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()
    private fun result(v: String) = reply(true, buildJsonObject { put("result", v) })

    private fun activate() {
        when (lastFind) {
            "opener" -> if (dialogOpens) dialogOpen = true
            "post" -> if (dialogOpen) { posted = true; dialogOpen = false }
            "popup" -> popup = false
        }
    }

    private fun startBridge(): BridgeClient {
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(cmds) { cmds += cmd }
            val body = when (cmd) {
                "ready" -> reply(true, buildJsonObject { put("eval_ok", true) })
                "nav" -> { url = args["url"]!!.jsonPrimitive.content; synchronized(navs) { navs += url }
                    reply(true, buildJsonObject { put("url", url); put("matched", true); put("seconds", 2) }) }
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    val call = e.substringAfterLast("\nreturn ", "")
                    when {
                        call.startsWith("B.findText(") -> when {
                            call.contains("on your mind") -> {
                                val visible = !popup && (openerOnlyOn == null || url.contains(openerOnlyOn!!))
                                if (visible) { lastFind = "opener"; result("top") } else result("no")
                            }
                            call.contains("Publier|Publish") -> if (dialogOpen) { lastFind = "post"; result("top") } else result("no")
                            call.contains("Suivant") -> result("no")
                            call.contains("Not now") || call.contains("cookies") -> if (popup && call.contains("Not now")) { lastFind = "popup"; result("top") } else result("no")
                            else -> result("no")
                        }
                        call.startsWith("B.clickMarked(") -> { activate(); result("ok") }
                        call.startsWith("String(B.exists(") -> result(dialogOpen.toString())
                        call.startsWith("B.posted(") -> result(if (dialogOpen) "open" else "closed")
                        e.contains("execCommand('insertText'") -> { editor = "hello fb"; result("inserted") }
                        e.contains("innerText") -> result(editor)
                        else -> result("")
                    }
                }
                "click" -> {
                    val sel = args["selector"]!!.jsonPrimitive.content
                    if (sel == DomFinder.TARGET_CSS && lastFind == "post" && postClickBroken) reply(false, stderr = "click failed: not clickable")
                    else { if (sel == DomFinder.TARGET_CSS) activate(); reply(true) }
                }
                "focus" -> {
                    val sel = args["selector"]!!.jsonPrimitive.content
                    if (sel == DomFinder.TARGET_CSS) {
                        if (lastFind == "post" && postClickBroken) reply(false, stderr = "element missing") else { activate(); reply(true, stdout = "clicked") }
                    } else reply(true, stdout = "focused")
                }
                "editor_type" -> { editor = args["text"]!!.jsonPrimitive.content; reply(true, buildJsonObject { put("method", "xdotool") }) }
                "key" -> { val k = args["keys"]!!.jsonPrimitive.content; synchronized(keys) { keys += k }
                    if (k == "ctrl+Return" && dialogOpen) { posted = true; dialogOpen = false }; reply(true) }
                "press" -> reply(true)
                else -> reply(false, stderr = "unknown cmd: $cmd")
            }
            res.reply(200, body)
        }
        server.start()
        return BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })
    }

    @Test fun `posts via the composer, dismissing a Not now popup, with keyboard navigation only`() = runBlocking {
        val bridge = startBridge()
        popup = true
        val a = SocialAutomation(config(), bridge)
        a.ensureBrowserReady(30)
        a.post("hello fb")
        assertTrue(posted)
        assertEquals("hello fb", editor)
        assertEquals("ready", cmds.first())
        assertFalse("TBP goto must not be used", cmds.contains("goto"))
        assertEquals(listOf("https://www.facebook.com/"), navs)
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("1. open the Facebook home page: ok") && log.contains("nav ok"))
        assertTrue(log, log.contains("dismiss a 'Not now' popup: ok") || log.contains("dismissed a popup"))
        assertTrue(log, log.contains("editor_type via xdotool"))
        assertTrue(log, log.contains("compose dialog closed"))
        assertEquals(11, a.lastStepLog.size)
    }

    @Test fun `opener missing on home is found after the most-recent feed fallback URL`() = runBlocking {
        val bridge = startBridge()
        openerOnlyOn = "sk=h_chr"
        val a = SocialAutomation(config(shrink = true), bridge)
        a.post("hello fb")
        assertTrue(posted)
        assertTrue(navs.toString(), navs.any { it.contains("?sk=h_chr") })
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("not found → nav ok"))
    }

    @Test fun `broken Post click falls back to JS and then ctrl+Return`() = runBlocking {
        val bridge = startBridge()
        postClickBroken = true
        val a = SocialAutomation(config(), bridge).apply { clickTryMaxMs = 500 }
        a.post("hello fb")
        assertTrue(posted)
        assertEquals(listOf("ctrl+Return"), keys)
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("TBP click failed") && log.contains("key ctrl+Return"))
    }

    @Test fun `dialog that never opens fails with the per-step log`() = runBlocking {
        val bridge = startBridge()
        dialogOpens = false
        val e = runCatching { SocialAutomation(config(shrink = true), bridge).post("hi") }.exceptionOrNull()
        assertTrue(e.toString(), e is StepFailedException)
        val m = e!!.message!!
        assertTrue(m, m.contains("Step 5/11") && m.contains("Step log:") && m.contains("4. open the composer"))
        assertTrue(m, m.contains("The 'Create post' dialog did not open"))
        assertFalse(posted)
    }
}
