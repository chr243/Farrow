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
    internal var probeMaxMs = PROBE_MAX_MS

    private val timings = LinkedHashMap<String, Long>()
    private val steps = mutableListOf<String>()

    suspend fun post(text: String) {
        val t0 = System.currentTimeMillis()
        timings.clear(); steps.clear(); failedBeforeTyping = true
        lastTimings = timings; lastSteps = steps
        val box = config.sel("composeText")
        val submit = config.sel("composeSubmit")
        var shieldOn = false
        var composer = false
        try {
            phase("ready", 45_000) { ready() }
            phase("stray_check", STRAY_MAX_MS) { shieldOn = true; strayCheck() }
            composer = true
            phase("goto_compose", 50_000) { nav(config.url("compose")) }
            phase("focus_composeText", 45_000) { focus(box) }
            failedBeforeTyping = false
            phase("insert_text", 90_000) { insert(box, submit, text) }
            phase("sleep", 5_000) { delay(SLEEP_MS); "${SLEEP_MS} ms" }
            phase("click_composeSubmit", 20_000) { clickSubmit(box, submit) }
            phase("waitPosted", 30_000) { waitPosted(box, submit, text).also { shieldOn = false } }
        } catch (e: Throwable) {
            // Never leave a broken composer behind for the next x_post / x_post_beta (see [cleanupAfterFailure]).
            if (composer) withContext(NonCancellable) {
                val c0 = System.currentTimeMillis()
                val r = withTimeoutOrNull(CLEANUP_MAX_MS) { runCatching { cleanupAfterFailure(box) }.getOrElse { "failed: ${it.message}" } }
                timings["cleanup"] = System.currentTimeMillis() - c0
                steps += "cleanup: ${r ?: "stopped after ${CLEANUP_MAX_MS / 1000} s"}"
                shieldOn = false
            }
            throw e
        } finally {
            if (shieldOn) withContext(NonCancellable) { withTimeoutOrNull(10_000) { runCatching { bridge.eval(XPostBetaShield.GROK_OFF, 10) } } }
            timings["total"] = System.currentTimeMillis() - t0
            lastTimings = LinkedHashMap(timings); lastSteps = steps.toList()
        }
    }

    // ---- phases ---------------------------------------------------------------------------------------------------

    /**
     * After a failure once the compose page was opened: stop anything still running in the bridge, empty and close
     * our composer, discard a "Save post?" sheet (never Save), switch the shield off and go home with a full keyboard
     * navigation. That way the next run (x_post or the beta) starts from a fresh page with no draft. Bounded; never throws.
     */
    private suspend fun cleanupAfterFailure(box: String): String {
        val notes = mutableListOf<String>()
        val c = runCatching { withTimeoutOrNull(10_000) { bridge.cancel() } }.getOrNull()
        val killed = (c?.data as? JsonObject)?.get("killed")?.let { runCatching { it.jsonArray.size }.getOrNull() }
        val idle = runCatching { withTimeoutOrNull(25_000) { bridge.ready(20) } }.getOrNull()
        notes += "cancel ${if (c?.ok == true) "killed ${killed ?: 0}" else "n/a"}, browser ${if (idle?.ok == true) "idle" else "not idle"}"
        val st = config.stray ?: StraySpec()
        val v = runCatching { withTimeoutOrNull(15_000) { evalStr(XPostBetaJs.closeComposer(box, st)) } }.getOrNull()
        notes += "composer: ${v ?: "no answer"}"
        // A "Save post?" sheet → Discard (the probe's own rule: Discard first, never Save).
        var last: String? = null
        for (k in 0 until 2) {
            delay(pollMs)
            val o = runCatching { withTimeoutOrNull(12_000) { evalJson(XPostBetaJs.probe(st, last, k)) } }.getOrNull() ?: break
            if ((o["n"]?.jsonPrimitive?.intOrNull ?: 0) == 0) break
            last = o.str("s"); notes += "dialog '${last.orEmpty().take(40)}' → ${o.str("what") ?: "none"}"
            if (o.str("what") == "none") key("Escape")
        }
        runCatching { withTimeoutOrNull(10_000) { bridge.eval(XPostBetaShield.GROK_OFF, 10) } }
        val home = runCatching { withTimeoutOrNull(45_000) { nav(config.url("home")) } }.getOrNull()
        notes += "home: ${home ?: "not confirmed"}"
        return notes.joinToString("; ")
    }

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
            // v1.0.15 phone run: the first probe after a browser start (about:blank) hung 25 s and cost a restart.
            // A probe that doesn't answer within PROBE_MAX_MS is skipped: the compose navigation reloads the page anyway.
            val o = withTimeoutOrNull(probeMaxMs) { runCatching { evalJson(XPostBetaJs.probe(st, lastS, k)) }.getOrNull() }
            if (o == null) {
                val c = runCatching { withTimeoutOrNull(10_000) { bridge.cancel() } }.getOrNull()
                val idle = runCatching { withTimeoutOrNull(25_000) { bridge.ready(20) } }.getOrNull()
                return (notes + "probe did not answer in ${probeMaxMs / 1000} s → skipped (cancel ${if (c?.ok == true) "ok" else "n/a"}, " +
                    "browser ${if (idle?.ok == true) "idle" else "still busy"})").joinToString("; ")
            }
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

    /** Editor/Post-button state from one of the beta's evals: {len, match, enabled, btn}. */
    private data class EdState(val len: Int, val match: Boolean, val enabled: Boolean, val btn: Boolean, val raw: String?) {
        /** X registered the text: it is in the editor AND the Post button is enabled (Draft.js state, not just the DOM). */
        val registered get() = match && enabled
        override fun toString() = "editor $len chars, text ${if (match) "matches" else "missing"}, Post button " +
            (if (!btn) "not found" else if (enabled) "enabled" else "disabled")
    }

    private suspend fun edState(expr: String): EdState {
        val raw = evalStr(expr)
        val o = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return EdState(0, false, false, false, raw)
        return EdState(o["len"]?.jsonPrimitive?.intOrNull ?: 0, o["match"]?.jsonPrimitive?.booleanOrNull == true,
            o["enabled"]?.jsonPrimitive?.booleanOrNull == true, o["btn"]?.jsonPrimitive?.booleanOrNull == true, raw)
    }

    /**
     * Text in, and REGISTERED by X: the editor holds the text and the Post button is enabled. (v1.0.15 phone run:
     * insertText reported ok from the DOM, yet the Post button stayed disabled, so X's Draft.js state probably never
     * got the text.) Order: insertText (1 eval) → one re-check → real window focus + paste event → keystrokes (bridge
     * `editor_type`: xdotool typing into the activated main window, then its own insertText) after clearing the box.
     * Every attempt logs the editor length and the button state.
     */
    private suspend fun insert(box: String, submit: String, text: String): String {
        val notes = mutableListOf<String>()
        // Give the page window the real OS focus first (each eval runs with the DevTools console window active, so
        // the JS focus alone may never fire the focus event Draft.js needs): bridge `key` = windowactivate + End.
        key("End")
        var st = edState(XPostBetaJs.insert(box, submit, text))
        notes += "insertText: $st"
        if (st.registered) return notes.joinToString("; ")
        if (st.raw == "\"missing\"" || st.raw == "missing") throw AutomationException("the compose box disappeared before typing")
        delay(pollMs * 2)   // Draft.js may enable the button a moment later
        st = edState(XPostBetaJs.state(box, submit, text))
        notes += "re-check: $st"
        if (st.registered) return notes.joinToString("; ")
        if (st.match) notes += "post button disabled, text not registered"
        // Paste with the page window really focused (the eval console had the OS focus during insertText).
        key("End")
        st = edState(XPostBetaJs.paste(box, submit, text))
        notes += "paste: $st"
        if (st.registered) return notes.joinToString("; ")
        delay(pollMs * 2)
        st = edState(XPostBetaJs.state(box, submit, text))
        if (st.registered) return (notes + "re-check: $st").joinToString("; ")
        // Keystrokes as the last resort: clear the box, then the bridge's editor_type (as x_post types).
        st = edState(XPostBetaJs.clear(box, submit, text))
        notes += "cleared: $st"
        val r = runCatching { withTimeoutOrNull(text.length * 400L + 30_000) { bridge.editorType(XPostBetaJs.ED, text) } }.getOrNull()
        notes += "keystrokes (editor_type): " + when { r == null -> "no answer"; r.ok -> "ok"; else -> r.errorMessage.take(120) }
        st = edState(XPostBetaJs.state(box, submit, text))
        notes += "after keystrokes: $st"
        if (st.registered) return notes.joinToString("; ")
        throw AutomationException("post button disabled, text not registered by X (${notes.joinToString("; ")}). Nothing was posted.")
    }

    /**
     * One eval checks the Post button next to our editor and, only if it is enabled, schedules a JS click with
     * setTimeout(0) and returns at once. The eval never waits for X's click handler, which may navigate or re-render.
     * A disabled button gets one re-check, then fails ("post button disabled, text not registered") without clicking:
     * nothing is posted. A missing button → TBP click on the submit selector (bounded).
     */
    private suspend fun clickSubmit(box: String, submit: String): String {
        var v = evalStr(XPostBetaJs.clickSubmit(box, submit)).orEmpty()
        if (v.startsWith("disabled")) {
            delay(pollMs * 2)
            v = evalStr(XPostBetaJs.clickSubmit(box, submit)).orEmpty()
        }
        return when {
            v.startsWith("clicked") -> "JS click scheduled (${v.substringAfter(':', "")})"
            v.startsWith("disabled") -> throw AutomationException("post button disabled, text not registered (${v.substringAfter(':', "")}). Not clicked; nothing was posted.")
            else -> if (tbpClick(submit)) "Post button not found by JS ($v) → TBP click" else
                throw AutomationException("Post button not found ($v) and the TBP click failed; nothing was clicked")
        }
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
        const val PROBE_MAX_MS = 12_000L
        const val CLEANUP_MAX_MS = 90_000L
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

    private fun closeCss(st: StraySpec) = st.closeButtons.joinToString(", ").ifBlank { "[aria-label=\"Close\"]" }

    /** Empties our editor (select all + delete) and schedules a click on the compose dialog's close button → 'emptied:<chars>[,close]'. */
    fun closeComposer(box: String, st: StraySpec) = """(()=>{${lib(box)}var ed=find();var n=-1;
      if(ed){try{ed.focus();document.execCommand('selectAll',false,null);document.execCommand('delete',false,null)}catch(x){}n=norm(ed.innerText||ed.value).length}
      var dlg=ed?ed.closest('[role="dialog"]'):document.querySelector('[role="dialog"]');
      var c=dlg?dlg.querySelector(${q(closeCss(st))}):null;
      if(c)setTimeout(function(){try{c.click()}catch(x){}},0);return (ed?'emptied:'+n:'no editor')+(c?',close':'')})()"""

    /** Editor + Post-button state as JSON {len, match, enabled, btn} (the button in our editor's dialog, else the first visible). */
    private fun stateJs(submit: String, text: String) = """var btnOf=function(ed){var dlg=ed&&ed.closest('[role="dialog"]');
        var all=[].slice.call((dlg||document).querySelectorAll(${q(submit)})).filter(vis);
        if(!all.length&&dlg)all=[].slice.call(document.querySelectorAll(${q(submit)})).filter(vis);return all[0]||null};
      var state=function(ed){var got=norm(ed?(ed.innerText||ed.value):'');var b=btnOf(ed);
        return JSON.stringify({len:got.length,match:got.indexOf(norm(${q(text)}))>=0,btn:!!b,
          enabled:!!b&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'})};"""

    /** focus + select all + insertText → state JSON (or 'missing'). */
    fun insert(box: String, submit: String, text: String) = """(()=>{${lib(box)}${stateJs(submit, text)}var ed=find();if(!ed)return 'missing';ed.focus();
      try{document.execCommand('selectAll',false,null)}catch(x){}try{document.execCommand('insertText',false,${q(text)})}catch(x){}
      return state(ed)})()"""

    /** State JSON only. */
    fun state(box: String, submit: String, text: String) = """(()=>{${lib(box)}${stateJs(submit, text)}return state(find())})()"""

    /** Select all + a synthetic paste (Draft.js handles onPaste) → state JSON. */
    fun paste(box: String, submit: String, text: String) = """(()=>{${lib(box)}${stateJs(submit, text)}var ed=find();if(!ed)return 'missing';ed.focus();
      try{document.execCommand('selectAll',false,null)}catch(x){}
      try{var dt=new DataTransfer();dt.setData('text/plain',${q(text)});ed.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}))}catch(x){}
      return state(ed)})()"""

    /** Select all + delete (before typing keystrokes, so nothing is doubled) → state JSON. */
    fun clear(box: String, submit: String, text: String) = """(()=>{${lib(box)}${stateJs(submit, text)}var ed=find();if(!ed)return 'missing';ed.focus();
      try{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null)}catch(x){}return state(ed)})()"""

    /**
     * 'clicked:<state>' (click scheduled with setTimeout(0), returns at once) | 'disabled:<state>' (not clicked) | 'missing'.
     */
    fun clickSubmit(box: String, submit: String) = """(()=>{${lib(box)}${stateJs(submit, "")}var ed=find();var b=btnOf(ed);if(!b)return 'missing';
      document.querySelectorAll(${q(BTN)}).forEach(function(x){x.removeAttribute(${q(MARK)})});b.setAttribute(${q(MARK)},'btn');
      var len=norm(ed?(ed.innerText||ed.value):'').length;
      if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'disabled:editor '+len+' chars, Post button disabled';
      setTimeout(function(){try{b.click()}catch(x){}},0);return 'clicked:editor '+len+' chars, Post button enabled'})()"""

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
