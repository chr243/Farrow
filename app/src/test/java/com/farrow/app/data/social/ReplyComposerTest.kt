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

    // ---- v1.0.7: intent composer first + Grok shield ----

    @Test fun `intent composer under a Grok drawer - the composer is found, the drawer is never the top dialog`() {
        val (h, doc) = marked(fixture("x_intent_reply_grok.html"))
        val p = ReplyComposer.locate(h, spec, "https://x.com/compose/post") as ComposerPick.Found
        assertEquals(ComposerKind.MODAL, p.kind)
        assertEquals("tweetTextarea_0", doc.selectFirst(p.box)!!.attr("data-testid"))
        assertEquals("tweetButton", doc.selectFirst(p.send)!!.attr("data-testid"))
        // Without the Grok filter this was v1.0.6's "a dialog without a reply box is open (Grok …)".
        val dialogs = Jsoup.parse(h).select("[${ReplyComposer.ROOT}=dialog]").map { it.children().first()!! }
        assertEquals(listOf(false, true), dialogs.map(GrokShield::isGrokOverlay))
    }

    @Test fun `isGrokOverlay - Grok header or testid yes, composer with the Grok image button no`() {
        val doc = Jsoup.parse(fixture("x_intent_reply_grok.html"))
        assertTrue(GrokShield.isGrokOverlay(doc.selectFirst("[data-testid=GrokDrawer]")!!))
        assertFalse(GrokShield.isGrokOverlay(doc.selectFirst("[aria-modal=true]")!!))
        assertTrue(GrokShield.isGrokOverlay(Jsoup.parse("<div role=dialog><h2>Grok</h2><p>hi</p></div>").selectFirst("div")!!))
        assertTrue(GrokShield.isGrokOverlay(Jsoup.parse("<div ${GrokShield.ATTR}=x><p>hi</p></div>").selectFirst("div")!!))
        assertFalse(GrokShield.isGrokOverlay(Jsoup.parse("<div role=dialog><h2>Unsent posts</h2></div>").selectFirst("div")!!))
    }

    @Test fun `IntentComposer check - Replying to the right author passes, standalone or flow or wrong author fail`() {
        val re = spec.replyingTo
        fun raw(u: String, ctx: String) = kotlinx.serialization.json.buildJsonObject {
            put("u", kotlinx.serialization.json.JsonPrimitive(u)); put("ctx", kotlinx.serialization.json.JsonPrimitive(ctx)) }.toString()
        assertNull(IntentComposer.check(raw("https://x.com/compose/post", "Replying to\n@alice\nPost your reply"), re, "alice"))
        assertNull(IntentComposer.check(raw("https://x.com/compose/post", "En réponse à @Alice"), re, "alice"))
        assertNull(IntentComposer.check(raw("https://x.com/compose/post", "Replying to @bob"), re, "i"))
        assertTrue(IntentComposer.check(raw("https://x.com/compose/post", "What is happening?!"), re, "alice")!!.contains("standalone"))
        assertTrue(IntentComposer.check(raw("https://x.com/i/flow/login", "Sign in"), re, "alice")!!.contains("/i/flow/"))
        assertTrue(IntentComposer.check(raw("https://x.com/compose/post", "Replying to @alicex"), re, "alice")!!.contains("someone else"))
        assertNotNull(IntentComposer.check(null, re, "alice"))
        assertEquals("Replying to @alice on https://x.com/compose/post",
            IntentComposer.summary(raw("https://x.com/compose/post", "Replying to\n@alice"), re))
    }

    @Test fun `GrokShield JS is host-guarded, idempotent and never hides composers`() {
        assertTrue(GrokShield.ON.contains("(x|twitter)\\.com"))
        assertTrue(GrokShield.ON.contains("if(w.__fwGrok){"))
        assertTrue(GrokShield.ON.contains("tweetTextarea_") && GrokShield.ON.contains("r.querySelector(P)"))
        assertTrue(GrokShield.OFF.contains("__fwGrokOff"))
        java.io.File("build/tmp/grokshield").apply { mkdirs() }.resolve("on.js").writeText(GrokShield.ON)
    }

    // ---- v1.0.8: typing target in the intent modal over /home ----

    @Test fun `modal over home - the editor is the dialog's contenteditable, never the home composer behind it`() {
        val (h, doc) = marked(fixture("x_intent_modal_over_home.html"))
        // Root cause: the first tweetTextarea_0 in the document (what a plain querySelector / the old wait saw) is
        // the home timeline's composer BEHIND the modal.
        val first = doc.selectFirst(spec.textarea)!!
        assertTrue(first.parents().none { it.attr("role") == "dialog" })
        val p = ReplyComposer.locate(h, spec, "https://x.com/compose/post") as ComposerPick.Found
        assertEquals(ComposerKind.MODAL, p.kind)
        val ed = ReplyComposer.editorFor(doc, p.box, spec, modal = true)!!
        assertEquals("true", ed.attr("contenteditable"))
        assertEquals("tweetTextarea_0", ed.attr("data-testid"))
        assertTrue(ed.parents().any { it.attr("role") == "dialog" })
        assertNotSame(first, ed)
        // Even a stale mark pointing at the composer behind the modal resolves to the dialog's editor.
        first.attr(ReplyComposer.MARK, "stale")
        assertSame(ed, ReplyComposer.editorFor(doc, ReplyComposer.sel("stale"), spec, modal = true))
        assertSame(ed, ReplyComposer.editorFor(doc, null, spec, modal = true))
        // Inline keeps the marked box.
        assertSame(first, ReplyComposer.editorFor(doc, ReplyComposer.sel("stale"), spec, modal = false))
        // The intent wait now requires the box inside the dialog.
        assertEquals(1, doc.select("${spec.dialog} ${spec.textarea}").size)
        java.io.File("build/tmp/replytype").apply { mkdirs() }.let { d ->
            d.resolve("target.js").writeText(ReplyComposer.editorTargetJs(ReplyComposer.sel("stale"), spec, true))
            d.resolve("active.js").writeText(ReplyComposer.ACTIVE_JS)
            d.resolve("text.js").writeText(ReplyComposer.EDITOR_TEXT_JS)
            d.resolve("paste.js").writeText(ReplyComposer.pasteJs("hello world"))
        }
    }

    @Test fun `editor target is parsed and described for the step log`() {
        val t = ReplyComposer.parseEditorTarget("""{"ok":true,"testid":"tweetTextarea_0","cls":"notranslate public-DraftEditor-content","rect":[12,140.4,336,24],"inDialog":true,"focused":false,"boxes":2,"chars":0}""")
        assertTrue(t.ok && t.inDialog && !t.focused)
        assertEquals(listOf(12, 140, 336, 24), t.rect)
        assertEquals("testid=tweetTextarea_0 .notranslate rect=12,140,336,24 inDialog=true focused=false boxes=2 chars=0", t.describe())
        val none = ReplyComposer.parseEditorTarget("""{"ok":false,"why":"no dialog with a reply box is open","boxes":1}""")
        assertFalse(none.ok); assertTrue(none.describe().contains("no dialog"))
        assertFalse(ReplyComposer.parseEditorTarget(null).ok)
    }

    @Test fun `stage timings`() {
        var now = 1_000L
        val st = SocialAutomation.Stages { now }
        now += 3_100; assertEquals("open composer 3.1 s (t=3.1 s)", st.mark("open composer"))
        now += 900; assertEquals("type 0.9 s (t=4.0 s)", st.mark("type"))
        assertEquals("open composer 3.1 s · type 0.9 s · total 4.0 s", st.summary())
    }
}

