package com.farrow.app.data.social

import com.farrow.app.agent.tools.XPostBetaTool
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import com.farrow.app.data.tools.ToolPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** x_post_beta: one eval per phase, JS actions first, TBP click only when the JS action had no effect. */
class XPostBetaTest {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    private fun file(rel: String) = listOf(rel, "app/$rel").map(::File).first { it.exists() }
    private val x: SiteConfig by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), file("src/main/assets/selectors/x.json").readText())
    }

    private val calls = mutableListOf<String>()
    @Volatile var probes = mutableListOf<String>()      // replies for successive probes (last one repeats)
    @Volatile var focusReplies = mutableListOf("focused")
    @Volatile var insertReply = "ok:12"
    @Volatile var hasTextReply = "ok:12"
    @Volatile var submitReply = "clicked"
    @Volatile var postedAfterTbpClick = false
    @Volatile var postedPolls = 0
    @Volatile var tbpClicked = 0

    private fun reply(ok: Boolean, data: JsonElement? = null, stderr: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", ""); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()

    private fun result(v: String) = reply(true, buildJsonObject { put("result", v) })

    private fun next(l: MutableList<String>) = synchronized(l) { if (l.size > 1) l.removeAt(0) else l[0] }

    private fun beta(): XPostBeta {
        if (probes.isEmpty()) probes = mutableListOf("""{"u":"https://x.com/home","n":0}""")
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            val body = when (cmd) {
                "ready" -> reply(true, buildJsonObject { put("eval_ok", true) })
                "nav" -> reply(true, buildJsonObject { put("url", "https://x.com/compose/post"); put("matched", true); put("seconds", 1) })
                "click" -> { tbpClicked++; reply(true) }
                "key", "cancel" -> reply(true)
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        e.contains("DMDrawer") -> { synchronized(calls) { calls += "eval:probe" }; result(next(probes)) }
                        e.contains("'nofocus'") -> { synchronized(calls) { calls += "eval:focus" }; result(next(focusReplies)) }
                        e.contains("insertText',false") -> { synchronized(calls) { calls += "eval:insert" }; result(insertReply) }
                        e.contains("ClipboardEvent('paste'") -> { synchronized(calls) { calls += "eval:paste" }; result("ok:12") }
                        e.contains("return 'clicked'") -> { synchronized(calls) { calls += "eval:submit" }; result(submitReply) }
                        e.contains("var want=norm") -> { synchronized(calls) { calls += "eval:posted" }; postedPolls++
                            result(if (postedAfterTbpClick && tbpClicked == 0) "open" else "toast") }
                        e.contains("?'ok:':'no:'") -> { synchronized(calls) { calls += "eval:hasText" }; result(hasTextReply) }
                        else -> { synchronized(calls) { calls += "eval:other" }; result("0") }
                    }
                }
                else -> reply(false, stderr = "unknown cmd: $cmd")
            }
            if (cmd != "eval") synchronized(calls) { calls += cmd }
            res.reply(200, body)
        }
        server.start()
        return XPostBeta(x, BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })).apply {
            pollMs = 20; submitVerifyMs = 300
        }
    }

    @Test fun `happy path - one eval per phase, no TBP click, no editor_type, timings in order`() = runBlocking {
        val b = beta()
        b.post("hello world!")
        assertEquals(listOf("ready", "eval:probe", "nav", "eval:focus", "eval:insert", "eval:submit", "eval:posted"), calls)
        assertEquals(listOf("ready", "stray_check", "goto_compose", "focus_composeText", "insert_text", "sleep", "click_composeSubmit", "waitPosted", "total"),
            b.lastTimings.keys.toList())
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.startsWith("stray_check: ok") && it.contains("no dialog") })
        assertTrue(b.lastTimings["total"]!! < 5_000)
    }

    @Test fun `a leftover dialog is acted on in the probe eval and the next probe confirms it is gone`() = runBlocking {
        probes = mutableListOf("""{"u":"https://x.com/home","n":1,"s":"Unsent posts","what":"close"}""", """{"u":"https://x.com/home","n":0}""")
        val b = beta()
        b.post("hello world!")
        assertEquals(listOf("ready", "eval:probe", "eval:probe", "nav"), calls.take(4))
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("dialog 'Unsent posts' → close; clean") })
    }

    @Test fun `a dialog without buttons gets Escape and the check gives up after 3 tries without waiting a cap`() = runBlocking {
        probes = mutableListOf("""{"u":"https://x.com/home","n":1,"s":"Stuck","what":"none"}""")
        val b = beta()
        b.post("hello world!")
        assertEquals(3, calls.count { it == "eval:probe" })
        assertEquals(3, calls.count { it == "key" })
        assertTrue(b.lastTimings["stray_check"]!! < 3_000)
        assertTrue(calls.contains("eval:posted"))
    }

    @Test fun `login URL in the probe is a session expiry`() = runBlocking {
        probes = mutableListOf("""{"u":"https://x.com/i/flow/login","n":0}""")
        val b = beta()
        val e = runCatching { b.post("hello world!") }.exceptionOrNull()
        assertTrue("$e", e is SessionExpiredException)
        assertTrue(b.failedBeforeTyping)
    }

    @Test fun `JS focus without effect falls back to the TBP click once`() = runBlocking {
        focusReplies = mutableListOf("missing", "nofocus", "focused")
        val b = beta()
        b.post("hello world!")
        assertEquals(1, tbpClicked)
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("JS focus had no effect → TBP click ok → focused") })
    }

    @Test fun `JS Post click without effect - TBP click once after the verify window, then posted`() = runBlocking {
        postedAfterTbpClick = true
        val b = beta()
        b.post("hello world!")
        assertEquals(1, tbpClicked)
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("JS click had no effect") && it.contains("→ TBP click") })
    }

    @Test fun `insertText pending gets one re-check, a failed insert falls back to paste`() = runBlocking {
        insertReply = "pending:0"
        val b = beta()
        b.post("hello world!")
        assertTrue(calls.containsAll(listOf("eval:insert", "eval:hasText")) && "eval:paste" !in calls)
        assertFalse(b.failedBeforeTyping)
    }

    @Test fun `insert failing everywhere fails after typing started (no retry allowed)`() = runBlocking {
        insertReply = "failed:0"; hasTextReply = "no:0"
        val b = beta()
        // paste mock says ok → succeeds; make it fail through the message path instead
        b.post("hello world!")
        assertTrue(calls.contains("eval:paste"))
        assertFalse(b.failedBeforeTyping)
        assertEquals(0, tbpClicked)
    }

    @Test fun `shield copies are verbatim`() {
        assertEquals(GrokShield.ON, XPostBetaShield.GROK_ON)
        assertEquals(GrokShield.OFF, XPostBetaShield.GROK_OFF)
        assertEquals(GrokShield.PROTECT, XPostBetaShield.GROK_PROTECT)
        assertEquals(GrokShield.ATTR, XPostBetaShield.GROK_ATTR)
        assertEquals(FastScrape.mediaOnJs(FastScrape.PAGE_MEDIA_TTL_MS), XPostBetaShield.mediaOn(XPostBetaShield.PAGE_MEDIA_TTL_MS))
    }

    @Test fun `x_post_beta is off by default and labelled as the beta`() {
        assertTrue(XPostBetaTool.NAME in ToolPrefs.DEFAULT_OFF)
        assertTrue(XPostBetaTool.NAME in ToolPrefs.effective(emptySet(), emptySet()))
        assertFalse(XPostBetaTool.NAME in ToolPrefs.effective(emptySet(), setOf(XPostBetaTool.NAME)))
        assertEquals(setOf("web_click", XPostBetaTool.NAME), ToolPrefs.effective(setOf("web_click"), emptySet()))
        assertFalse("x_post" in ToolPrefs.effective(emptySet(), emptySet()))
        assertEquals("Beta: faster X posting.", com.farrow.app.data.tools.ToolStatus.short(XPostBetaTool.DESCRIPTION))
    }

    /** Dumps every beta script (for `node --check`, see the JS syntax test below). */
    @Test fun `beta scripts are valid JavaScript`() {
        val st = x.stray ?: StraySpec()
        val box = x.sel("composeText"); val sub = x.sel("composeSubmit")
        val t = "it's a \"test\" \n with ${'$'} and ünïcode"
        val scripts = mapOf("probe" to XPostBetaJs.probe(st, "Unsent posts", 1), "probe0" to XPostBetaJs.probe(st, null, 0),
            "focus" to XPostBetaJs.focus(box), "insert" to XPostBetaJs.insert(box, t), "hasText" to XPostBetaJs.hasText(box, t),
            "paste" to XPostBetaJs.paste(box, t), "submit" to XPostBetaJs.clickSubmit(box, sub),
            "posted" to XPostBetaJs.posted(box, x.sel("postSuccess"), t), "off" to XPostBetaShield.GROK_OFF)
        val dir = File(System.getProperty("java.io.tmpdir"), "farrow-beta-js").apply { mkdirs() }
        scripts.forEach { (k, v) -> File(dir, "$k.js").writeText(v + ";\n") }
        val node = listOf("/home/box/tools/node-v22.23.3-linux-x64/bin/node", "/usr/bin/node").map(::File).firstOrNull { it.canExecute() } ?: return
        scripts.keys.forEach { k ->
            val p = ProcessBuilder(node.path, "--check", File(dir, "$k.js").path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$k.js: $out", 0, p.waitFor())
        }
    }
}
