package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * x_post_beta (Farrow v1.0.14+, off by default): a faster X posting flow, kept apart from x_post. It has its own JS
 * and helpers ([XPostBetaShield], [XPostBetaJs]) and touches none of x_post's code (SocialAutomation.post and
 * runSteps, DomFinder, StrayDialogs, x.json postSteps). Only the raw bridge commands (ready/eval/nav/click/key/cancel)
 * and the site's selectors and URLs are read.
 *
 * Why x_post is slow on a phone: every bridge eval costs about 3–4 s (TBP activates the DevTools console window,
 * types the expression, and reads the result back), and x_post pays for many of them:
 *  - stray cleanup (45 s): a snapshot eval, then for every #layers child with a button (not only real dialogs) it
 *    clicks or presses Escape, settles with more snapshot evals, and repeats up to 6 rounds. When an overlay can't be
 *    closed, it hard-navigates home, polls, and runs a second cleanup. Its log went nowhere, so this never showed up.
 *  - click composeText (14 s; 68 s with v1.0.13's 30 s TBP cap): an exists eval, the bridge's element-centre eval,
 *    28 xdotool moves, the TBP click eval and a refocus. On a timeout it also waited for idle before the JS fallback,
 *    which is the one that always worked.
 *  - typeEditor (36 s): an exists eval, then the bridge's focus eval and 140 human-paced xdotool keystrokes (about
 *    20 s) that never reach Draft.js on the phone, then 3 more evals (verify, insertText, verify).
 *  - click composeSubmit (14 s, 68 s on v1.0.13): the same TBP click path.
 *
 * The beta does one eval per phase:
 *  probe (shield + leftover-dialog check, acting on a dialog in the same eval) → keyboard nav to compose → focus
 *  (find + focus + verify) → insert (insertText + verify) → JS click on Post → poll posted (shield off in the same eval).
 * The TBP click is only a fallback, used when the JS action had no visible effect.
 */
class XPostBeta(private val config: SiteConfig, private val bridge: BridgeClient) {

    /** `timings_ms` of the last [post]: ready, stray_check, goto_compose, focus_composeText, insert_text, sleep, click_composeSubmit, waitPosted, total. */
    @Volatile var lastTimings: Map<String, Long> = emptyMap()
        private set

    /** Step log of the last [post] (also on failure). */
    @Volatile var lastSteps: List<String> = emptyList()
        private set

    /** True when the last failed [post] stopped before any text was inserted (nothing can have been posted). */
    @Volatile var failedBeforeTyping = false
        private set

    /** Phase caps (tests shorten them). */
    internal var submitVerifyMs = SUBMIT_VERIFY_MS
    internal var pollMs = POLL_MS

    private val timings = LinkedHashMap<String, Long>()
    private val steps = mutableListOf<String>()

    suspend fun post(text: String) {
        val t0 = System.currentTimeMillis()
        timings.clear(); steps.clear(); failedBeforeTyping = true
        lastTimings = timings; lastSteps = steps
        val box = config.sel("composeText")
        val submit = config.sel("composeSubmit")
        var shieldOn = false
        try {
            phase("ready", 45_000) { ready() }
            phase("stray_check", STRAY_MAX_MS) { shieldOn = true; strayCheck() }
            phase("goto_compose", 50_000) { nav(config.url("compose")) }
            phase("focus_composeText", 45_000) { focus(box) }
            failedBeforeTyping = false
            phase("insert_text", 40_000) { insert(box, text) }
            phase("sleep", 5_000) { delay(SLEEP_MS); "${SLEEP_MS} ms" }
            phase("click_composeSubmit", 30_000) { clickSubmit(box, submit) }
            phase("waitPosted", 30_000) { waitPosted(box, submit, text).also { shieldOn = false } }
        } finally {
            if (shieldOn) withContext(NonCancellable) { withTimeoutOrNull(10_000) { runCatching { bridge.eval(XPostBetaShield.GROK_OFF, 10) } } }
            timings["total"] = System.currentTimeMillis() - t0
            lastTimings = LinkedHashMap(timings); lastSteps = steps.toList()
        }
    }

    // ---- phases ---------------------------------------------------------------------------------------------------

    private suspend fun ready(): String {
        val r = bridge.ready(30)
        if (r.ok || r.unknownCmd()) return "browser ready"
        throw AutomationException("The internal browser is not ready: ${r.errorMessage.take(300)}")
    }

    /**
     * One eval: shield on, URL, and real blocking dialogs only (role=dialog / aria-modal / sheet / X's draft and
     * schedule markers). A plain #layers child (DM drawer, banners, hover cards) is not a dialog. A dialog found is
     * acted on in the same eval (Discard, never Save; else Close; else the mask); the next probe is the check.
     * No dialog → done after this one eval. Never waits out a cap: it stops at the first clean probe, or after
     * [STRAY_ROUNDS] tries and just goes on (the compose navigation reloads the page anyway).
     */
    private suspend fun strayCheck(): String {
        val st = config.stray ?: StraySpec()
        val notes = mutableListOf<String>()
        val tried = HashMap<String, Int>()
        var lastS: String? = null
        for (round in 0 until STRAY_ROUNDS) {
            val k = lastS?.let { tried.getOrDefault(it, 0) } ?: 0
            val o = evalJson(XPostBetaJs.probe(st, lastS, k)) ?: throw AutomationException("the page did not answer the first check")
            val u = o.str("u")
            if (u != null && config.sessionExpiredUrlPatterns.any { u.contains(it) })
                throw SessionExpiredException(config.site, "${config.displayName} redirected to the login page ($u)")
            if ((o["n"]?.jsonPrimitive?.intOrNull ?: 0) == 0) {
                return if (notes.isEmpty()) "no dialog, url ok ($u)" else (notes + "clean").joinToString("; ")
            }
            val s = o.str("s").orEmpty()
            var what = o.str("what") ?: "none"
            if (what == "none") { key("Escape"); what = "escape" }
            tried[s] = (tried[s] ?: 0) + 1
            lastS = s
            notes += "dialog '${s.take(60)}' → $what"
            delay(pollMs)
        }
        return (notes + "still open after $STRAY_ROUNDS tries, going on").joinToString("; ")
    }

    private suspend fun nav(url: String): String {
        val r = bridge.nav(url, 40)
        if (r.unknownCmd()) throw AutomationException("x_post_beta needs bridge ≥ 1.8.0 (keyboard navigation); use x_post")
        val d = r.data as? JsonObject
        val now = d?.get("url")?.jsonPrimitive?.contentOrNull
        if (now != null && config.sessionExpiredUrlPatterns.any { now.contains(it) })
            throw SessionExpiredException(config.site, "${config.displayName} redirected to the login page ($now)")
        if (!r.ok && !sameTarget(now, url)) throw AutomationException("navigation failed: ${r.errorMessage.take(300)}")
        return "nav ${if (r.ok) "ok" else "unconfirmed but on target"} in ${d?.get("seconds")?.jsonPrimitive?.contentOrNull ?: "?"} s"
    }

    /** Poll: one eval finds the editor (the dialog's one first), focuses it and reports whether focus landed. */
    private suspend fun focus(box: String): String {
        var polls = 0
        while (true) {
            polls++
            when (val v = evalStr(XPostBetaJs.focus(box))) {
                "focused" -> return "JS focus ok ($polls eval${if (polls > 1) "s" else ""})"
                "nofocus" -> {
                    // JS focus had no effect → TBP click as the fallback, then one verify eval.
                    val r = tbpClick(XPostBetaJs.ED)
                    val again = evalStr(XPostBetaJs.focus(box))
                    if (again == "focused") return "JS focus had no effect → TBP click ${if (r) "ok" else "failed"} → focused"
                    throw AutomationException("the compose box did not take the focus (JS focus, then TBP click ${if (r) "ok" else "failed"})")
                }
                else -> { if (v != "missing" && v != null) steps += "focus: unexpected '$v'"; delay(pollMs) }
            }
        }
    }

    /** One eval: focus + select all + insertText (paste event as a last resort) + verify. A 'pending' editor gets one more look. */
    private suspend fun insert(box: String, text: String): String {
        val v = evalStr(XPostBetaJs.insert(box, text)).orEmpty()
        if (v.startsWith("ok")) return "insertText ok (${v.substringAfter(':')} chars, 1 eval)"
        if (v == "missing") throw AutomationException("the compose box disappeared before typing")
        delay(pollMs)
        val v2 = evalStr(XPostBetaJs.hasText(box, text)).orEmpty()
        if (v2.startsWith("ok")) return "insertText ok after a re-check (${v2.substringAfter(':')} chars)"
        val v3 = evalStr(XPostBetaJs.paste(box, text)).orEmpty()
        if (v3.startsWith("ok")) return "insertText $v → paste ok (${v3.substringAfter(':')} chars)"
        throw AutomationException("the text did not appear in the editor (insertText: $v; re-check: $v2; paste: $v3)")
    }

    /** One eval clicks the Post button next to our editor (waits while X still shows it disabled, ≤ 5 s). */
    private suspend fun clickSubmit(box: String, submit: String): String {
        val until = System.currentTimeMillis() + 5_000
        var last: String?
        do {
            last = evalStr(XPostBetaJs.clickSubmit(box, submit))
            if (last == "clicked") return "JS click"
            delay(pollMs)
        } while (System.currentTimeMillis() < until)
        // Button never clickable by JS → TBP click as the fallback, then ctrl+Return.
        if (tbpClick(submit)) return "JS click: $last → TBP click"
        key("ctrl+Return")
        return "JS click: $last → TBP click failed → ctrl+Return"
    }

    /**
     * Poll: one eval per round reports toast / cleared (no visible compose box holds the text) / sending (Post button
     * disabled) / open, and switches the Grok shield off in the same eval once posted. If the JS click had no effect
     * (still 'open' with the Post button enabled for [submitVerifyMs]), click with TBP once. Never more than one fallback.
     */
    private suspend fun waitPosted(box: String, submit: String, text: String): String {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 25_000
        var openSince: Long? = null
        var fallback: String? = null
        var polls = 0
        while (true) {
            polls++
            val st = evalStr(XPostBetaJs.posted(box, config.sel("postSuccess"), text)) ?: "open"
            val now = System.currentTimeMillis()
            when (st) {
                "toast", "cleared" -> return (listOfNotNull(fallback) + "${if (st == "toast") "success toast" else "compose box emptied/closed"} ($polls poll${if (polls > 1) "s" else ""})").joinToString("; ")
                "open" -> {
                    if (openSince == null) openSince = now
                    if (fallback == null && now - openSince >= submitVerifyMs) {
                        fallback = "JS click had no effect after ${(now - t0) / 1000.0} s → " +
                            (if (tbpClick(submit)) "TBP click" else { key("ctrl+Return"); "TBP click failed → ctrl+Return" })
                    }
                }
                else -> openSince = null // sending
            }
            if (now >= deadline) throw AutomationException("not posted after 25 s: the compose box still holds the text" + (fallback?.let { " ($it)" } ?: ""))
            delay(pollMs)
        }
    }

    // ---- helpers (own copies) ------------------------------------------------------------------------------------

    private suspend fun phase(name: String, capMs: Long, block: suspend () -> String) {
        val t0 = System.currentTimeMillis()
        val r = try { hardTimeout(capMs, block) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            timings[name] = System.currentTimeMillis() - t0
            steps += "$name: FAILED after ${timings[name]} ms: ${e.message}"
            throw e
        }
        timings[name] = System.currentTimeMillis() - t0
        if (r == null) {
            val c = runCatching { hardTimeout(10_000) { bridge.cancel() } }.getOrNull()
            steps += "$name: TIMED OUT after ${capMs / 1000} s (cancel: ${if (c?.ok == true) "ok" else "no answer"})"
            throw AutomationException("x_post_beta: $name timed out after ${capMs / 1000} s.\nSteps:\n" + steps.joinToString("\n"))
        }
        steps += "$name: ok ${timings[name]} ms ($r)"
    }

    private suspend fun <T : Any> hardTimeout(ms: Long, block: suspend () -> T): T? {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext.minusKey(kotlinx.coroutines.Job) + SupervisorJob())
        val d = scope.async { block() }
        return try {
            withTimeoutOrNull(ms) { d.await() } ?: run { d.cancel(); null }
        } catch (e: CancellationException) { d.cancel(); throw e }
    }

    /** Copy of NavCheck.sameTarget: same host (www. ignored), target path is a prefix. */
    private fun sameTarget(url: String?, target: String): Boolean {
        if (url.isNullOrBlank()) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val t = runCatching { java.net.URI(target) }.getOrNull() ?: return false
        if ((u.host ?: "").removePrefix("www.").lowercase() != (t.host ?: "").removePrefix("www.").lowercase()) return false
        val tp = (t.path ?: "").trimEnd('/')
        return tp.isEmpty() || (u.path ?: "").trimEnd('/').startsWith(tp)
    }

    private fun BridgeResult.unknownCmd() = !ok && stderr.contains("unknown cmd")

    private suspend fun evalStr(expr: String): String? {
        val r = bridge.eval(expr, 20)
        if (!r.ok) throw AutomationException("eval failed: ${r.errorMessage.take(300)}")
        return r.valueString()?.trim()?.trim('"')
    }

    private suspend fun evalJson(expr: String): JsonObject? =
        evalStr(expr)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private suspend fun tbpClick(css: String): Boolean =
        runCatching { withTimeoutOrNull(TBP_CLICK_MAX_MS) { bridge.click(css) }?.ok == true }.getOrDefault(false)

    private suspend fun key(k: String) {
        runCatching { withTimeoutOrNull(10_000) { bridge.key(k) } }
    }

    companion object {
        const val POLL_MS = 300L
        const val SLEEP_MS = 150L
        const val STRAY_ROUNDS = 3
        const val STRAY_MAX_MS = 25_000L
        const val SUBMIT_VERIFY_MS = 6_000L
        const val TBP_CLICK_MAX_MS = 15_000L
    }
}

/** The beta's page scripts. Every script is one eval and returns a short string. */
object XPostBetaJs {
    /** The editor/button the beta picked, marked so later evals address the same element. */
    const val MARK = "data-farrow-beta"
    const val ED = "[$MARK=\"ed\"]"
    const val BTN = "[$MARK=\"btn\"]"

    private fun q(s: String) = JsonPrimitive(s).toString()

    private const val EDITABLE = "[contenteditable=true],[contenteditable=\"\"],[role=textbox],input,textarea"

    /** JS helpers: visible, normalised text, our editor (marked one, else the dialog's, else the first visible). */
    private fun lib(box: String) = """var vis=function(e){if(!e||!e.isConnected)return false;var r=e.getBoundingClientRect();
      if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'};
      var norm=function(t){return String(t||'').replace(/\u200b/g,'').replace(/\s+/g,' ').trim()};
      var edOf=function(e){return e.matches(${q(EDITABLE)})?e:(e.querySelector('[contenteditable=true],[role=textbox]')||e)};
      var find=function(){var m=document.querySelector(${q(ED)});if(m&&vis(m))return m;
        var all=[].slice.call(document.querySelectorAll(${q(box)})).filter(vis);
        var e=all.filter(function(x){return x.closest('[role="dialog"]')})[0]||all[0];if(!e)return null;
        var ed=edOf(e);document.querySelectorAll(${q(ED)}).forEach(function(x){x.removeAttribute(${q(MARK)})});
        ed.setAttribute(${q(MARK)},'ed');return ed};"""

    /**
     * Shield on + URL + blocking dialogs → {u, n, s, what}. [tries]: how often the top dialog (summary [last]) was
     * tried already, which picks the next action (discard → close → mask → none = Escape from Kotlin).
     */
    fun probe(st: StraySpec, last: String?, tries: Int): String {
        val overlays = q((st.overlays + "[data-testid=\"sheetDialog\"]").distinct().joinToString(", "))
        val markers = q(st.markers.joinToString(", ").ifBlank { "#__none__" })
        val ignore = q(st.ignore.joinToString(", ").ifBlank { "#__none__" })
        val close = q(st.closeButtons.joinToString(", ").ifBlank { "#__none__" })
        val mask = q(st.mask ?: "#__none__")
        return """(()=>{${XPostBetaShield.ON_STMT}
          var vis=function(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'};
          var O=$overlays,I=$ignore;var set=new Set();document.querySelectorAll(O).forEach(function(e){set.add(e)});
          document.querySelectorAll($markers).forEach(function(m){var d=m.closest(O)||m.closest('#layers > *');if(d)set.add(d)});
          var roots=[].slice.call(set).filter(function(e){
            if(e.closest('[${XPostBetaShield.GROK_ATTR}]')||e.closest('[data-testid="DMDrawer"]')||e.matches(I)||!vis(e))return false;
            var hd=e.querySelector('h1,h2,h3,[role="heading"]');if(hd&&/^\s*grok\b/i.test(hd.textContent||''))return false;
            return [].slice.call(e.querySelectorAll('button,[role="button"],a,input,textarea,[contenteditable]')).some(function(b){return !b.closest(I)})});
          roots=roots.filter(function(e){return !roots.some(function(o){return o!==e&&o.contains(e)})});
          var out={u:location.href,n:roots.length};if(!roots.length)return JSON.stringify(out);
          var top=roots[roots.length-1];out.s=(top.innerText||'').replace(/\s+/g,' ').trim().slice(0,80)||
            [].slice.call(top.querySelectorAll('[data-testid]')).map(function(e){return e.getAttribute('data-testid')}).join(' ').slice(0,80);
          var dre=new RegExp(${q(st.discardText)},'i'),sre=new RegExp(${q(st.saveText)},'i');
          var txt=function(b){return (b.innerText||b.textContent||'').trim()};
          var btns=[].slice.call(top.querySelectorAll('button,[role="button"]'));
          var acts=[];var d=btns.filter(function(b){return dre.test(txt(b))&&!sre.test(txt(b))})[0];if(d)acts.push(['discard',d]);
          var c=[].slice.call(top.querySelectorAll($close)).filter(function(b){return !sre.test(txt(b))})[0];if(c)acts.push(['close',c]);
          var m=document.querySelector($mask);if(m)acts.push(['mask',m]);
          var k=(out.s===${q(last ?: "")})?$tries:0;if(!acts.length||k>=acts.length){out.what='none';return JSON.stringify(out)}
          var a=acts[k];try{a[1].click();out.what=a[0]}catch(x){out.what='none'}
          return JSON.stringify(out)})()"""
    }

    /** 'focused' | 'nofocus' | 'missing'. Shield on as well (the compose page is a fresh page load). */
    fun focus(box: String) = """(()=>{${XPostBetaShield.ON_STMT}${lib(box)}var ed=find();if(!ed)return 'missing';
      ed.scrollIntoView({block:'center'});ed.focus();try{var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);
      var s=getSelection();s.removeAllRanges();s.addRange(r)}catch(x){}
      var a=document.activeElement;return (a===ed||ed.contains(a)||(a&&a.contains&&a.contains(ed)&&a.isContentEditable))?'focused':'nofocus'})()"""

    /** 'ok:<chars>' | 'pending:<chars>' | 'failed' | 'missing'. */
    fun insert(box: String, text: String) = """(()=>{${lib(box)}var ed=find();if(!ed)return 'missing';ed.focus();
      try{document.execCommand('selectAll',false,null)}catch(x){}var ok=false;try{ok=document.execCommand('insertText',false,${q(text)})}catch(x){}
      var got=norm(ed.innerText||ed.value);if(got.indexOf(norm(${q(text)}))>=0)return 'ok:'+got.length;
      return (ok?'pending:':'failed:')+got.length})()"""

    /** 'ok:<chars>' | 'no:<chars>' | 'missing'. */
    fun hasText(box: String, text: String) = """(()=>{${lib(box)}var ed=find();if(!ed)return 'missing';
      var got=norm(ed.innerText||ed.value);return (got.indexOf(norm(${q(text)}))>=0?'ok:':'no:')+got.length})()"""

    /** Select all + a synthetic paste (Draft.js handles it) + verify. */
    fun paste(box: String, text: String) = """(()=>{${lib(box)}var ed=find();if(!ed)return 'missing';ed.focus();
      try{document.execCommand('selectAll',false,null)}catch(x){}
      try{var dt=new DataTransfer();dt.setData('text/plain',${q(text)});ed.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}))}catch(x){return 'failed'}
      var got=norm(ed.innerText||ed.value);return (got.indexOf(norm(${q(text)}))>=0?'ok:':'no:')+got.length})()"""

    /** 'clicked' | 'disabled' | 'missing': the Post button in our editor's dialog (else the first visible one). */
    fun clickSubmit(box: String, submit: String) = """(()=>{${lib(box)}var ed=find();var dlg=ed&&ed.closest('[role="dialog"]');
      var all=[].slice.call((dlg||document).querySelectorAll(${q(submit)})).filter(vis);if(!all.length&&dlg)all=[].slice.call(document.querySelectorAll(${q(submit)})).filter(vis);
      var b=all[0];if(!b)return 'missing';document.querySelectorAll(${q(BTN)}).forEach(function(x){x.removeAttribute(${q(MARK)})});
      b.setAttribute(${q(MARK)},'btn');if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'disabled';
      b.scrollIntoView({block:'center'});b.click();return 'clicked'})()"""

    /**
     * 'toast' | 'cleared' (no visible compose box holds the text) | 'sending' (Post button disabled) | 'open'.
     * Once posted, the Grok shield is switched off in the same eval.
     */
    fun posted(box: String, toast: String, text: String) = """(()=>{var vis=function(e){if(!e||!e.isConnected)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0};
      var norm=function(t){return String(t||'').replace(/\u200b/g,'').replace(/\s+/g,' ').trim()};var want=norm(${q(text)}).slice(0,60);
      var off=function(r){try{if(window.__fwGrokOff)window.__fwGrokOff()}catch(x){}return r};
      var t=[].slice.call(document.querySelectorAll(${q(toast)})).some(vis);if(t)return off('toast');
      var held=[].slice.call(document.querySelectorAll(${q(box)})).filter(vis).some(function(e){return norm(e.innerText||e.value).indexOf(want)>=0});
      if(!held)return off('cleared');var b=document.querySelector(${q(BTN)});
      if(b&&(b.disabled||b.getAttribute('aria-disabled')==='true'))return 'sending';return 'open'})()"""
}
