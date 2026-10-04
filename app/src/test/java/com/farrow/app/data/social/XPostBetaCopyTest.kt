package com.farrow.app.data.social

import com.farrow.app.agent.tools.XPostBetaTool
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import com.farrow.app.data.tools.ToolPrefs
import com.farrow.app.data.tools.ToolStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** x_post_beta = x_post 1:1, except typing (instant insertText instead of letter-by-letter keystrokes). */
class XPostBetaCopyTest {
    private val servers = mutableListOf<MiniHttpServer>()
    @After fun stop() = servers.forEach { it.stop() }

    private fun file(rel: String) = listOf(rel, "app/$rel").map(::File).first { it.exists() }
    private val x: SiteConfig by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), file("src/main/assets/selectors/x.json").readText())
    }
    private val TYPE_START = "    /**\n     * Types into a (Draft.js) editor"
    private val BETA_TYPE_START = "    /**\n     * x_post_beta's ONLY difference"
    private val TYPE_END = "    /** URL, a page-text snippet"

    private fun region(s: String, start: String) = s.substring(s.indexOf(start).also { assertTrue(start, it >= 0) }, s.indexOf(TYPE_END))

    @Test fun `beta engine is SocialAutomation's class with only typeIntoEditor changed`() {
        val orig = file("src/main/java/com/farrow/app/data/social/SocialAutomation.kt").readText()
        val beta = file("src/main/java/com/farrow/app/data/social/XPostBetaAutomation.kt").readText()
        val origClass = orig.substring(orig.indexOf("class SocialAutomation(val config")).trimEnd()
        val betaClass = beta.substring(beta.indexOf("class XPostBetaAutomation(val config")).trimEnd()
        val origType = region(origClass, TYPE_START)
        val betaType = region(betaClass, BETA_TYPE_START)
        val back = betaClass.replace(betaType, origType).replaceFirst("class XPostBetaAutomation(val config", "class SocialAutomation(val config")
        assertEquals("XPostBetaAutomation must be a copy of SocialAutomation except typeIntoEditor — re-copy it", origClass, back)
        // Same imports.
        assertEquals(orig.substring(orig.indexOf("import "), orig.indexOf("\n\nenum class")), beta.substring(beta.indexOf("import "), beta.indexOf("\n\n/**")))
        // The beta's typing = x_post's insertText fallback + its verification, without the keystrokes.
        assertFalse(betaType.contains("editorType(") || betaType.contains("bridge.type(") || betaType.contains("jsFocusOrClick"))
        listOf(
            "val ins = runCatching { withTimeoutOrNull(30_000) { evalString(DomFinder.insertText(css, text)) } }.getOrNull()",
            "notes += \"insertText: \${ins ?: \"failed\"}\"",
            "val got = editorText(css)",
            "if (DomFinder.containsText(got, text)) { stepNote = notes.joinToString(\"; \"); return StepOutcome.Ok }",
            "return StepOutcome.Failed(\"the text did not appear in the editor (\${notes.joinToString(\"; \")}; editor has \${got?.length ?: 0} chars)\")",
        ).forEach { line -> assertTrue(line, origType.contains(line) && betaType.contains(line)) }
    }

    @Test fun `beta tool body is x_post's`() {
        fun body(s: String) = s.substring(s.indexOf("val text = args.str(\"text\")"), s.indexOf("}.toString()", s.indexOf("put(\"steps\", JsonArray(a.lastStepLog")))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val xPost = file("src/main/java/com/farrow/app/agent/tools/SocialTools.kt").readText()
        val beta = file("src/main/java/com/farrow/app/agent/tools/XPostBetaTool.kt").readText()
        assertEquals(body(xPost), body(beta))
        fun guarded(s: String) = s.substring(s.indexOf("if (!bridge.isAvailable())"), s.indexOf("errorJson(\"bridge error: \${e.message}\")"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(guarded(xPost), guarded(beta))
        assertTrue(XPostBetaTool.NAME in ToolPrefs.DEFAULT_OFF)
        assertEquals("Beta: faster X posting.", ToolStatus.short(XPostBetaTool.DESCRIPTION))
    }

    private class Run(val acting: List<String>, val evals: List<String>, val log: List<String>, val timings: Map<String, Long>, val typed: String)

    private fun run(beta: Boolean): Run {
        val server = MiniHttpServer().also { servers += it }
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
                        e.contains("execCommand('insertText'") -> { editor = "Hello from Farrow"; result("inserted") }
                        e.contains("return String((ed.tagName") -> result(editor)
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
        return runBlocking {
            if (beta) XPostBetaAutomation(x, bridge).run { post("Hello from Farrow"); collect(calls, lastStepLog, lastPostTimings, editor) }
            else SocialAutomation(x, bridge).run { post("Hello from Farrow"); collect(calls, lastStepLog, lastPostTimings, editor) }
        }.also { assertTrue(posted) }
    }

    private fun collect(calls: List<Pair<String, JsonObject>>, log: List<String>, t: Map<String, Long>, editor: String) = synchronized(calls) {
        Run(calls.filter { it.first !in setOf("eval", "ready") }.map { it.first + " " + (it.second["selector"] ?: it.second["url"]) },
            calls.filter { it.first == "eval" }.map { it.second["expression"]!!.jsonPrimitive.content.take(40) }, log, t, editor)
    }

    @Test fun `same bridge calls, steps and timings as x_post - only typing differs`() {
        val xPost = run(beta = false)
        val beta = run(beta = true)
        assertEquals(xPost.timings.keys.toList(), beta.timings.keys.toList())
        assertEquals(xPost.log.map { it.substringBefore(":") }, beta.log.map { it.substringBefore(":") })
        // x_post: nav, click box, editor_type (keystrokes), click Post — beta: the same without editor_type.
        assertEquals(xPost.acting.filterNot { it.startsWith("editor_type") }, beta.acting)
        assertTrue(xPost.acting.any { it.startsWith("editor_type") })
        assertTrue(beta.log[3], beta.log[3].startsWith("4. typeEditor composeText: ok") && beta.log[3].contains("instant typing (no keystrokes); insertText: inserted"))
        assertEquals("Hello from Farrow", beta.typed)
    }
}
