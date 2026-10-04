package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.0.4: the bubble path — which post and which reply button x_reply clicks. */
class ReplyTargetTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val spec get() = x.reply!!
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun marked(html: String): Pair<String, org.jsoup.nodes.Document> {
        val doc = Jsoup.parse(html)
        var n = 0
        val roots = listOfNotNull(doc.selectFirst(spec.conversation)?.let { "conversation" to it }) + doc.select(spec.dialog).map { "dialog" to it }
        val sb = StringBuilder()
        roots.forEach { (k, r) ->
            r.select("[data-testid],[role],[contenteditable],button,a,select,input,textarea").forEach { it.attr(ReplyComposer.MARK, (n++).toString()) }
            sb.append("<div ${ReplyComposer.ROOT}=\"$k\">").append(r.outerHtml()).append("</div>")
        }
        return sb.toString() to doc
    }

    private fun author(doc: org.jsoup.nodes.Document, t: TargetPost) = doc.selectFirst(t.article)!!.selectFirst("[data-testid=User-Name] a")!!.attr("href")

    @Test fun `focal post's own bubble is chosen even though an inline box exists`() {
        val (h, doc) = marked(fixture("x_reply_inline.html"))
        val t = ReplyComposer.target(h, spec, "100")!!
        assertEquals("/alice", author(doc, t)); assertEquals("status id", t.how)
        val b = doc.selectFirst(t.replyButton!!)!!
        assertEquals("reply", b.attr("data-testid"))
        assertTrue(b.parents().contains(doc.selectFirst(t.article)))
        // The composer locator would pick the inline box — the bubble path must come first (see openComposer).
        assertEquals(ComposerKind.INLINE, (ReplyComposer.locate(h, spec) as ComposerPick.Found).kind)
    }

    @Test fun `URL pointing at a reply targets that reply, not the focal post`() {
        val (h, doc) = marked(fixture("x_reply_inline.html"))
        val t = ReplyComposer.target(h, spec, "101")!!
        assertEquals("/bob", author(doc, t))
        assertTrue(doc.selectFirst(t.replyButton!!)!!.parents().contains(doc.selectFirst(t.article)))
    }

    @Test fun `no tabindex=-1 on the focal post (v1_0_2 skipped the bubble) - found by status id`() {
        val (h, doc) = marked(fixture("x_reply_inline.html").replace("tabindex=\"-1\"", ""))
        val t = ReplyComposer.target(h, spec, "100")!!
        assertEquals("/alice", author(doc, t)); assertNotNull(t.replyButton)
    }

    @Test fun `a reply button inside a quoted post is never the bubble`() {
        val html = fixture("x_reply_inline.html").replace(
            """<div role="group"><button data-testid="reply" aria-label="3 Replies. Reply"></button>""",
            """<div role="link"><a href="/carol/status/50"><time>1d</time></a><button data-testid="reply" aria-label="quoted reply"></button></div><div role="group"><button data-testid="reply" aria-label="3 Replies. Reply"></button>""")
        val (h, doc) = marked(html)
        val t = ReplyComposer.target(h, spec, "100")!!
        assertEquals("3 Replies. Reply", doc.selectFirst(t.replyButton!!)!!.attr("aria-label"))
        assertNull(ReplyComposer.target(h, spec, "50")?.takeIf { it.how == "status id" })
    }

    @Test fun `unknown id falls back to the focal post, none without focal`() {
        val (h, doc) = marked(fixture("x_reply_inline.html"))
        val t = ReplyComposer.target(h, spec, "999")!!
        assertEquals("focal", t.how); assertEquals("/alice", author(doc, t))
        val (h2, _) = marked(fixture("x_reply_inline.html").replace("tabindex=\"-1\"", ""))
        assertNull(ReplyComposer.target(h2, spec, "999"))
    }

    @Test fun `x json v9 bubble settings`() {
        assertTrue(x.version >= 9)
        assertEquals(5_000L, spec.bubbleWaitMs)
        assertEquals("article[data-testid=\"tweet\"]", spec.article)
    }
}
