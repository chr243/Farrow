package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReplyComposerTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val spec get() = x.reply!!
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    /** What [ReplyComposer.markJs] produces in the browser: marked conversation column + dialogs in root wrappers. */
    private fun marked(html: String): Pair<String, org.jsoup.nodes.Document> {
        val doc = Jsoup.parse(html)
        var n = 0
        val roots = listOfNotNull(doc.selectFirst(ReplyComposer.jsoupCss(spec.conversation)).let { it?.let { "conversation" to it } }) +
            doc.select(spec.dialog).map { "dialog" to it }
        val sb = StringBuilder()
        roots.forEach { (kind, r) ->
            r.select("[data-testid],[role],[contenteditable],button,a,select,input,textarea").forEach { it.attr(ReplyComposer.MARK, (n++).toString()) }
            sb.append("<div ${ReplyComposer.ROOT}=\"$kind\">").append(r.outerHtml()).append("</div>")
        }
        return sb.toString() to doc
    }

    @Test fun `x json v7 has the reply spec and forbids toolbar controls`() {
        assertTrue(x.version >= 7)
        assertTrue(spec.forbidden.contains("[data-testid=\"scheduleOption\"]"))
        assertTrue(spec.forbidden.any { it.contains("gifSearchButton") } && spec.forbidden.any { it.contains("createPollButton") })
        assertTrue(spec.focal.contains("primaryColumn") && spec.focal.contains("tabindex=\"-1\""))
        assertEquals("https://x.com/intent/post?in_reply_to=123", x.url("replyIntent", mapOf("id" to "123")))
    }

    @Test fun `inline composer - its own tweetButtonInline, never schedule, never the sidebar Post button`() {
        val (h, doc) = marked(fixture("x_reply_inline.html"))
        val p = ReplyComposer.locate(h, spec, "https://x.com/alice/status/100") as ComposerPick.Found
        assertEquals(ComposerKind.INLINE, p.kind)
        val box = doc.selectFirst(p.box)!!; val send = doc.selectFirst(p.send)!!
        assertEquals("tweetTextarea_0", box.attr("data-testid"))
        assertEquals("tweetButtonInline", send.attr("data-testid"))
        assertTrue(send.parents().any { it.attr("data-testid") == "inline_reply_offscreen" })
        assertTrue(p.sendEnabled)
        assertEquals("Great point!", p.boxText)
    }

    @Test fun `modal composer wins over the inline one, submit is the dialog's tweetButton (disabled until typed)`() {
        val (h, doc) = marked(fixture("x_reply_modal.html"))
        val p = ReplyComposer.locate(h, spec, "https://x.com/alice/status/100") as ComposerPick.Found
        assertEquals(ComposerKind.MODAL, p.kind)
        val send = doc.selectFirst(p.send)!!
        assertEquals("tweetButton", send.attr("data-testid"))
        assertTrue(send.parents().any { it.attr("role") == "dialog" })
        assertTrue(doc.selectFirst(p.box)!!.parents().any { it.attr("role") == "dialog" })
        assertFalse(p.sendEnabled)
        // Never one of the forbidden controls.
        listOf(p.box, p.send).map { doc.selectFirst(it)!! }.forEach { e ->
            assertFalse(spec.forbidden.map(ReplyComposer::jsoupCss).any { f -> e.`is`(f) })
        }
        // Inline fallback can still be forced.
        val inline = ReplyComposer.locate(h, spec, null, preferInline = true) as ComposerPick.Found
        assertEquals(ComposerKind.INLINE, inline.kind)
    }

    @Test fun `schedule dialog or schedule URL is recognised as the wrong state`() {
        val (h, _) = marked(fixture("x_reply_schedule_dialog.html"))
        val w = ReplyComposer.locate(h, spec, "https://x.com/alice/status/100")
        assertTrue(w.toString(), w is ComposerPick.Wrong)
        val (h2, _) = marked(fixture("x_reply_inline.html"))
        val u = ReplyComposer.locate(h2, spec, "https://x.com/compose/post/schedule")
        assertTrue(u is ComposerPick.Wrong)
        assertNotNull(ReplyComposer.isWrongUrl("https://x.com/compose/post/unsent/drafts", spec))
        assertNull(ReplyComposer.isWrongUrl("https://x.com/alice/status/100", spec))
    }

    @Test fun `a composer whose only button is scheduleOption is refused`() {
        val html = fixture("x_reply_inline.html").replace("""<button data-testid="tweetButtonInline" role="button">Reply</button>""", "")
        val (h, _) = marked(html)
        val p = ReplyComposer.locate(h, spec, null)
        assertTrue(p.toString(), p is ComposerPick.Missing)
    }

    @Test fun `a reply box inside a post is never used`() {
        val html = fixture("x_reply_inline.html").replace("""<div data-testid="cellInnerDiv"><div data-testid="inline_reply_offscreen">""",
            """<div data-testid="cellInnerDiv"><article data-testid="tweet"><div data-testid="inline_reply_offscreen">""")
            .replace("""<button data-testid="tweetButtonInline" role="button">Reply</button>
    </div>
  </div></div>""", """<button data-testid="tweetButtonInline" role="button">Reply</button>
    </div>
  </div></article></div>""")
        val (h, _) = marked(html)
        assertTrue(ReplyComposer.locate(h, spec, null) is ComposerPick.Missing)
    }

    @Test fun `mark JS targets the conversation column and dialogs`() {
        val js = ReplyComposer.markJs(spec)
        assertTrue(js.contains("primaryColumn") && js.contains("role=\\\"dialog\\\""))
        assertTrue(js.contains(ReplyComposer.MARK))
    }

    @Test fun `system prompt routes replies to x_reply`() {
        val g = com.farrow.app.agent.AgentLoop.TOOL_GROUPS
        assertTrue(g.contains("ALWAYS call x_reply"))
        assertTrue(g.contains("NEVER"))
    }
}
