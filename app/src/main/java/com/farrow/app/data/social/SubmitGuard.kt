package com.farrow.app.data.social

import kotlinx.serialization.json.JsonPrimitive

/**
 * v1.0.6: submit only a settled composer, and recognise the "Save post?" sheet. Pure parts are unit-tested.
 * Before the single submit click the composer text must EQUAL the intended text and stay unchanged for [STABLE_MS],
 * and the submit button must be enabled (no aria-disabled="true", not disabled).
 */
object SubmitGuard {
    const val STABLE_MS = 500L
    const val SETTLE_TIMEOUT_MS = 8_000L

    fun norm(t: String?): String = (t ?: "").replace("\u200b", "").replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    fun equalsIntended(editor: String?, intended: String): Boolean = norm(editor) == norm(intended)

    /** True when the box holds nothing (X shows an empty contenteditable, maybe just a newline / ZWSP). */
    fun isEmpty(editor: String?): Boolean = norm(editor).isEmpty()

    /** Feed probes in order; [ready] once text == intended and enabled continuously for [stableMs]. */
    class Settle(private val intended: String, private val stableMs: Long = STABLE_MS) {
        private var since: Long? = null
        private var lastText: String? = null
        var lastWhy: String = "not probed yet"; private set
        fun feed(text: String?, enabled: Boolean, nowMs: Long): Boolean {
            val n = norm(text)
            val match = n == norm(intended)
            lastWhy = when {
                !match -> "the box holds \"${n.take(60)}\" (${n.length} chars), not the intended ${norm(intended).length} chars"
                !enabled -> "the submit button is disabled"
                else -> "settled"
            }
            if (!match || !enabled) { since = null; lastText = n; return false }
            if (n != lastText || since == null) { since = nowMs; lastText = n; return stableMs <= 0 }
            return nowMs - since!! >= stableMs
        }
    }

    private fun js(s: String) = JsonPrimitive(s).toString()

    /**
     * One eval: {t: editor text, en: submit enabled, send: submit present, save: a "Save post?" sheet is open}.
     * [boxCss] is the composer box (or its contenteditable), [sendCss] its submit button.
     */
    fun probeJs(boxCss: String, sendCss: String, stray: StraySpec): String =
        "(()=>{const ed=" + DomFinder.editorText(boxCss) + ";const b=document.querySelector(${js(sendCss)});" +
            "const en=!!b&&b.getAttribute('aria-disabled')!=='true'&&!b.disabled;" +
            "return JSON.stringify({t:ed,en:en,send:!!b,save:" + saveSheetExpr(stray) + "})})()"

    /** JS boolean: an open sheet/dialog with a Save button AND a Discard button (X's "Save post?" prompt). */
    fun saveSheetExpr(stray: StraySpec): String {
        val overlays = js((stray.overlays + "[data-testid=\"confirmationSheetDialog\"]").joinToString(", "))
        val save = js(stray.saveText)
        val discard = js(stray.discardText)
        return "(()=>{const S=new RegExp($save,'i'),D=new RegExp($discard,'i');return [...document.querySelectorAll($overlays)].some(d=>{" +
            "const bs=[...d.querySelectorAll('button,[role=\"button\"]')].map(x=>(x.innerText||x.getAttribute('aria-label')||'').trim());" +
            "return bs.some(t=>S.test(t))&&bs.some(t=>D.test(t))})})()"
    }
}
