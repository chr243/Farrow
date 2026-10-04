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
import java.util.concurrent.atomic.AtomicInteger

/** SocialAutomation (X) against a fake bridge: robust compose navigation, retries, success check, JS-free status. */
class XAutomationTest {
    private val server = MiniHttpServer()
    private val cmds = mutableListOf<String>()
    private val composeChecks = AtomicInteger()
    @Volatile var navOk = false
    @Volatile var composeAppearsAfter = 2
    @Volatile var posted = false
    /** Bridge < 1.8.0: no focus / key / editor_type. */
    @Volatile var oldBridge = false
    /** `tbp click` on the compose box hangs (X's Draft.js editor). */
    @Volatile var composeClickHangsMs = 0L
    /** `tbp click` on the Post button fails, and so does the JS click → ctrl+Return. */
    @Volatile var submitClickBroken = false
    /** `tbp type` doesn't reach the Draft.js editor. */
    @Volatile var typeIgnored = false
    @Volatile var editor = ""
    val keys = mutableListOf<String>()

    @After fun stop() = server.stop()

    private fun xConfig(shrink: Boolean = false): SiteConfig {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        var raw = f.readText()
        if (shrink) raw = raw.replace("45000", "3000").replace("25000", "2000")
        return Json { ignoreUnknownKeys = true }.decodeFromString(SiteConfig.serializer(), raw)
    }

    private fun reply(ok: Boolean, data: JsonElement? = null, stderr: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", ""); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()

    private fun startBridge(): BridgeClient {
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(cmds) { cmds += cmd }
            val body = when (cmd) {
                "ready" -> reply(true, buildJsonObject { put("eval_ok", true) })
                // Navigation "not confirmed" (X never settles) but the history already shows the compose page.
                "nav" -> reply(navOk, buildJsonObject { put("url", "https://x.com/compose/post"); put("matched", navOk); put("seconds", 30) },
                    if (navOk) "" else "navigation to https://x.com/compose/post not confirmed within 40 s")
                "focus", "key", "editor_type" -> if (oldBridge) reply(false, stderr = "unknown cmd: $cmd") else when (cmd) {
                    "focus" -> {
                        val sel = args["selector"]!!.jsonPrimitive.content
                        if (sel.contains("tweetButton")) { if (submitClickBroken) reply(false, stderr = "element missing") else { posted = true; reply(true) } }
                        else reply(true).replace("\"stdout\":\"\"", "\"stdout\":\"focused\"")
                    }
                    "key" -> { val k = args["keys"]!!.jsonPrimitive.content; synchronized(keys) { keys += k }; if (k == "ctrl+Return") posted = true; reply(true) }
                    else -> { editor = args["text"]!!.jsonPrimitive.content; reply(true, buildJsonObject { put("method", "xdotool") }) }
                }
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        // v1.0.6 pre-submit settle probe: editor text + submit enabled + no "Save post?" sheet.
                        e.contains("JSON.stringify({t:ed") -> reply(true, buildJsonObject {
                            put("result", buildJsonObject { put("t", editor); put("en", true); put("send", true); put("save", false) }.toString()) })
                        e.contains("B.first(") -> reply(true, buildJsonObject { put("result", if (posted) "0" else "1") })
                        e.contains("B.exists(") -> {
                            val n = composeChecks.incrementAndGet()
                            if (n <= composeAppearsAfter) reply(false, stderr = "eval failed: timeout after 45s")
                            else reply(true, buildJsonObject { put("result", "true") })
                        }
                        e.contains("execCommand('insertText'") -> { editor = "hello"; reply(true, buildJsonObject { put("result", "inserted") }) }
                        e.contains("innerText") -> reply(true, buildJsonObject { put("result", editor) })
                        e.contains("t.click();return 'clicked'") -> reply(true, buildJsonObject { put("result", "focused") })
                        else -> reply(true, buildJsonObject { put("result", "") })
                    }
                }
                "click" -> {
                    val sel = args["selector"]!!.jsonPrimitive.content
                    when {
                        sel.contains("tweetButton") -> if (submitClickBroken) reply(false, stderr = "click failed: not clickable") else { posted = true; reply(true) }
                        composeClickHangsMs > 0 -> { Thread.sleep(composeClickHangsMs); reply(false, stderr = "timeout") }
                        else -> reply(true)
                    }
                }
                "type" -> { if (!typeIgnored) editor = args["text"]!!.jsonPrimitive.content; reply(true) }
                "press" -> { synchronized(keys) { keys += "press:" + args["key"]!!.jsonPrimitive.content }; reply(true) }
                "site_status" -> reply(true, buildJsonObject {
                    put("state", "LOGGED_IN"); put("reason", "session cookies auth_token, ct0 in Firefox's cookie store")
                    put("url", "https://x.com/home"); put("cookies", buildJsonArray { add("auth_token"); add("ct0") })
                })
                else -> reply(false, stderr = "unknown cmd: $cmd")
            }
            res.reply(200, body)
        }
        server.start()
        return BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })
    }

    @Test fun `post carries on when nav is unconfirmed but already on compose, retries the textbox and confirms the post`() = runBlocking {
        val bridge = startBridge()
        val a = SocialAutomation(xConfig(), bridge)
        a.ensureBrowserReady(30)
        a.post("hello")
        assertTrue(posted)
        assertFalse("TBP goto (waits for load) must not be used", cmds.contains("goto"))
        assertEquals("ready", cmds.first())
        assertTrue(cmds.contains("nav"))
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("1. goto compose: ok") && log.contains("unconfirmed but on target"))
        assertTrue(log, log.contains("success toast"))
        assertTrue(composeChecks.get() > 2)
    }

    @Test fun `hung compose click falls back to JS focus after an idle wait, types, and posts with ctrl+Return`() = runBlocking {
        val bridge = startBridge()
        navOk = true; composeAppearsAfter = 0; composeClickHangsMs = 2_500; submitClickBroken = true
        val a = SocialAutomation(xConfig(), bridge).apply { clickTryMaxMs = 800 }
        a.post("hello")
        assertTrue(posted)
        val c = synchronized(cmds) { cmds.toList() }
        val firstClick = c.indexOf("click")
        assertTrue(c.toString(), firstClick >= 0 && c.subList(firstClick, c.size).take(3).contains("ready"))
        val after = c.subList(firstClick, c.size)
        assertTrue(after.toString(), after.indexOf("ready") in 0 until after.indexOf("focus"))
        assertTrue(c.contains("editor_type"))
        assertEquals(listOf("ctrl+Return"), keys)
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("TBP click timed out") && log.contains("JS fallback: focused") && log.contains("editor_type via xdotool"))
        assertTrue(log, log.contains("key ctrl+Return"))
    }

    @Test fun `old bridge types with tbp type and fixes a Draft js editor with insertText`() = runBlocking {
        val bridge = startBridge()
        navOk = true; composeAppearsAfter = 0; oldBridge = true; typeIgnored = true
        val a = SocialAutomation(xConfig(), bridge)
        a.post("hello")
        assertTrue(posted)
        assertEquals("hello", editor)
        val log = a.lastStepLog.joinToString("\n")
        assertTrue(log, log.contains("old bridge") && log.contains("insertText: inserted"))
    }

    @Test fun `fast check verdict`() {
        val pats = listOf("/i/flow/login")
        assertEquals(LoginState.LOGGED_IN, FastCheck.verdict("https://x.com/home", "Home / X — Mozilla Firefox", "https://x.com/home", pats))
        assertEquals(LoginState.LOGGED_OUT, FastCheck.verdict("https://x.com/i/flow/login", "", "https://x.com/home", pats))
        assertEquals(LoginState.LOGGED_OUT, FastCheck.verdict("https://x.com/home", "Log in to X / X", "https://x.com/home", pats))
        assertEquals(LoginState.UNKNOWN, FastCheck.verdict("https://x.com/explore", "Explore / X", "https://x.com/home", pats))
        val fc = FastCheck.parse(buildJsonObject { put("state", "LOGGED_IN"); put("reason", "r"); put("busy", buildJsonArray { add("click") }); put("ms", 120) })
        assertEquals(listOf("click"), fc.busy); assertEquals(120L, fc.bridgeMs)
        assertNull(FastCheck.parse(buildJsonObject { put("state", "LOGGED_IN") }).busy)
    }

    @Test fun `missing compose box fails with the per-step log`() = runBlocking {
        val bridge = startBridge()
        navOk = true; composeAppearsAfter = Int.MAX_VALUE
        val e = runCatching { SocialAutomation(xConfig(shrink = true), bridge).post("hi") }.exceptionOrNull()
        assertTrue(e.toString(), e is StepFailedException)
        val m = e!!.message!!
        assertTrue(m, m.contains("Step 2/8") && m.contains("Step log:") && m.contains("1. goto compose: ok"))
        assertTrue(m, m.contains("2. waitFor composeText: FAILED") && m.contains("last eval error"))
    }

    @Test fun `status uses the cookie store and URL, never eval or goto`() = runBlocking {
        val bridge = startBridge()
        val st = SocialAutomation(xConfig(), bridge).quickStatus()
        assertEquals(LoginState.LOGGED_IN, st.state)
        assertEquals("https://x.com/home", st.url)
        assertFalse(cmds.contains("eval")); assertFalse(cmds.contains("goto"))
    }

    @Test fun `nav target check`() {
        assertTrue(NavCheck.sameTarget("https://x.com/compose/post", "https://x.com/compose/post"))
        assertTrue(NavCheck.sameTarget("https://www.x.com/compose/post?x=1", "https://x.com/compose/post"))
        assertFalse(NavCheck.sameTarget("https://x.com/home", "https://x.com/compose/post"))
        assertFalse(NavCheck.sameTarget(null, "https://x.com/"))
        assertTrue(NavCheck.sameTarget("https://x.com/home", "https://x.com/"))
    }
}
