package com.farrow.app.data.social

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Leftover dialogs (unsent posts/drafts view, schedule picker, a composer, "Save post?" sheet) that block x_post. */
@Serializable
data class StraySpec(
    val overlays: List<String> = listOf("[role=\"dialog\"]", "[aria-modal=\"true\"]"),
    /** Container of X's popups; its children with interactive content count as overlays too. */
    val layers: String? = "#layers",
    /** Anything containing one of these is an overlay (even without role=dialog). */
    val markers: List<String> = emptyList(),
    /** Layer children that are only these (toast, hover card) are harmless. */
    val ignore: List<String> = emptyList(),
    val closeButtons: List<String> = emptyList(),
    val discardButtons: List<String> = emptyList(),
    /** Regex (case-insensitive) of the "Discard" button text in a Save/Discard sheet. */
    val discardText: String = "^(discard)$",
    /** Regex of Save-like texts: such a button is NEVER clicked. */
    val saveText: String = "^(save)\\b",
    val mask: String? = null,
    val maxRounds: Int = 6,
)

/** One overlay to get rid of. Selectors address the live page via `data-farrow-s` marks. */
data class Stray(val signature: String, val discard: String?, val close: String?, val isSaveSheet: Boolean, val summary: String)

object StrayDialogs {
    const val MARK = "data-farrow-s"
    const val ROOT = "data-farrow-overlay"

    /** Page CSS → Jsoup CSS (Jsoup has no `[attr*="x" i]` flag). */
    internal fun jsoupCss(css: String) = css.replace(Regex("\"\\s+i\\]"), "\"]")

    private fun css(s: String) = jsoupCss(s)

    /**
     * Overlays in the marked snapshot ([snapshotJs]) that are not [ours] (the CSS of our own compose box, if we
     * opened one). Top-most last.
     */
    fun analyze(html: String, spec: StraySpec, ours: String? = null): List<Stray> {
        val doc = Jsoup.parse(html)
        val save = Regex(spec.saveText, RegexOption.IGNORE_CASE)
        val discardRe = Regex(spec.discardText, RegexOption.IGNORE_CASE)
        val ignore = spec.ignore.map(::css)
        val out = mutableListOf<Stray>()
        for (o in doc.select("[$ROOT]")) {
            val inner = o.children().firstOrNull() ?: continue
            if (ours != null && o.selectFirst(css(ours)) != null) continue
            if (GrokShield.isGrokOverlay(inner)) continue // the shield hides it; never "clean" it with Escape loops
            val interactive = inner.select("button, [role=button], a, input, textarea, select, [contenteditable]").filter { e -> ignore.none { e.closest(it) != null } }
            if (interactive.isEmpty()) continue // empty layer, toast, hover card
            val buttons = inner.select("button, [role=button]")
            fun txt(e: Element) = e.text().trim()
            val saveLike = buttons.any { save.containsMatchIn(txt(it)) }
            val discard = buttons.firstOrNull { discardRe.containsMatchIn(txt(it)) && !save.containsMatchIn(txt(it)) }
                ?: if (saveLike) spec.discardButtons.map(::css).firstNotNullOfOrNull { s -> inner.select(s).firstOrNull { !save.containsMatchIn(txt(it)) && txt(it).isNotBlank() } } else null
            val close = spec.closeButtons.map(::css).firstNotNullOfOrNull { s -> inner.select(s).firstOrNull { !save.containsMatchIn(txt(it)) } }
            out += Stray(
                signature = inner.text().take(120) + "#" + inner.select("[data-testid]").joinToString(",") { it.attr("data-testid") }.take(200),
                discard = discard?.attr(MARK)?.takeIf { it.isNotBlank() }?.let { "[$MARK=\"$it\"]" },
                close = close?.attr(MARK)?.takeIf { it.isNotBlank() }?.let { "[$MARK=\"$it\"]" },
                isSaveSheet = saveLike,
                summary = inner.text().take(80).ifBlank { inner.select("[data-testid]").joinToString(" ") { it.attr("data-testid") }.take(80) },
            )
        }
        return out
    }

    /** Marks overlays (dialogs, aria-modal, sheets, #layers children, anything around a marker) → {h, u, mask}. */
    fun snapshotJs(spec: StraySpec): String {
        val q = { s: String -> JsonPrimitive(s).toString() }
        val overlays = q(spec.overlays.joinToString(", "))
        val markers = q(spec.markers.joinToString(", ").ifBlank { "#__none__" })
        val layers = q(spec.layers ?: "#__none__")
        val mask = q(spec.mask ?: "#__none__")
        return """(()=>{document.querySelectorAll('[$MARK]').forEach(e=>e.removeAttribute('$MARK'));let n=0;
            const set=new Set();document.querySelectorAll($overlays).forEach(e=>set.add(e));
            const L=document.querySelector($layers);if(L)[...L.children].forEach(e=>set.add(e));
            document.querySelectorAll($markers).forEach(m=>{const d=m.closest($overlays)||(L&&[...L.children].find(c=>c.contains(m)));if(d)set.add(d)});
            [...set].forEach(e=>{if(e.closest('[${GrokShield.ATTR}]'))set.delete(e)}); // hidden by the Grok shield
            const roots=[...set].filter(e=>![...set].some(o=>o!==e&&o.contains(e)));
            let h='';roots.forEach(r=>{r.querySelectorAll('*').forEach(e=>e.setAttribute('$MARK',String(n++)));
              const k=r.cloneNode(true);k.querySelectorAll('svg,img,video,picture,style,script,noscript').forEach(e=>e.remove());
              k.querySelectorAll('*').forEach(e=>{e.removeAttribute('class');e.removeAttribute('style')});
              h+='<div $ROOT="1">'+k.outerHTML+'</div>'});
            return JSON.stringify({h:h,u:location.href,mask:!!document.querySelector($mask)})})()""".trimIndent()
    }
}
