package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * v1.0.8: x_reply in the intent composer (a modal over /home with the home composer behind it) types with x_post's
 * routine into the DIALOG's editor: trusted click on the marked editor → activeElement check → editor_type → verify.
 * A dead editor fails fast with the target in the log instead of running into the 60 s budget.
 */
class XReplyTypingTest {
    private val server = MiniHttpServer()
    private val cmds = mutableListOf<String>()
    private val clicks = mutableListOf<String>()
    private val typedInto = mutableListOf<String>()
    @Volatile var editor = ""
    @Volatile var editorDead = false
    @Volatile var posted = false

    @After fun stop() = server.stop()

    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val spec get() = x.reply!!

    /** markJs's output for the fixture (same marking as ReplyComposerTest). */
    private val snapshot: String by lazy {
        val doc = Jsoup.parse(javaClass.getResource("/fixtures/x_intent_modal_over_home.html")!!.readText())
        var n = 0
        val roots = listOfNotNull(doc.selectFirst(ReplyComposer.jsoupCss(spec.conversation))?.let { "conversation" to it }) +
            doc.select(spec.dialog).map { "dialog" to it }
        val sb = StringBuilder()
        roots.forEach { (kind, r) ->
            r.select("[data-testid],[role],[contenteditable],button,a,select,input,textarea").forEach { it.attr(ReplyComposer.MARK, (n++).toString()) }
            sb.append("<div ${ReplyComposer.ROOT}=\"$kind\">").append(r.outerHtml()).append("</div>")
        }
        buildJsonObject { put("h", sb.toString()); put("u", "https://x.com/compose/post") }.toString()
    }

    private fun res(ok: Boolean, data: JsonElement? = null, stderr: String = "", stdout: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", stdout); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()
    private fun value(v: String) = res(true, buildJsonObject { put("result", v) })

    private fun startBridge(): BridgeClient {
        server.route("/cmd") { rq, out ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(cmds) { cmds += cmd }
            val body = when (cmd) {
                "ready" -> res(true, buildJsonObject { put("eval_ok", true) })
                "nav" -> res(true, buildJsonObject { put("url", "https://x.com/compose/post"); put("matched", true); put("seconds", 1) })
                "click" -> {
                    val sel = args["selector"]!!.jsonPrimitive.content
                    synchronized(clicks) { clicks += sel }
                    if (sel != ReplyComposer.EDITOR_CSS) posted = true
                    res(true)
                }
                "focus" -> res(true, stdout = "focused")
                "key", "press" -> res(true)
                "editor_type" -> {
                    synchronized(typedInto) { typedInto += args["selector"]!!.jsonPrimitive.content }
                    if (editorDead) res(false, stderr = "text not in the editor after typing and insertText (editor has 0 chars)")
                    else { editor = args["text"]!!.jsonPrimitive.content; res(true, buildJsonObject { put("method", "xdotool") }) }
                }
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        e.contains(",mask:") -> value(buildJsonObject { put("h", ""); put("u", "https://x.com/compose/post"); put("mask", false) }.toString())
                        e.contains("JSON.stringify({h:h,u:location.href})") -> value(snapshot)
                        e.contains("ctx:t") -> value(buildJsonObject { put("u", "https://x.com/compose/post"); put("ctx", "Replying to\n@bitcloud\nPost your reply") }.toString())
                        e.contains("const A='${ReplyComposer.EDITOR_ATTR}'") -> value("""{"ok":true,"testid":"tweetTextarea_0","cls":"notranslate public-DraftEditor-content","rect":[16,180,328,24],"inDialog":true,"focused":false,"boxes":2,"chars":0}""")
                        e.contains("?'yes':'no'") -> value("yes")
                        e.contains("__missing__") -> value(editor)
                        e.contains("JSON.stringify({t:ed") -> value(buildJsonObject { put("t", editor); put("en", editor.isNotEmpty()); put("send", true); put("save", false) }.toString())
                        e.contains("toast:t?") -> value(if (posted) """{"toast":"Your post was sent. View","link":"https://x.com/me/status/999","box":null,"save":false}""" else """{"toast":null,"link":null,"box":"","save":false}""")
                        e.contains("B.exists(") -> value("true")
                        else -> value("")
                    }
                }
                else -> res(false, stderr = "unknown cmd: $cmd")
            }
            out.reply(200, body)
        }
        server.start()
        return BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" })
    }

    @Test fun `intent modal over home - trusted click and editor_type on the dialog's editor, verified, then one submit`() = runBlocking {
        val a = SocialAutomation(x, startBridge())
        val r = a.reply("https://x.com/bitcloud/status/1841234567890123456", "Nice one")
        val log = r.steps.joinToString("\n")
        assertEquals(log, ComposerKind.MODAL, r.composer)
        assertEquals("https://x.com/me/status/999", r.replyUrl)
        // x_post's routine, on the editor marked inside the dialog (never the first tweetTextarea_0 = the home composer).
        assertEquals(listOf(ReplyComposer.EDITOR_CSS), typedInto)
        assertEquals(ReplyComposer.EDITOR_CSS, clicks.first())
        assertEquals(log, 1, clicks.count { it != ReplyComposer.EDITOR_CSS })
        val c = synchronized(cmds) { cmds.toList() }
        assertTrue(c.toString(), c.indexOf("click") < c.indexOf("editor_type"))
        assertTrue(log, log.contains("editor target: testid=tweetTextarea_0") && log.contains("inDialog=true") && log.contains("rect=16,180,328,24"))
        assertTrue(log, log.contains("focus: activeElement is in the editor"))
        assertTrue(log, log.contains("verify: the dialog's editor holds the text"))
        assertTrue(log, log.contains("timing: open composer") && log.contains("timing: type") && log.contains("timing: submit"))
    }

    @Test fun `dead editor - one insertText and paste retry, then a fast failure naming the target, nothing posted`() = runBlocking {
        editorDead = true
        val a = SocialAutomation(x, startBridge())
        val t0 = System.currentTimeMillis()
        val e = runCatching { a.reply("https://x.com/bitcloud/status/1841234567890123456", "Nice one") }.exceptionOrNull()
        val took = System.currentTimeMillis() - t0
        assertTrue("$e", e is ReplyFailedException)
        val log = (e as ReplyFailedException).steps.joinToString("\n")
        assertTrue(e.message, e.message!!.contains("typing failed in the modal reply composer"))
        assertTrue(e.message, e.message!!.contains("inDialog=true"))
        assertTrue(log, log.contains("retry: refocus + insertText"))
        assertTrue(log, log.contains("stage timings:"))
        assertFalse("never submitted", posted)
        assertEquals(1, typedInto.size)
        assertTrue("failed fast ($took ms), not at the 60 s budget", took < 25_000)
    }
}
