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

/**
 * v1.0.9: x_reply runs x_post's implementation (same steps, selectors, click + editor_type, settle, submit, waitPosted),
 * only the opening differs (/compose/post?in_reply_to=<id>, else the post page) plus the "Replying to @author" pre-check.
 */
class XReplyViaPostTest {
    private val server = MiniHttpServer()
    private val cmds = mutableListOf<String>()
    private val navs = mutableListOf<String>()
    private val clicks = mutableListOf<String>()
    private val typedInto = mutableListOf<String>()
    @Volatile var editor = ""
    @Volatile var posted = false
    /** On /compose/post the first composeText is the home box behind the modal (not in the dialog). */
    @Volatile var homeBoxBehindModal = false
    @Volatile var dialogAuthor = "bitcloud"
    @Volatile var postPageHasInlineBox = true

    @After fun stop() = server.stop()

    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val composeText get() = x.sel("composeText")
    private val post = "https://x.com/bitcloud/status/1841234567890123456"

    private fun res(ok: Boolean, data: JsonElement? = null, stderr: String = "", stdout: String = "") = buildJsonObject {
        put("ok", ok); put("code", if (ok) 0 else 1); put("stdout", stdout); put("stderr", stderr); data?.let { put("data", it) }
    }.toString()
    private fun value(v: String) = res(true, buildJsonObject { put("result", v) })

    private fun target(): String {
        val page = navs.lastOrNull().orEmpty()
        return if (page.contains("/compose/post")) {
            if (homeBoxBehindModal) """{"ok":true,"testid":"tweetTextarea_0","cls":"public-DraftEditor-content","rect":[16,120,328,24],"inDialog":false,"inConversation":true,"inArticle":false,"focused":false,"boxes":2,"chars":0,"u":"https://x.com/compose/post","ctx":"What is happening?!"}"""
            else """{"ok":true,"testid":"tweetTextarea_0","cls":"public-DraftEditor-content","rect":[16,180,328,24],"inDialog":true,"inConversation":false,"inArticle":false,"focused":true,"boxes":1,"chars":0,"u":"https://x.com/compose/post","ctx":"Replying to\n@$dialogAuthor\nPost your reply"}"""
        } else if (!postPageHasInlineBox) """{"ok":false,"why":"no composer box","boxes":0,"u":"$post"}"""
        else """{"ok":true,"testid":"tweetTextarea_0","cls":"public-DraftEditor-content","rect":[16,640,328,24],"inDialog":false,"inConversation":true,"inArticle":false,"focused":false,"boxes":1,"chars":0,"u":"$post","ctx":"Post your reply"}"""
    }

    private fun startBridge(): BridgeClient {
        server.route("/cmd") { rq, out ->
            val o = Json.parseToJsonElement(rq.body).jsonObject
            val cmd = o["cmd"]!!.jsonPrimitive.content
            val args = o["args"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(cmds) { cmds += cmd }
            val body = when (cmd) {
                "ready" -> res(true, buildJsonObject { put("eval_ok", true) })
                "nav" -> { val u = args["url"]!!.jsonPrimitive.content; synchronized(navs) { navs += u }
                    res(true, buildJsonObject { put("url", if (u.contains("/compose/post")) "https://x.com/compose/post" else u); put("matched", true); put("seconds", 1) }) }
                "click" -> { val sel = args["selector"]!!.jsonPrimitive.content; synchronized(clicks) { clicks += sel }
                    if (sel.contains("tweetButton")) posted = true; res(true) }
                "focus" -> res(true, stdout = "focused")
                "key", "press" -> res(true)
                "editor_type" -> { synchronized(typedInto) { typedInto += args["selector"]!!.jsonPrimitive.content }
                    editor = args["text"]!!.jsonPrimitive.content; res(true, buildJsonObject { put("method", "xdotool") }) }
                "eval" -> {
                    val e = args["expression"]!!.jsonPrimitive.content
                    when {
                        e.contains(",mask:") -> value(buildJsonObject { put("h", ""); put("u", navs.lastOrNull() ?: ""); put("mask", false) }.toString())
                        e.contains("ctx:String((d||c)") -> value(target())
                        e.contains("JSON.stringify({t:ed") -> value(buildJsonObject { put("t", editor); put("en", editor.isNotEmpty()); put("send", true); put("save", false) }.toString())
                        e.contains("B.first(") -> value(if (posted) "0" else "1")
                        e.contains("return a?a.href:''") -> value(if (posted) "https://x.com/me/status/999" else "")
                        e.contains("B.exists(") -> value(if (!postPageHasInlineBox && e.contains("primaryColumn")) "false" else "true")
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

    @Test fun `compose modal with Replying to - x_post's exact steps, selectors and typing call`() = runBlocking {
        val a = SocialAutomation(x, startBridge())
        val r = a.reply(post, "Nice one")
        val log = r.steps.joinToString("\n")
        assertEquals(listOf("https://x.com/compose/post?in_reply_to=1841234567890123456"), navs)
        assertEquals("x_post's typing call on x_post's selector", listOf(composeText), typedInto)
        assertEquals(composeText, clicks.first())
        assertEquals(log, 1, clicks.count { it.contains("tweetButton") })
        assertEquals(ComposerKind.MODAL, r.composer)
        assertEquals("https://x.com/me/status/999", r.replyUrl)
        assertTrue(log, log.contains("target: testid=tweetTextarea_0") && log.contains("inDialog=true") && log.contains("pre-check ok"))
        assertTrue(log, log.contains("typeEditor composeText: ok") && log.contains("editor_type via xdotool"))
        assertTrue(log, log.contains("timing: compose") && log.contains("stage timings:"))
    }

    @Test fun `x_post's selector hits the home box behind the modal - nothing typed there, the post page's inline box is used`() = runBlocking {
        homeBoxBehindModal = true
        val a = SocialAutomation(x, startBridge())
        val r = a.reply(post, "Nice one")
        val log = r.steps.joinToString("\n")
        assertEquals(listOf("https://x.com/compose/post?in_reply_to=1841234567890123456", post), navs)
        assertEquals("typed once, on the post page", listOf(composeText), typedInto)
        assertTrue(log, log.contains("hits a page box outside the composer dialog") && log.contains("→ next path"))
        assertEquals(ComposerKind.INLINE, r.composer)
        assertTrue(posted)
    }

    @Test fun `wrong author on compose and no reply box on the post - fails before typing or submitting`() = runBlocking {
        dialogAuthor = "someoneelse"; postPageHasInlineBox = false
        val a = SocialAutomation(x, startBridge())
        val e = runCatching { a.reply(post, "Nice one") }.exceptionOrNull()
        assertTrue("$e", e is ReplyFailedException)
        assertTrue(typedInto.isEmpty()); assertFalse(posted)
        assertTrue(e!!.message, e.message!!.contains("nothing was posted") || e.message!!.contains("could not open"))
    }

    @Test fun `x_post runs the same step list (plus the logging target step)`() {
        val a = SocialAutomation(x, BridgeClient(object : BridgeEndpoint { override val port = 1; override val token = "t" }))
        val steps = a.composerSteps(x.postSteps.takeWhile { it.action == "goto" })
        assertEquals(listOf("goto", "waitFor", "target", "click", "typeEditor", "settleSubmit", "click", "waitPosted"), steps.map { it.action })
        assertEquals(x.postSteps.filter { it.action != "target" }, steps.filter { it.action != "target" })
    }

    @Test fun `reply pre-check`() {
        val a = SocialAutomation(x, BridgeClient(object : BridgeEndpoint { override val port = 1; override val token = "t" }))
        val c = StatusUrl.canonical(post)!!
        val spec = x.reply!!
        fun t(json: String) = ReplyComposer.parseEditorTarget(json)
        assertNull(a.replyPreCheck(t("""{"ok":true,"inDialog":true,"u":"https://x.com/compose/post","ctx":"Replying to @bitcloud"}"""), c, spec, modal = true))
        assertNotNull(a.replyPreCheck(t("""{"ok":true,"inDialog":true,"u":"https://x.com/compose/post","ctx":"What is happening?!"}"""), c, spec, modal = true))
        assertNotNull(a.replyPreCheck(t("""{"ok":true,"inDialog":false,"inConversation":true,"u":"https://x.com/home"}"""), c, spec, modal = true))
        assertNull(a.replyPreCheck(t("""{"ok":true,"inDialog":false,"inConversation":true,"u":"$post"}"""), c, spec, modal = false))
        assertNotNull(a.replyPreCheck(t("""{"ok":true,"inDialog":false,"inConversation":true,"inArticle":true,"u":"$post"}"""), c, spec, modal = false))
        assertNotNull(a.replyPreCheck(t("""{"ok":true,"inDialog":false,"inConversation":true,"u":"https://x.com/home"}"""), c, spec, modal = false))
        assertNotNull(a.replyPreCheck(t("""{"ok":false,"why":"no composer box"}"""), c, spec, modal = false))
    }
}
