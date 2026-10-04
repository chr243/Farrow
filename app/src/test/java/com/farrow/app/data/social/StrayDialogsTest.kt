package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StrayDialogsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val spec get() = x.reply!!.stray
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun c(s: String) = ReplyComposer.jsoupCss(s)

    /** Same selection as [StrayDialogs.snapshotJs] in the browser. */
    private fun snapshot(html: String): Pair<String, Document> {
        val doc = Jsoup.parse(html)
        val set = LinkedHashSet<Element>()
        doc.select(spec.overlays.joinToString(", ") { c(it) }).forEach { set += it }
        val layers = spec.layers?.let { doc.selectFirst(it) }
        layers?.children()?.forEach { set += it }
        doc.select(spec.markers.joinToString(", ") { c(it) }).forEach { m ->
            (m.closest(spec.overlays.joinToString(", ") { c(it) }) ?: layers?.children()?.firstOrNull { m in it.allElements })?.let { set += it }
        }
        val roots = set.filter { e -> set.none { o -> o !== e && e.parents().contains(o) } }
        var n = 0
        val sb = StringBuilder()
        roots.forEach { r -> r.allElements.drop(1).forEach { it.attr(StrayDialogs.MARK, (n++).toString()) }
            sb.append("<div ${StrayDialogs.ROOT}=\"1\">").append(r.outerHtml()).append("</div>") }
        return sb.toString() to doc
    }

    @Test fun `x json v8 has the stray spec and the unsent and drafts wrong-state markers`() {
        assertTrue(x.version >= 8)
        val r = x.reply!!
        assertTrue(r.wrongDialog.contains("[data-testid=\"unsentButton\"]"))
        assertTrue(r.wrongUrlPatterns.contains("/drafts") && r.wrongUrlPatterns.contains("/unsent"))
        assertTrue(spec.overlays.contains("div[data-testid=\"sheetDialog\"]") && spec.overlays.contains("[aria-modal=\"true\"]"))
        assertEquals("#layers", spec.layers)
    }

    @Test fun `unsent posts view in layers (no role=dialog) is found and closed with app-bar-close, toast ignored`() {
        val (h, doc) = snapshot(fixture("x_unsent_dialog.html"))
        val s = StrayDialogs.analyze(h, spec)
        assertEquals(s.toString(), 1, s.size)
        assertTrue(s[0].summary.contains("Unsent posts"))
        assertFalse(s[0].isSaveSheet)
        assertNull(s[0].discard)
        assertEquals("app-bar-close", doc.selectFirst(s[0].close!!)!!.attr("data-testid"))
        // And ReplyComposer sees the unsent marker as a wrong state if it were a dialog.
        assertTrue(x.reply!!.wrongDialog.any { doc.selectFirst(c(it)) != null })
    }

    @Test fun `Save post sheet - Discard is picked, never Save`() {
        val (h, doc) = snapshot(fixture("x_save_discard_sheet.html"))
        val s = StrayDialogs.analyze(h, spec)
        assertEquals(2, s.size)
        val sheet = s.last() // top-most
        assertTrue(sheet.isSaveSheet)
        val d = doc.selectFirst(sheet.discard!!)!!
        assertEquals("Discard", d.text()); assertEquals("confirmationSheetCancel", d.attr("data-testid"))
        assertNotEquals("Save", sheet.close?.let { doc.selectFirst(it)?.text() })
        // The leftover composer below it is stray too.
        assertTrue(doc.selectFirst("[data-farrow-s]") != null && s.first().summary.contains("half typed"))
    }

    @Test fun `French Save sheet with only Enregistrer and a non-labelled cancel is never saved`() {
        val html = fixture("x_save_discard_sheet.html").replace("<span>Save</span>", "<span>Enregistrer</span>").replace("<span>Discard</span>", "<span>Supprimer</span>")
        val (h, doc) = snapshot(html)
        val sheet = StrayDialogs.analyze(h, spec).last()
        assertEquals("Supprimer", doc.selectFirst(sheet.discard!!)!!.text())
        val only = fixture("x_save_discard_sheet.html").replace("""<button data-testid="confirmationSheetCancel" role="button"><span>Discard</span></button>""", "")
        val (h2, doc2) = snapshot(only)
        val s2 = StrayDialogs.analyze(h2, spec).last()
        assertTrue(s2.discard == null || doc2.selectFirst(s2.discard!!)!!.text() != "Save")
        assertTrue(s2.close == null || doc2.selectFirst(s2.close!!)!!.text() != "Save")
    }

    @Test fun `our own reply composer is not stray, a clean page has none`() {
        val (h, doc) = snapshot(fixture("x_save_discard_sheet.html").replace(Regex("""<div><div data-testid="sheetDialog"[\s\S]*?</div></div>"""), ""))
        val box = doc.selectFirst("[role=dialog] [data-testid=tweetTextarea_0]")!!.attr(StrayDialogs.MARK)
        assertTrue(StrayDialogs.analyze(h, spec, ours = "[${StrayDialogs.MARK}=\"$box\"]").isEmpty())
        val (h2, _) = snapshot(fixture("x_reply_inline.html"))
        assertTrue(StrayDialogs.analyze(h2, spec).isEmpty())
    }

    @Test fun `not-a-post URLs`() {
        listOf("https://x.com/compose/post", "https://x.com/compose/post/unsent/drafts", "https://x.com/i/flow/login").forEach {
            assertNotNull(it, StrayDialogs.notOnPost(it, spec))
        }
        assertNull(StrayDialogs.notOnPost("https://x.com/alice/status/100", spec))
        assertTrue(ReplyComposer.locate("<div ${ReplyComposer.ROOT}=\"conversation\"></div>", x.reply!!, "https://x.com/compose/post/unsent/drafts") is ComposerPick.Wrong)
    }

    @Test fun `snapshot JS covers dialogs, sheets, layers and markers`() {
        val js = StrayDialogs.snapshotJs(spec)
        listOf("sheetDialog", "aria-modal", "#layers", "unsentButton", "scheduledConfirmationPrimaryAction", "app-bar-close").forEach { assertTrue(it, js.contains(it)) }
    }

    @Test fun `v1_0_7 - a Grok drawer over the intent composer is not a stray dialog`() {
        val (h, _) = snapshot(fixture("x_intent_reply_grok.html"))
        val s = StrayDialogs.analyze(h, spec, ours = x.reply!!.textarea)
        assertTrue(s.toString(), s.none { it.summary.contains("Grok", ignoreCase = true) })
        assertTrue(s.toString(), s.isEmpty())
    }
}
