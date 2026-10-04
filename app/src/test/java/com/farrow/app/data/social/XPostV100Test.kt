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
import java.security.MessageDigest

/**
 * v1.0.11 regression guard: x_post must stay EXACTLY v1.0.0's flow (tag v1.0.0, the last version that typed on the
 * phone). v1.0.9/v1.0.10 added a `target` step, marker selectors, a settle step and bridge 1.12.0's probe/paste
 * `editor_type`, and x_post stopped typing. If one of these assertions fails, compare with `git diff v1.0.0 HEAD`.
 *
 * v1.0.13 (faster x_post): only the WAITING changed — the settle sleep is 150 ms (was 800), waitPosted also returns as
 * soon as the compose box no longer holds the text, polls are 200 ms. Focus, typing and clicks (commands, selectors,
 * order, the bridge's focus/type/click/key code and the app's click/typeEditor code) are still v1.0.0's, checked below.
 */
class XPostV100Test {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    private fun file(rel: String) = listOf(rel, "app/$rel").map(::File).first { it.exists() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy { json.decodeFromString(SiteConfig.serializer(), file("src/main/assets/selectors/x.json").readText()) }

    /** selectors/x.json `postSteps` at tag v1.0.0. */
    private val V100_POST_STEPS = """[{"action": "goto", "url": "compose", "timeoutMs": 45000}, {"action": "waitFor", "selector": "composeText", "timeoutMs": 45000, "hint": "The compose box did not appear; X may be slow or showing a dialog."}, {"action": "click", "selector": "composeText", "timeoutMs": 45000}, {"action": "typeEditor", "selector": "composeText", "text": "{text}", "timeoutMs": 45000}, {"action": "sleep", "ms": 800}, {"action": "click", "selector": "composeSubmit", "timeoutMs": 45000, "fallbackKey": "ctrl+Return"}, {"action": "waitPosted", "selector": "postSuccess", "selectors": ["composeText"], "timeoutMs": 25000}]"""

    /** v1.0.13: v1.0.0's steps with only the waits changed (sleep 800 → 150, waitPosted gets the text). */
    private val V113_POST_STEPS = V100_POST_STEPS
        .replace("""{"action": "sleep", "ms": 800}""", """{"action": "sleep", "ms": 150}""")
        .replace(""""selectors": ["composeText"], "timeoutMs": 25000}""", """"selectors": ["composeText"], "text": "{text}", "timeoutMs": 25000}""")

    private fun acting(steps: List<AutomationStep>) = steps.filter { it.action !in setOf("sleep", "waitFor", "waitPosted") }

    @Test fun `x json post steps act like v1_0_0's, only the waits changed`() {
        val raw = json.parseToJsonElement(file("src/main/assets/selectors/x.json").readText()).jsonObject
        assertNotEquals(V100_POST_STEPS, V113_POST_STEPS)
        assertEquals(json.parseToJsonElement(V113_POST_STEPS), raw["postSteps"])
        val v100 = json.decodeFromString(ListSerializerSteps, V100_POST_STEPS)
        assertEquals(v100.map { it.action }, x.postSteps.map { it.action })
        // goto / click / typeEditor / click: identical to v1.0.0 (url, selectors, timeouts, fallback key)
        assertEquals(acting(v100), acting(x.postSteps))
        assertEquals(v100.first { it.action == "waitFor" }, x.postSteps.first { it.action == "waitFor" })
        assertEquals("[data-testid=\"tweetTextarea_0\"]", x.selectors["composeText"])
        assertEquals("[data-testid=\"tweetButton\"], [data-testid=\"tweetButtonInline\"]", x.selectors["composeSubmit"])
        assertEquals("[data-testid=\"toast\"]", x.selectors["postSuccess"])
        assertEquals("https://x.com/compose/post", x.url("compose"))
        assertTrue(x.postSteps.none { it.action == "target" || it.action == "settleSubmit" })
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** From [start] up to the next blank-blank line (PEP 8 spacing between top-level definitions). */
    private fun pyBlock(src: String, start: String): String {
        val i = src.indexOf(start).also { assertTrue("missing $start", it >= 0) }
        return src.substring(i, src.indexOf("\n\n\n", i))
    }

    @Test fun `bridge typing, focus, click and key commands are v1_0_0's (bridge 1_9_0)`() {
        val py = file("src/main/assets/tbp_bridge.py").readText()
        mapOf(
            "FOCUS_JS = r\"\"\"" to "b60ba91f323fe424209575f5d6f87fe5ec7dbe3f37b062cb6d8fc113d33ac97a", // FOCUS_JS, TEXT_JS, INSERT_JS
            "def norm_text(s):" to "94177ef9beb3bae38491a6b521c9f87976fd42e788dd54a939919a7629de765e",
            "def js_value(res):" to "f1e58a31f11c5956bc91737f5c6ac71e853aedad0057f30853e28f9e2941b030",
            "def cmd_focus(a):" to "623af5d1a7db1f6b32ed4263b392569b0f9b9c376f8a6243e63e5c53def9dd64",
            "def cmd_key(a):" to "436f391fd0ba71973a875c0910889039c18f9b787b5d29e3052848b28766ea1e",
            "def cmd_editor_type(a):" to "74e3db6fb00ba9d7f17d65f2456e62cc890e456373ddec3cfe8d575b0b9ccc89",
            "def cmd_click(a):" to "333a5739f9fde016a7ff3ddd1e756994ce66d6d5415f2a69cca1fa1e2c484422",
            "def cmd_type(a):" to "c9857c7ea0723cb53ae1e795c61b8b43fa09d49f4b6accbf8e46afccc3dc6d04",
        ).forEach { (start, hash) -> assertEquals("tbp_bridge.py '$start' differs from v1.0.0", hash, sha(pyBlock(py, start))) }
        assertFalse(py.contains("clipboard_paste") || py.contains("PREP_JS") || py.contains("data-farrow-typing"))
    }

    /**
     * The acting code is v1.0.0's byte for byte (hashes = the same blocks at tag v1.0.0): runOne's click/clickText/
     * type/typeEditor branches and sturdyClick → waitIdle → jsFocusOrClick → pressKey → typeIntoEditor. (v1.0.13 changed
     * runSteps' timing/log and the waitPosted/stray waits, so the whole-engine hash is gone.)
     */
    @Test fun `SocialAutomation click and typing code is v1_0_0's`() {
        val kt = file("src/main/java/com/farrow/app/data/social/SocialAutomation.kt").readText()
        val a = kt.indexOf("    private suspend fun sturdyClick(")
        val b = kt.indexOf("    /** URL, a page-text snippet", a)
        val c = kt.indexOf("            \"click\" -> {\n                val sel = s.selector ?: return StepOutcome.Skipped")
        val d = kt.indexOf("            \"press\" -> ", c)
        assertTrue(a >= 0 && b > a && c >= 0 && d > c)
        assertEquals("sturdyClick..typeIntoEditor differs from v1.0.0", "a5c63b7d856df799f4d17b7cf72ca400c49941ac808b15cfd19147da98c3a76e", sha(kt.substring(a, b)))
        assertEquals("runOne click/type branches differ from v1.0.0", "bf8d02a76ae07afc579afd9fca8c7bf412aa05a7ff805be0744ac379270b210c", sha(kt.substring(c, d)))
    }

    @Test fun `a post run makes v1_0_0's bridge calls in v1_0_0's order`() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var editor = ""
        var posted = false
        fun ok(data: JsonElement? = null) = buildJsonObject {
            put("ok", true); put("code", 0); put("stdout", ""); put("stderr", ""); data?.let { put("data", it) }
        }.toString()
        fun result(v: String) = ok(buildJsonObject { put("result", v) })
        server.route("/cmd") { rq, res ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(calls) { calls += cmd to args }
            res.reply(200, when (cmd) {
                "ready" -> ok(buildJsonObject { put("eval_ok", true) })
                "nav" -> ok(buildJsonObject { put("url", "https://x.com/compose/post"); put("matched", true); put("seconds", 1) })
                "click" -> { if (args["selector"]!!.jsonPrimitive.content.contains("tweetButton")) posted = true; ok() }
                "editor_type" -> { editor = args["text"]!!.jsonPrimitive.content; ok(buildJsonObject { put("method", "xdotool"); put("chars", editor.length) }) }
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        e.contains("return B.postDone(") -> result(if (posted) "cleared" else "open")
                        e.contains("B.exists(") -> result("true")
                        else -> result("")
                    }
                }
                else -> """{"ok":false,"code":2,"stdout":"","stderr":"unknown cmd: $cmd"}"""
            })
        }
        server.start()
        val bridge = BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })
        val a = SocialAutomation(x, bridge)
        a.post("Hello from Farrow")
        assertTrue(posted)
        // Everything that moves focus, types or submits — v1.0.0: nav compose, click box, editor_type box, click Post.
        val acting = synchronized(calls) { calls.filter { it.first !in setOf("eval", "ready") } }
        assertEquals(acting.map { it.first }.toString(), listOf("nav", "click", "editor_type", "click"), acting.map { it.first })
        assertEquals("https://x.com/compose/post", acting[0].second["url"]!!.jsonPrimitive.content)
        assertEquals("[data-testid=\"tweetTextarea_0\"]", acting[1].second["selector"]!!.jsonPrimitive.content)
        val type = acting[2].second
        assertEquals(setOf("selector", "text", "delays_ms"), type.keys) // no `active`, no marker selector
        assertEquals("[data-testid=\"tweetTextarea_0\"]", type["selector"]!!.jsonPrimitive.content)
        assertEquals("Hello from Farrow", type["text"]!!.jsonPrimitive.content)
        assertEquals("Hello from Farrow".length, type["delays_ms"]!!.jsonArray.size)
        assertEquals("[data-testid=\"tweetButton\"], [data-testid=\"tweetButtonInline\"]", acting[3].second["selector"]!!.jsonPrimitive.content)
        assertFalse(calls.any { (c, args) -> args.toString().contains("data-farrow-compose") || args.toString().contains("data-farrow-submit") || c == "key" })
        val log = a.lastStepLog
        assertEquals(log.joinToString("\n"), 7, log.size)
        assertTrue(log.joinToString("\n"), log[3].startsWith("4. typeEditor composeText: ok") && log[4].startsWith("5. sleep 150 ms: ok"))
        assertTrue(log.joinToString("\n"), log[6].startsWith("7. waitPosted postSuccess: ok") && log[6].contains("compose box emptied"))
        assertTrue(log.all { Regex(""": (ok|skipped) \d+ ms""").containsMatchIn(it) })
        // v1.0.13 timings_ms: stray cleanup, one entry per step, total
        val t = a.lastPostTimings
        assertEquals(listOf("stray_cleanup", "1_goto_compose", "2_waitFor_composeText", "3_click_composeText", "4_typeEditor_composeText",
            "5_sleep", "6_click_composeSubmit", "7_waitPosted_postSuccess", "total"), t.keys.toList())
        assertTrue(t.toString(), t["5_sleep"]!! in 150L..1_000L && t["total"]!! >= t.filterKeys { it != "total" }.values.sum())
        // No wait runs to its timeout when its condition already holds: every wait step returns within ~a poll.
        listOf("2_waitFor_composeText", "7_waitPosted_postSuccess").forEach { k -> assertTrue("$k = ${t[k]} ms", t[k]!! < 1_500) }
        assertTrue("total ${t["total"]} ms", t["total"]!! < 6_000)
    }

    private companion object {
        val ListSerializerSteps = kotlinx.serialization.builtins.ListSerializer(AutomationStep.serializer())
    }
}
