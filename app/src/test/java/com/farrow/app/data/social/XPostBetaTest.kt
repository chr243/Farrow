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
    @Volatile var insertReplies = mutableListOf(st(12, true, true))   // insert, then re-checks/paste/clear/state in order
    @Volatile var submitReplies = mutableListOf("clicked:editor 12 chars, Post button enabled")
    @Volatile var probeHangMs = 0L
    @Volatile var postedAfterTbpClick = false
    @Volatile var tbpClicked = 0
    @Volatile var editorTyped = 0

    private fun st(len: Int, match: Boolean, enabled: Boolean) = """{"len":$len,"match":$match,"enabled":$enabled,"btn":true}"""

    private fun reply(ok: Boolean, data: JsonElement? = null, stderr: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", ""); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()

    private fun result(v: String) = reply(true, buildJsonObject { put("result", v) })

    private fun next(l: MutableList<String>) = synchronized(l) { if (l.size > 1) l.removeAt(0) else l[0] }

    private fun log(c: String) = synchronized(calls) { calls += c }

    private fun beta(): XPostBeta {
        if (probes.isEmpty()) probes = mutableListOf("""{"u":"https://x.com/home","n":0}""")
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            val body = when (cmd) {
                "ready" -> reply(true, buildJsonObject { put("eval_ok", true) })
                "nav" -> reply(true, buildJsonObject { put("url", args["url"]!!.jsonPrimitive.content); put("matched", true); put("seconds", 1) })
                "click" -> { tbpClicked++; reply(true) }
                "editor_type" -> { editorTyped++; reply(true, buildJsonObject { put("method", "insertText") }) }
                "key", "cancel" -> reply(true)
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        e.contains("'emptied:'") -> { log("eval:closeComposer"); result("emptied:0,close") }
                        e.contains("DMDrawer") -> { log("eval:probe"); if (probeHangMs > 0) { val h = probeHangMs; probeHangMs = 0; Thread.sleep(h) }; result(next(probes)) }
                        e.contains("'nofocus'") -> { log("eval:focus"); result(next(focusReplies)) }
                        e.contains("execCommand('insertText'") -> { log("eval:insert"); result(next(insertReplies)) }
                        e.contains("ClipboardEvent('paste'") -> { log("eval:paste"); result(next(insertReplies)) }
                        e.contains("return state(find())") -> { log("eval:state"); result(next(insertReplies)) }
                        e.contains("execCommand('delete'") -> { log("eval:clear"); result(next(insertReplies)) }
                        e.contains("setTimeout(function(){try{b.click()") -> { log("eval:submit"); result(next(submitReplies)) }
                        e.contains("var want=norm") -> { log("eval:posted"); result(if (postedAfterTbpClick && tbpClicked == 0) "open" else "toast") }
                        else -> { log("eval:other"); result("0") }
                    }
                }
                else -> reply(false, stderr = "unknown cmd: $cmd")
            }
            if (cmd != "eval") log(cmd)
            res.reply(200, body)
        }
        server.start()
        return XPostBeta(x, BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })).apply {
            pollMs = 20; submitVerifyMs = 300; probeMaxMs = 1_000
        }
    }

    @Test fun `happy path - one eval per phase, window focus before insert, no TBP click, timings in order`() = runBlocking {
        val b = beta()
        b.post("hello world!")
        assertEquals(listOf("ready", "eval:probe", "nav", "eval:focus", "key", "eval:insert", "eval:submit", "eval:posted"), calls)
        assertEquals(listOf("ready", "stray_check", "goto_compose", "focus_composeText", "insert_text", "sleep", "click_composeSubmit", "waitPosted", "total"),
            b.lastTimings.keys.toList())
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.startsWith("stray_check: ok") && it.contains("no dialog") })
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("insertText: editor 12 chars, text matches, Post button enabled") })
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("JS click scheduled (editor 12 chars, Post button enabled)") })
        assertEquals(0, tbpClicked)
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
        assertTrue(b.lastTimings["stray_check"]!! < 3_000)
        assertTrue(calls.contains("eval:posted"))
    }

    @Test fun `a probe that does not answer is skipped, not a failure and not a restart`() = runBlocking {
        probeHangMs = 3_000
        val b = beta()
        b.post("hello world!")
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.contains("probe did not answer in 1 s → skipped") })
        assertTrue(b.lastTimings["stray_check"]!! < 2_900)
        assertTrue(calls.contains("eval:posted"))
    }

    @Test fun `login URL in the probe is a session expiry (no composer yet, no cleanup)`() = runBlocking {
        probes = mutableListOf("""{"u":"https://x.com/i/flow/login","n":0}""")
        val b = beta()
        val e = runCatching { b.post("hello world!") }.exceptionOrNull()
        assertTrue("$e", e is SessionExpiredException)
        assertTrue(b.failedBeforeTyping)
        assertFalse(calls.contains("eval:closeComposer"))
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

    @Test fun `text in the DOM but Post button disabled = not registered - paste with window focus registers it`() = runBlocking {
        insertReplies = mutableListOf(st(12, true, false), st(12, true, false), st(12, true, true))
        val b = beta()
        b.post("hello world!")
        assertEquals(listOf("eval:focus", "key", "eval:insert", "eval:state", "key", "eval:paste", "eval:submit"),
            calls.dropWhile { it != "eval:focus" }.take(7))
        val log = b.lastSteps.first { it.startsWith("insert_text") }
        assertTrue(log, log.contains("insertText: editor 12 chars, text matches, Post button disabled") &&
            log.contains("post button disabled, text not registered") && log.contains("paste: editor 12 chars, text matches, Post button enabled"))
    }

    @Test fun `text never registered - fails before clicking, keystrokes tried, composer cleaned up, nothing posted`() = runBlocking {
        insertReplies = mutableListOf(st(12, true, false))
        val b = beta()
        val e = runCatching { b.post("hello world!") }.exceptionOrNull()
        assertTrue("$e", e is AutomationException && e.message!!.contains("post button disabled, text not registered") && e.message!!.contains("Nothing was posted"))
        assertEquals(1, editorTyped)
        assertTrue(calls.contains("eval:clear"))
        assertFalse(calls.contains("eval:submit"))
        assertEquals(0, tbpClicked)
        assertFalse("no retry after the insert phase started", b.failedBeforeTyping)
        // cleanup: cancel, idle, empty + close the composer, then home
        val after = calls.dropWhile { it != "cancel" }
        assertEquals(listOf("cancel", "ready", "eval:closeComposer"), after.take(3))
        assertEquals("nav", after.last())
        assertTrue(b.lastSteps.toString(), b.lastSteps.any { it.startsWith("cleanup: ") && it.contains("composer: emptied:0,close") })
        assertTrue(b.lastTimings.containsKey("cleanup"))
    }

    @Test fun `disabled Post button at submit - no click at all, clean failure, cleanup`() = runBlocking {
        submitReplies = mutableListOf("disabled:editor 12 chars, Post button disabled")
        val b = beta()
        val e = runCatching { b.post("hello world!") }.exceptionOrNull()
        assertTrue("$e", e is AutomationException && e.message!!.contains("post button disabled, text not registered") && e.message!!.contains("Not clicked"))
        assertEquals(2, calls.count { it == "eval:submit" })
        assertEquals(0, tbpClicked)
        assertFalse(calls.contains("key") && calls.indexOf("key") > calls.indexOf("eval:submit"))
        assertTrue(calls.contains("eval:closeComposer"))
        assertFalse(b.failedBeforeTyping)
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
            "focus" to XPostBetaJs.focus(box), "insert" to XPostBetaJs.insert(box, sub, t), "state" to XPostBetaJs.state(box, sub, t),
            "paste" to XPostBetaJs.paste(box, sub, t), "clear" to XPostBetaJs.clear(box, sub, t), "submit" to XPostBetaJs.clickSubmit(box, sub),
            "close" to XPostBetaJs.closeComposer(box, st),
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
