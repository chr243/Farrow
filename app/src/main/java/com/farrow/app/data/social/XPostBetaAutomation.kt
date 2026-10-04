package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * x_post_beta's engine (after v1.0.16): a 1:1 copy of [SocialAutomation] (x_post) — same steps, cleanup, waits, TBP
 * clicks, timings and step log — with exactly one difference: typeIntoEditor skips the human-paced keystrokes and
 * goes straight to the instant insertText path. XPostBetaCopyTest checks that this file is SocialAutomation's class
 * with only that function (and the class name) changed; to update it, re-copy SocialAutomation's class.
 */
class XPostBetaAutomation(val config: SiteConfig, private val bridge: BridgeClient) {

    private fun js(s: String): String = JsonPrimitive(s).toString() // JSON string literal == valid JS string literal

    private suspend fun evalString(expression: String): String? {
        val r = bridge.eval(expression)
        if (!r.ok) throw AutomationException("eval failed: ${r.errorMessage}")
        return r.valueString()
    }

    /** Presence check that also looks inside open shadow roots and same-origin iframes. */
    suspend fun exists(selectorKey: String): Boolean = evalString(DomFinder.exists(config.sel(selectorKey))) == "true"

    /** Polls until present (or gone); a failed/slow eval is retried until the deadline instead of failing the step. */
    suspend fun waitFor(selectorKey: String, timeoutMs: Long, gone: Boolean = false): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val r = try { exists(selectorKey) } catch (e: AutomationException) { lastPollError = e.message; null }
            if (r != null && r != gone) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(POLL_MS)
        }
    }

    @Volatile private var lastPollError: String? = null

    /** Duration of the last [ensureBrowserReady] (TBP `ready`: in-flight wait + console probe eval). */
    @Volatile var lastReadyMs: Long? = null
        private set

    /** Unknown command on an old bridge (< 1.7.0) → fall back to the old path. */
    private fun BridgeResult.unknownCmd() = !ok && stderr.contains("unknown cmd")

    /**
     * Before any site tool: the TBP socket is up, no other command is still running in the daemon (TBP serialises them,
     * so a timed-out goto made the next eval wait 90 s) and the console answers. Waits up to 30 s.
     */
    suspend fun ensureBrowserReady(timeoutS: Int = 30) {
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull((timeoutS + 15) * 1000L) { bridge.ready(timeoutS) }
            ?: throw AutomationException("The internal browser did not become ready within ${timeoutS + 15} s")
        lastReadyMs = System.currentTimeMillis() - t0
        if (r.ok || r.unknownCmd()) return
        val steps = (r.data as? JsonObject)?.get("steps")?.let { runCatching { it.jsonArray.joinToString("; ") { s -> s.jsonPrimitive.content } }.getOrNull() }
        throw AutomationException("The internal browser is not ready: ${r.errorMessage.take(300)}" + (steps?.let { " ($it)" } ?: ""))
    }

    /**
     * Navigation that doesn't wait for 'load' (X keeps connections open): keyboard + history check in the bridge.
     * Not confirmed but already on the target (or on it after all) → fine. A login URL → [SessionExpiredException].
     * Returns a short log line.
     */
    suspend fun navigateTo(url: String, timeoutMs: Long = 30_000): String =
        try { navigateRaw(url, timeoutMs) } finally {
            if (pageShield) withContext(kotlinx.coroutines.NonCancellable) { withTimeoutOrNull(SHIELD_EVAL_MAX_MS) { shield() } }
        }

    private suspend fun navigateRaw(url: String, timeoutMs: Long): String {
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(timeoutMs + 10_000) { bridge.nav(url, (timeoutMs / 1000).toInt()) }
            ?: throw AutomationException("Opening $url timed out after ${(timeoutMs + 10_000) / 1000} s")
        if (r.unknownCmd()) {
            val g = withTimeoutOrNull(timeoutMs + 15_000) { bridge.goto(url) } ?: throw AutomationException("Opening $url timed out")
            if (!g.ok) throw AutomationException("navigation failed: ${g.errorMessage}")
            return "goto (old bridge) ${(System.currentTimeMillis() - t0) / 1000.0} s"
        }
        val d = r.data as? JsonObject
        val now = d?.get("url")?.jsonPrimitive?.contentOrNull
        if (now != null && config.sessionExpiredUrlPatterns.any { now.contains(it) })
            throw SessionExpiredException(config.site, "${config.displayName} redirected to the login page ($now)")
        if (!r.ok && !NavCheck.sameTarget(now, url)) throw AutomationException("navigation failed: ${r.errorMessage.take(300)}")
        return "nav ${if (r.ok) "ok" else "unconfirmed but on target"} in ${d?.get("seconds")?.jsonPrimitive?.contentOrNull ?: "?"} s → $now"
    }

    /** x_status / Check session: cookie store + current URL from the bridge, no JS (falls back on an old bridge). */
    suspend fun quickStatus(): LoginStatus {
        val r = withTimeoutOrNull(20_000) {
            bridge.siteStatus(config.domain, config.loginCookies, config.sessionExpiredUrlPatterns)
        } ?: throw AutomationException("Checking the session timed out after 20 s")
        if (r.unknownCmd()) return loginStatus(navigate = true)
        return SiteStatusParse.parse(r.data as? JsonObject)
    }

    /** Check step 1 (< 1 s): cookies.sqlite copy + history URL + window title, no daemon, no JS. Null = old bridge. */
    suspend fun fastCheck(): FastCheck? {
        val r = withTimeoutOrNull(10_000) {
            bridge.siteStatus(config.domain, config.loginCookies, config.sessionExpiredUrlPatterns)
        } ?: throw AutomationException("Reading the cookie store timed out after 10 s")
        if (r.unknownCmd()) return null
        return FastCheck.parse(r.data as? JsonObject)
    }

    /**
     * Check step 2 (only when the browser is idle): open the home URL with keyboard navigation (no JS, no 'load' wait)
     * and look at where it lands — staying on /home with a non-login title = logged in.
     */
    suspend fun verifyOnHome(timeoutS: Int = 20): Pair<LoginStatus, String> {
        val home = config.url("home")
        val n = withTimeoutOrNull((timeoutS + 10) * 1000L) { bridge.nav(home, timeoutS) }
        delay(1_500) // a logged-out session redirects to the login flow right after loading
        val fc = fastCheck() ?: return LoginStatus(LoginState.UNKNOWN, null, emptyList(), "old bridge") to "old bridge"
        val url = fc.status.url
        val v = FastCheck.verdict(url, fc.title, home, config.sessionExpiredUrlPatterns)
        val why = when (v) {
            LoginState.LOGGED_IN -> "${config.displayName} home stayed open ($url)"
            LoginState.LOGGED_OUT -> "redirected to a login page (${url ?: fc.title})"
            LoginState.UNKNOWN -> if (n == null) "opening $home timed out" else "landed on ${url ?: "?"}"
        }
        // Missing cookies always win over a page that merely looks fine.
        val st = if (fc.status.state == LoginState.LOGGED_OUT) fc.status else LoginStatus(v, url, fc.status.cookies, why)
        return st to why
    }

    /** Reads URL, readable cookies and logged-in/login-wall DOM markers. Navigates to the home URL first if asked. */
    suspend fun loginStatus(navigate: Boolean = true): LoginStatus {
        if (navigate) {
            navigateTo(config.url("home"), 30_000)
            val waitUntil = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < waitUntil && !exists("loggedIn") && !exists("loginForm")) delay(POLL_MS)
        }
        val probe = """(()=>{const q=s=>{try{return !!document.querySelector(s)}catch(e){return false}};
            return JSON.stringify({url:location.href,
              cookies:document.cookie.split(';').map(c=>c.split('=')[0].trim()).filter(Boolean),
              loggedIn:q(${js(config.sel("loggedIn"))}),loginForm:q(${js(config.sel("loginForm"))})})})()""".trimIndent()
        val raw = evalString(probe) ?: return LoginStatus(LoginState.UNKNOWN, null, emptyList(), "no probe result")
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return LoginStatus(LoginState.UNKNOWN, null, emptyList(), "unparseable probe: ${raw.take(120)}")
        val url = o["url"]?.jsonPrimitive?.contentOrNull
        val cookies = (o["cookies"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val loggedInDom = o["loggedIn"]?.jsonPrimitive?.booleanOrNull == true
        val loginDom = o["loginForm"]?.jsonPrimitive?.booleanOrNull == true
        val expiredUrl = url != null && config.sessionExpiredUrlPatterns.any { url.contains(it) }
        val hasCookie = config.readableLoginCookies.isEmpty() || config.readableLoginCookies.any { it in cookies }
        return when {
            loggedInDom -> LoginStatus(LoginState.LOGGED_IN, url, cookies, "logged-in UI present")
            expiredUrl -> LoginStatus(LoginState.LOGGED_OUT, url, cookies, "redirected to a login page")
            loginDom -> LoginStatus(LoginState.LOGGED_OUT, url, cookies, "login form shown")
            !hasCookie -> LoginStatus(LoginState.LOGGED_OUT, url, cookies, "no session cookies")
            else -> LoginStatus(LoginState.UNKNOWN, url, cookies, "no login markers found")
        }
    }

    /** Throws [SessionExpiredException] when the current page is a login wall. */
    suspend fun ensureLoggedIn(navigate: Boolean = false) {
        val st = loginStatus(navigate)
        if (st.state == LoginState.LOGGED_OUT) throw SessionExpiredException(config.site, "${config.displayName} session expired (${st.reason})")
    }

    /** 0-based index of the first selector key present, or -1. One eval per poll. */
    suspend fun firstPresent(keys: List<String>): Int {
        val r = evalString(DomFinder.firstPresent(keys.map { config.sel(it) }))
        return r?.trim()?.toIntOrNull() ?: -1
    }

    suspend fun waitAny(keys: List<String>, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val i = firstPresent(keys)
            if (i >= 0 || System.currentTimeMillis() >= deadline) return i
            delay(POLL_MS)
        }
    }

    /** "top" | "deep" | "no" — see [DomFinder]. */
    private suspend fun mark(expr: String): String = evalString(expr)?.trim()?.trim('"') ?: "no"

    /** Polls [expr] until it finds something or [timeoutMs] passes. */
    private suspend fun pollMark(expr: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val r = mark(expr)
            if (r != "no" || System.currentTimeMillis() >= deadline) return r
            delay(POLL_MS)
        }
    }

    /**
     * Polls [expr] (a finder returning "no" when absent) until it finds something; while waiting, clicks away popups
     * matching [dismissRe] (cookie consent, "Not now") with a JS click. Returns the finder's result or "no".
     */
    private suspend fun pollDismissing(expr: String, dismissRe: String?, timeoutMs: Long, notes: MutableList<String>,
                                       found: String? = null): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var dismissed = 0
        while (true) {
            val r = try { mark(expr) } catch (e: AutomationException) { lastPollError = e.message; "no" }
            val hit = if (found != null) r == found else r != "no"
            if (hit) return r
            if (dismissRe != null && dismissed < 5) {
                val d = try { mark(DomFinder.findText(dismissRe)) } catch (e: AutomationException) { "no" }
                if (d != "no" && runCatching { evalString(DomFinder.clickMarked()) }.getOrNull() == "ok") {
                    dismissed++; notes += "dismissed a popup"; delay(800); continue
                }
            }
            if (System.currentTimeMillis() >= deadline) return "no"
            delay(POLL_MS)
        }
    }

    /** Clicks the marked element: human click via the bridge in the main document, JS click inside shadow/iframes. */
    private suspend fun clickMarked(where: String): Boolean {
        if (where == "top") {
            val r = bridge.click(DomFinder.TARGET_CSS)
            if (r.ok) return true
        }
        return evalString(DomFinder.clickMarked()) == "ok"
    }

    /** Text regex for clickText: [AutomationStep.match], or the literal text, anchored and escaped. */
    private fun textRegex(s: AutomationStep, fill: (String?) -> String): String = s.match ?: ("^" + escapeRegex(fill(s.text)) + "$")

    private fun escapeRegex(t: String) = buildString { t.forEach { c -> if (c in "\\^$.|?*+()[]{}/") append('\\'); append(c) } }

    private fun describe(s: AutomationStep): String = s.label ?: buildString {
        append(s.action)
        when (s.action) {
            "goto" -> append(" ").append(s.url)
            "waitAny" -> append(" ").append(s.selectors.joinToString(" | "))
            "clickText" -> append(" '").append(s.match ?: s.text).append("'")
            "sleep" -> append(" ${s.ms} ms")
            "press" -> append(" ").append(s.key)
            else -> s.selector?.let { append(" ").append(it) }
        }
    }

    private fun selectorDetail(s: AutomationStep): String {
        val keys = (listOfNotNull(s.selector) + s.selectors)
        return if (keys.isEmpty()) "" else " — selector: " + keys.joinToString(" | ") { "$it = ${config.sel(it)}" }
    }

    /**
     * Runs a step script with `{placeholders}` replaced from [vars]. Each step has its own budget
     * ([AutomationStep.timeoutMs], ~15 s by default); a step that exceeds it or fails throws [StepFailedException]
     * naming the step and selector. Elements are awaited by polling `eval`, not fixed sleeps.
     * [onStep] reports progress (1-based index, total, description).
     */
    suspend fun runSteps(
        steps: List<AutomationStep>, vars: Map<String, String>, urlParams: Map<String, String> = emptyMap(),
        onStep: (Int, Int, String) -> Unit = { _, _, _ -> },
    ) {
        fun fill(t: String?): String = (t ?: "").let { var r = it; vars.forEach { (k, v) -> r = r.replace("{$k}", v) }; r }
        val stepLog = mutableListOf<String>()
        hung = false
        val timings = LinkedHashMap<String, Long>().also { lastStepTimings = it }
        for ((i, s) in steps.withIndex()) {
            val name = "Step ${i + 1}/${steps.size} (${describe(s)})"
            onStep(i + 1, steps.size, describe(s))
            val t0 = System.currentTimeMillis()
            stepNote = null; lastPollError = null
            fun fail(why: String): Nothing {
                val ms = System.currentTimeMillis() - t0
                timings[stepKey(i, s)] = ms
                stepLog += "${i + 1}. ${describe(s)}: FAILED after $ms ms" +
                    (lastPollError?.let { " (last eval error: ${it.take(160)})" } ?: "")
                throw StepFailedException(i + 1, steps.size, describe(s),
                    "$name $why${selectorDetail(s)}" + (s.hint?.let { ". $it" } ?: "") + "\nStep log:\n" + stepLog.joinToString("\n"))
            }
            val typeBudget = when (s.action) {
                "type" -> fill(s.text).length * 400L
                // xdotool typing + verify + insertText fallback (+ old-bridge path)
                "typeEditor" -> fill(s.text).length * 400L + FALLBACK_BUDGET_MS
                // TBP click (≤ CLICK_TRY_MS) → wait for idle → JS focus/click → fallback key
                "click" -> FALLBACK_BUDGET_MS
                // sturdy click fallbacks + one navigation per fallback URL
                "clickText" -> (if (s.sturdy) FALLBACK_BUDGET_MS else 0L) + s.fallbackUrls.size * 45_000L
                else -> 0L
            }
            val budget = maxOf(s.timeoutMs, s.ms) + typeBudget + 5_000
            val outcome = try {
                hardTimeout(budget) { runOne(s, ::fill, urlParams) }
            } catch (e: StepFailedException) { throw e } catch (e: SessionExpiredException) { throw e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { fail("failed: ${e.message ?: e.javaClass.simpleName}") }
            when (outcome) {
                null -> { hung = true; fail("timed out after ${budget / 1000} s; ${recoverAfterTimeout()}") }
                is StepOutcome.Failed -> if (!s.optional) fail(outcome.why)
                StepOutcome.Ok, StepOutcome.Skipped -> Unit
            }
            val ms = System.currentTimeMillis() - t0
            timings[stepKey(i, s)] = ms
            stepLog += "${i + 1}. ${describe(s)}: ${if (outcome == StepOutcome.Skipped) "skipped" else "ok"} $ms ms" +
                (stepNote?.let { " ($it)" } ?: "")
        }
        lastStepLog = stepLog.toList()
    }

    /**
     * v1.0.14: [block] with a HARD deadline. It runs in a detached child that is cancelled at [ms]; we stop waiting at
     * [ms] even if that child doesn't finish cancelling (on the phone a hung click step returned only after 225 s of a
     * 125 s budget). Exceptions from [block] propagate; null = timed out.
     */
    private suspend fun <T : Any> hardTimeout(ms: Long, block: suspend () -> T): T? {
        val scope = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext.minusKey(kotlinx.coroutines.Job) + kotlinx.coroutines.SupervisorJob())
        val d = scope.async { block() }
        return try {
            withTimeoutOrNull(ms) { d.await() } ?: run { d.cancel(); null }
        } catch (e: kotlinx.coroutines.CancellationException) { d.cancel(); throw e }
    }

    /**
     * After a step timed out: kill the bridge's hung `tbp` CLI calls (bridge ≥ 1.15.0 `cancel`) and wait until TBP is
     * idle, so the next command (or the next x_post) isn't queued behind the hung one. Returns a log line.
     */
    private suspend fun recoverAfterTimeout(): String {
        val c = runCatching { hardTimeout(10_000) { bridge.cancel() } }.getOrNull()
        val what = when {
            c == null -> "cancel: no answer"
            c.unknownCmd() -> "cancel: old bridge"
            else -> "cancel: killed " + ((c.data as? JsonObject)?.get("killed")?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0) + " hung call(s)"
        }
        val idle = runCatching { hardTimeout(45_000) { waitIdle() } }.getOrNull() ?: "browser still busy after 45 s"
        return "$what; $idle"
    }

    /**
     * True when a step of the last [runSteps] ran into its hard budget (a bridge/TBP call hung): x_post then restarts
     * the browser once and retries (see SocialTools).
     */
    @Volatile var hung = false

    /** `timings_ms` key of a step: "3_click_composeText". */
    private fun stepKey(i: Int, s: AutomationStep) = "${i + 1}_${s.action}" + (s.selector ?: s.url)?.let { "_$it" }.orEmpty()

    /** Per-step durations (ms) of the last [runSteps] (also on failure), in step order. */
    @Volatile var lastStepTimings: Map<String, Long> = emptyMap()
        private set

    @Volatile private var stepNote: String? = null
    /** Per-step log of the last successful [runSteps] (for tool results). */
    @Volatile var lastStepLog: List<String> = emptyList()
        private set

    private sealed interface StepOutcome {
        data object Ok : StepOutcome
        data object Skipped : StepOutcome
        data class Failed(val why: String) : StepOutcome
    }

    private suspend fun runOne(s: AutomationStep, fill: (String?) -> String, urlParams: Map<String, String>): StepOutcome {
        when (s.action) {
            "goto" -> {
                stepNote = navigateTo(config.url(s.url ?: "home", urlParams), maxOf(s.timeoutMs - 5_000, 10_000))
            }
            "waitPosted" -> if (s.text != null && s.selector == null) {
                // Posted = the compose dialog closed, or the text shows in a feed item outside the dialog.
                val box = config.sel(s.selectors.firstOrNull() ?: "composeText")
                val item = config.scrape?.item ?: "[role=\"article\"]"
                val text = fill(s.text).take(80)
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    val st = try { mark(DomFinder.postedState(box, item, text)) } catch (e: AutomationException) { lastPollError = e.message; "open" }
                    if (st == "feed" || st == "closed") { stepNote = if (st == "feed") "text appeared in the feed" else "compose dialog closed"; return StepOutcome.Ok }
                    if (System.currentTimeMillis() >= deadline) return StepOutcome.Failed("the compose dialog is still open after ${s.timeoutMs / 1000} s and the post is not in the feed")
                    delay(POLL_MS)
                }
            } else {
                // Posted = the success toast shows, the compose box closed, or (v1.0.13, with text) no visible compose box
                // still holds the text — X's home page keeps an empty inline composer with the same test id, so "box
                // closed" alone could miss a sent post and wait out the whole timeout. One eval per poll.
                val toast = config.sel(s.selector ?: "postSuccess")
                val box = config.sel(s.selectors.firstOrNull() ?: "composeText")
                val expr = DomFinder.postDone(toast, box, s.text?.let(fill))
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    val st = try { mark(expr) } catch (e: AutomationException) { lastPollError = e.message; "open" }
                    if (st != "open") {
                        stepNote = when (st) { "toast" -> "success toast"; "cleared" -> "compose box emptied"; else -> "compose box closed" }
                        return StepOutcome.Ok
                    }
                    if (System.currentTimeMillis() >= deadline) return StepOutcome.Failed("no success toast and the compose box is still open after ${s.timeoutMs / 1000} s")
                    delay(POLL_MS)
                }
            }
            "waitFor", "waitGone" -> {
                val sel = s.selector ?: return StepOutcome.Skipped
                val ok = if (s.dismiss != null && s.action == "waitFor") {
                    val notes = mutableListOf<String>()
                    (pollDismissing(DomFinder.exists(config.sel(sel)), s.dismiss, s.timeoutMs, notes, found = "true") != "no")
                        .also { if (notes.isNotEmpty()) stepNote = notes.joinToString("; ") }
                } else waitFor(sel, s.timeoutMs, gone = s.action == "waitGone")
                if (!ok) {
                    if (s.optional) return StepOutcome.Skipped
                    if (s.action == "waitFor" && !sel.startsWith("log")) ensureLoggedIn(navigate = false)
                    return StepOutcome.Failed(if (s.action == "waitGone") "element did not go away within ${s.timeoutMs / 1000} s"
                        else "element not found within ${s.timeoutMs / 1000} s")
                }
            }
            "waitAny" -> {
                if (waitAny(s.selectors, s.timeoutMs) < 0)
                    return if (s.optional) StepOutcome.Skipped else StepOutcome.Failed("none of the elements appeared within ${s.timeoutMs / 1000} s")
            }
            "click" -> {
                val sel = s.selector ?: return StepOutcome.Skipped
                if (!exists(sel)) {
                    if (s.optional) return StepOutcome.Skipped
                    if (!waitFor(sel, s.timeoutMs)) {
                        s.fallbackKey?.let { pressKey(it); stepNote = "element not found → key $it"; return StepOutcome.Ok }
                        return StepOutcome.Failed("element not found")
                    }
                }
                return sturdyClick(s, config.sel(sel))
            }
            "clickText" -> {
                // Visible-text / aria-label match (regex, case-insensitive) across shadow roots/iframes, clicking away
                // popups ([AutomationStep.dismiss]) while waiting and trying [AutomationStep.fallbackUrls] when nothing matches.
                val re = textRegex(s, fill)
                val scope = (s.scope ?: s.selector)?.let { config.sel(it) }
                val expr = DomFinder.findText(re, s.exclude, scope)
                val notes = mutableListOf<String>()
                val urls = listOf<String?>(null) + s.fallbackUrls
                var where = "no"
                val perTry = if (s.fallbackUrls.isEmpty()) s.timeoutMs else maxOf(s.timeoutMs / urls.size, 8_000)
                for (u in urls) {
                    if (u != null) notes += "not found → " + navigateTo(config.url(u, urlParams), 30_000)
                    where = pollDismissing(expr, s.dismiss, perTry, notes)
                    if (where != "no") break
                }
                if (where == "no") {
                    if (s.optional) return StepOutcome.Skipped
                    s.fallbackKey?.let { pressKey(it); stepNote = (notes + "not found → key $it").joinToString("; "); return StepOutcome.Ok }
                    return StepOutcome.Failed("no visible button matching /$re/" + (if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"))
                }
                if (s.sturdy) {
                    val target = if (where == "top") DomFinder.TARGET_CSS else null
                    if (target != null) {
                        val r = sturdyClick(s, target)
                        if (notes.isNotEmpty()) stepNote = (notes + listOfNotNull(stepNote)).joinToString("; ")
                        return r
                    }
                }
                if (!clickMarked(where)) {
                    s.fallbackKey?.let { pressKey(it); return StepOutcome.Ok }
                    return StepOutcome.Failed("click on /$re/ failed")
                }
                if (notes.isNotEmpty()) stepNote = notes.joinToString("; ")
            }
            "type" -> {
                val text = fill(s.text)
                if (s.match != null || s.dialogFallback) {
                    if (s.optional && text.isBlank()) return StepOutcome.Skipped
                    val css = s.selector?.let { config.sel(it) }
                    val where = pollMark(DomFinder.findInput(s.match, css, s.dialogFallback), s.timeoutMs)
                    if (where == "no") return if (s.optional) StepOutcome.Skipped
                        else StepOutcome.Failed("no input labelled /${s.match}/" + (css?.let { " or matching $it" } ?: ""))
                    clickMarked(where) // focus it like a person would
                    val typed = where == "top" && bridge.type(DomFinder.TARGET_CSS, text, submit = s.submit).ok
                    if (!typed) {
                        if (evalString(DomFinder.setMarked(text)) != "ok") return StepOutcome.Failed("could not fill the input")
                        if (s.submit) bridge.press("Enter")
                    }
                    return StepOutcome.Ok
                }
                val sel = s.selector ?: return StepOutcome.Skipped
                if (s.optional && (text.isBlank() || !exists(sel))) return StepOutcome.Skipped
                if (!exists(sel) && !waitFor(sel, s.timeoutMs)) return StepOutcome.Failed("input not found")
                val r = bridge.type(config.sel(sel), text, submit = s.submit)
                if (!r.ok) return StepOutcome.Failed("typing failed: ${r.errorMessage}")
            }
            "typeEditor" -> {
                val sel = s.selector ?: return StepOutcome.Skipped
                val text = fill(s.text)
                if (s.optional && text.isBlank()) return StepOutcome.Skipped
                if (!exists(sel) && !waitFor(sel, s.timeoutMs)) return StepOutcome.Failed("editor not found")
                return typeIntoEditor(config.sel(sel), text, s.timeoutMs)
            }
            "press" -> bridge.press(s.key ?: "Enter")
            "sleep" -> delay(s.ms.coerceIn(0, 30_000))
            else -> return StepOutcome.Failed("unknown step action '${s.action}'")
        }
        return StepOutcome.Ok
    }

    /** Upper bound for the TBP click attempt (tests shorten it). */
    internal var clickTryMaxMs = CLICK_TRY_MAX_MS

    /** TBP click attempt length inside a click step; the rest of the step budget is for the fallbacks. */
    private fun clickTryMs(s: AutomationStep) = minOf(s.timeoutMs, clickTryMaxMs)

    /**
     * Click: TBP click (bounded) → on a timeout/failure wait until TBP is idle again (a cancelled HTTP call doesn't stop
     * the daemon's click, which would block the next eval) → one eval that focuses the editable element (X's Draft.js
     * contenteditable) or JS-clicks anything else → [AutomationStep.fallbackKey] (e.g. ctrl+Return to post).
     */
    private suspend fun sturdyClick(s: AutomationStep, css: String): StepOutcome {
        val tryMs = clickTryMs(s)
        val t0 = System.currentTimeMillis()
        val r = try { withTimeoutOrNull(tryMs) { bridge.click(css) } } catch (e: java.io.IOException) {
            BridgeResult(false, -1, "", e.message ?: "bridge error", null, JsonObject(emptyMap()))
        }
        if (r?.ok == true) return StepOutcome.Ok
        val why = r?.let { "TBP click failed (${it.errorMessage.take(120)})" } ?: "TBP click timed out after ${tryMs / 1000} s"
        val idle = waitIdle()
        val js = jsFocusOrClick(css)
        if (js != null) {
            stepNote = "$why after ${(System.currentTimeMillis() - t0) / 1000.0} s; $idle; JS fallback: $js"
            return StepOutcome.Ok
        }
        s.fallbackKey?.let { k ->
            pressKey(k)
            stepNote = "$why; $idle; JS fallback failed → key $k"
            return StepOutcome.Ok
        }
        return StepOutcome.Failed("click failed: $why; $idle; the JS focus/click fallback failed too")
    }

    /** Waits until the TBP daemon has no command in flight (bridge ≥ 1.7.0 `ready`; old bridges: a short pause). */
    private suspend fun waitIdle(timeoutS: Int = 30): String {
        val t0 = System.currentTimeMillis()
        val r = try { withTimeoutOrNull((timeoutS + 10) * 1000L) { bridge.ready(timeoutS) } } catch (e: java.io.IOException) { null }
        return when {
            r == null -> "browser still busy after ${(System.currentTimeMillis() - t0) / 1000} s"
            r.unknownCmd() -> { delay(OLD_BRIDGE_IDLE_MS); "old bridge: paused ${OLD_BRIDGE_IDLE_MS / 1000} s" }
            r.ok -> "browser idle after ${(System.currentTimeMillis() - t0) / 1000.0} s"
            else -> "browser not idle (${r.errorMessage.take(80)})"
        }
    }

    /** "focused" / "clicked" via the bridge's `focus` (≥ 1.8.0) or a direct eval; null when it failed. */
    private suspend fun jsFocusOrClick(css: String): String? {
        val r = try { withTimeoutOrNull(35_000) { bridge.focus(css) } } catch (e: java.io.IOException) { null }
        val v = when {
            r == null -> null
            r.unknownCmd() -> runCatching { withTimeoutOrNull(35_000) { evalString(DomFinder.focusOrClick(css)) } }.getOrNull()
            r.ok -> r.stdout.ifBlank { "focused" }
            else -> null
        }?.trim()?.trim('"')
        return v?.takeIf { it == "focused" || it == "clicked" }
    }

    /** Key combo via the bridge's xdotool `key` (≥ 1.8.0), else `press`. */
    private suspend fun pressKey(keys: String) {
        val r = runCatching { withTimeoutOrNull(20_000) { bridge.key(keys) } }.getOrNull()
        if (r == null || r.unknownCmd() || !r.ok) runCatching { withTimeoutOrNull(20_000) { bridge.press(keys) } }
    }

    private suspend fun editorText(css: String): String? =
        runCatching { withTimeoutOrNull(30_000) { evalString(DomFinder.editorText(css)) } }.getOrNull()

    /**
     * x_post_beta's ONLY difference from x_post: no human-paced letter-by-letter keystrokes (bridge `editor_type`).
     * Straight to the instant insertText path that x_post falls back to, with the same verification afterwards
     * (editor text contains the post text).
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun typeIntoEditor(css: String, text: String, timeoutMs: Long): StepOutcome {
        val notes = mutableListOf("instant typing (no keystrokes)")
        val ins = runCatching { withTimeoutOrNull(30_000) { evalString(DomFinder.insertText(css, text)) } }.getOrNull()
        notes += "insertText: ${ins ?: "failed"}"
        val got = editorText(css)
        if (DomFinder.containsText(got, text)) { stepNote = notes.joinToString("; "); return StepOutcome.Ok }
        return StepOutcome.Failed("the text did not appear in the editor (${notes.joinToString("; ")}; editor has ${got?.length ?: 0} chars)")
    }

    /** URL, a page-text snippet and (if the bridge supports it) a screenshot — collected after a failure. */
    suspend fun diagnostics(): LoginDiagnostics {
        val probe = withTimeoutOrNull(8_000) {
            runCatching { evalString("JSON.stringify({u:location.href,t:(document.body?document.body.innerText:'').slice(0,1500)})") }.getOrNull()
        }
        val o = probe?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val shot = withTimeoutOrNull(35_000) { bridge.screenshot() } ?: Result.failure(Exception("screenshot timed out"))
        return LoginDiagnostics(
            url = o?.get("u")?.jsonPrimitive?.contentOrNull,
            pageText = o?.get("t")?.jsonPrimitive?.contentOrNull,
            screenshotPng = shot.getOrNull(),
            screenshotError = shot.exceptionOrNull()?.message,
        )
    }

    /**
     * While an X tool runs: Grok drawer shield + media filter in the page (re-installed after each navigation, since a
     * page load drops them). [pageShield] is set by x_post; scrapes install it in their own poll eval.
     */
    @Volatile private var pageShield = false

    private suspend fun shield() {
        if (!pageShield) return
        runCatching { evalString("(()=>{" + FastScrape.mediaOnJs(FastScrape.PAGE_MEDIA_TTL_MS) + "\n" + GrokShield.ON_STMT + "return 'on'})()") }
    }

    private suspend fun <T> shielded(block: suspend () -> T): T {
        pageShield = true
        return try { shield(); block() } finally {
            pageShield = false
            // Grok shield off; the media filter is left to lapse (images stay blocked while the page is on screen).
            withContext(kotlinx.coroutines.NonCancellable) { withTimeoutOrNull(SHIELD_EVAL_MAX_MS) { runCatching { evalString(GrokShield.OFF) } } }
        }
    }

    /** Timings of the last scrape / scrapeReplies (tool result `timings_ms` + `steps`). */
    @Volatile var lastScrapeTimings: ScrapeTimings? = null
        private set

    /**
     * The v1.0.5 poll loop shared by all scrapes. One eval per poll ([js]: scrollTop, scroll) that also reports the
     * location; navigation is skipped when the page already is [targetUrl]; polls every [FastScrape.POLL_MS] and
     * returns as soon as [have] reaches [limit]; scrolls only when the count stops growing. The media filter (if the
     * spec asks for it) is released in the last poll or, failing that, by one restore eval. False = no posts in 20 s.
     */
    private suspend fun pollScrape(targetUrl: String, target: FastScrape.Target, navigate: Boolean, limit: Int, maxScrolls: Int,
                                   tm: ScrapeTimings, js: (Boolean, Boolean) -> String, absorb: (FastScrape.Batch) -> Int,
                                   have: () -> Int): Boolean {
        var mediaOn = false
        var loaded = false
        var everOn = false
        var polls = 0; var pollMs = 0L; var scrolls = 0
        try {
            val first = tm.time("locate", { b: FastScrape.Batch? -> "page ${b?.url ?: "?"}" }) { FastScrape.parseBatch(evalString(js(true, false))) }
            mediaOn = first?.mediaOn == true; everOn = mediaOn
            val here = first != null && FastScrape.onTarget(first.url, targetUrl, target)
            if (navigate && !here) {
                tm.time("nav", { n: String -> n }) { navigateTo(targetUrl, 30_000) }
            } else {
                tm.steps += if (navigate) "nav skipped (already on ${first?.url})" else "nav not requested"
                if (first != null && !first.scrolledTop && first.count > 0) { absorb(first); loaded = true }
            }
            var lastGrowth = System.currentTimeMillis()
            var lastScroll: Long? = null
            val firstDeadline = System.currentTimeMillis() + 20_000
            var pendingScroll = false
            var waitedForFirst = 0L
            val loopStart = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                when (FastScrape.decide(have(), loaded, limit, now - lastGrowth, lastScroll?.let { now - it }, scrolls, maxScrolls, firstDeadline - now)) {
                    FastScrape.Next.DONE -> break
                    FastScrape.Next.GIVE_UP -> { tm.add("wait_posts", now - loopStart, "no posts after $polls polls"); return false }
                    FastScrape.Next.SCROLL -> { pendingScroll = true; scrolls++ }
                    FastScrape.Next.POLL -> if (polls > 0 || loaded) delay(FastScrape.POLL_MS)
                }
                val s0 = System.currentTimeMillis()
                val b = try { FastScrape.parseBatch(evalString(js(false, pendingScroll))) } catch (e: AutomationException) { lastPollError = e.message; null }
                polls++; pollMs += System.currentTimeMillis() - s0
                if (pendingScroll) { lastScroll = System.currentTimeMillis(); pendingScroll = false }
                if (b == null) continue
                mediaOn = b.mediaOn; everOn = everOn || mediaOn
                if (b.scrolledTop || b.count == 0) continue
                if (!loaded) { loaded = true; lastGrowth = System.currentTimeMillis(); waitedForFirst = lastGrowth - loopStart }
                if (absorb(b) > 0) lastGrowth = System.currentTimeMillis()
            }
            if (waitedForFirst > 0) tm.ms["wait_first_posts"] = waitedForFirst
            return true
        } finally {
            tm.ms["polls"] = pollMs
            tm.steps += "polls $polls in $pollMs ms (one eval each), scrolls $scrolls"
            if (mediaOn) runCatching { tm.time("release_shield", { n: String? -> "Grok shield off (${n ?: "?"} overlays unhidden)" }) { evalString(GrokShield.OFF) } }
            else if (everOn) tm.steps += "Grok shield released in the last poll"
            if (everOn) tm.steps += "images/video stay blocked on this page (page filter, ${FastScrape.PAGE_MEDIA_TTL_MS / 60_000} min)"
        }
    }

    /** Extracts up to [limit] items with the config's scrape spec (fast poll loop, see [pollScrape]). */
    suspend fun scrape(urlKey: String, params: Map<String, String>, limit: Int, maxScrolls: Int = 8): JsonArray {
        val spec = config.scrape ?: throw AutomationException("no scrape spec for ${config.site}")
        val tm = ScrapeTimings().also { lastScrapeTimings = it }
        lastReadyMs?.let { tm.add("ready", it) }
        val url = config.url(urlKey, params)
        val t = FastScrape.target(url)
        val seen = LinkedHashMap<String, JsonObject>()
        val ok = pollScrape(url, t, navigate = true, limit, maxScrolls, tm,
            js = { top, scroll -> FastScrape.extractJs(spec, limit, t, top, scroll, statusUrl = config.site == SelectorStore.X) },
            absorb = { b -> FastScrape.merge(seen, FastScrape.items(b), spec.dedupeField) },
            have = { seen.size })
        if (!ok) {
            ensureLoggedIn(navigate = false)
            tm.total(); return JsonArray(emptyList())
        }
        tm.total()
        val out = seen.values.take(limit)
        return JsonArray(if (config.site == SelectorStore.X) out.map(FastScrape::withStatusUrl) else out)
    }

    /**
     * Replies under one post (x_scrape kind=replies): only `article` posts inside the conversation column AFTER the
     * focal post. The side nav / account switcher (the logged-in account's own name), the inline compose box and
     * the focal post itself are never returned. The page HTML is parsed by [ThreadReplies] (unit-tested on fixtures).
     */
    suspend fun scrapeReplies(statusUrl: String, limit: Int, maxScrolls: Int = 8, navigate: Boolean = true): RepliesResult {
        val spec = config.replies ?: throw AutomationException("no replies spec for ${config.site}")
        val tm = ScrapeTimings().also { lastScrapeTimings = it }
        if (navigate) lastReadyMs?.let { tm.add("ready", it) }
        val t = FastScrape.target(statusUrl, ThreadReplies.statusId(statusUrl, spec))
        val replies = LinkedHashMap<String, JsonObject>()
        var focal: JsonObject? = null
        var self: String? = null
        val dropped = mutableListOf<String>()
        var parseMs = 0L
        val ok = pollScrape(statusUrl, t, navigate, limit, maxScrolls, tm,
            js = { top, scroll -> FastScrape.grabJs(spec, limit, t, top, scroll) },
            absorb = { b ->
                val p0 = System.currentTimeMillis()
                val page = ThreadReplies.parse(b.obj["h"]?.jsonPrimitive?.contentOrNull.orEmpty(), statusUrl, spec,
                    selfHref = b.obj["self"]?.jsonPrimitive?.contentOrNull)
                parseMs += System.currentTimeMillis() - p0
                focal = focal ?: page.focal
                self = self ?: page.selfHandle
                dropped += page.dropped
                val before = replies.size
                page.replies.forEach { r -> r["url"]?.jsonPrimitive?.contentOrNull?.let { replies.putIfAbsent(it, r) } }
                replies.size - before
            },
            have = { replies.size })
        tm.ms["parse"] = parseMs
        if (!ok) {
            ensureLoggedIn(navigate = false)
            tm.total()
            throw AutomationException("the post did not load (no ${spec.item} on the page)")
        }
        tm.total()
        return RepliesResult(focal, replies.values.take(limit), dropped.distinct(), self)
    }

    /** Page text limited to [TextScope] (X: the main column without nav, sidebar, account banner or composer). */
    suspend fun scopedPageText(scope: TextScope): String? = evalString(scopedTextJs(scope))

    /**
     * Closes leftover overlays (unsent posts/drafts view, schedule picker, an old composer, a "Save post?" sheet) that
     * aren't [ours]. Per overlay, escalating: Discard (never Save) → its close button → Escape → the mask. Re-checks
     * after each action. Returns the overlays still open (empty = clean).
     */
    suspend fun cleanStray(st: StraySpec, log: (String) -> Unit = {}, ours: String? = null): List<Stray> {
        val tried = HashMap<String, Int>()
        var left: List<Stray> = emptyList()
        repeat(st.maxRounds.coerceIn(1, 12)) {
            val snap = runCatching { evalString(StrayDialogs.snapshotJs(st))?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
                ?: return left.also { log("stray check: page did not answer") }
            left = StrayDialogs.analyze(snap["h"]?.jsonPrimitive?.contentOrNull.orEmpty(), st, ours)
            val top = left.lastOrNull() ?: return emptyList()
            val n = (tried.getOrDefault(top.signature, 0)).also { tried[top.signature] = it + 1 }
            val actions = buildList {
                top.discard?.let { add("discard" to it) }
                top.close?.let { add("close" to it) }
                add("escape" to "")
                if (snap["mask"]?.jsonPrimitive?.booleanOrNull == true && st.mask != null) add("mask" to st.mask)
            }
            val (what, css) = actions[n % actions.size]
            when (what) {
                "escape" -> pressKey("Escape")
                else -> {
                    val r = try { withTimeoutOrNull(10_000) { bridge.click(css) } } catch (e: java.io.IOException) { null }
                    if (r?.ok != true) runCatching { evalString("(()=>{const e=document.querySelector(${js(css)});if(e){e.click();return 'clicked'}return 'none'})()") }
                }
            }
            log("stray dialog '${top.summary}'${if (top.isSaveSheet) " (Save/Discard sheet)" else ""}: $what")
            // v1.0.13: was a fixed 700 ms; now polls until that overlay is gone or changed (≤ 1.5 s, closing animations).
            val until = System.currentTimeMillis() + STRAY_SETTLE_MAX_MS
            while (System.currentTimeMillis() < until) {
                delay(POLL_MS)
                val s2 = runCatching { evalString(StrayDialogs.snapshotJs(st))?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull() ?: break
                if (StrayDialogs.analyze(s2["h"]?.jsonPrimitive?.contentOrNull.orEmpty(), st, ours).lastOrNull()?.signature != top.signature) break
            }
        }
        if (left.isNotEmpty()) log("still open after cleanup: ${left.joinToString { it.summary }}")
        return left
    }

    /**
     * x_post = v1.0.0's flow (Farrow v1.0.11 revert): x.json's postSteps unchanged since v1.0.0 — goto compose, waitFor
     * composeText, click composeText, typeEditor composeText (bridge `editor_type`, v1.0.0 semantics), sleep 800 ms,
     * click composeSubmit (ctrl+Return fallback), waitPosted. Kept around it, without touching focus/typing/submit: the
     * leftover-dialog cleanup BEFORE the flow starts, and the Grok shield + image filter installed after navigation.
     */
    suspend fun post(text: String) {
        val t0 = System.currentTimeMillis()
        val pre = LinkedHashMap<String, Long>()
        lastPostTimings = pre
        config.stray?.let { st ->
            // A stuck overlay → hard navigation home, then one more cleanup round; all before the post flow starts.
            if (cleanStray(st).isNotEmpty()) {
                // v1.0.14: bounded; if the page doesn't answer, wait until TBP is idle so a late, queued replace can't
                // send the browser home after the compose navigation.
                val r = runCatching { hardTimeout(10_000) { bridge.eval("location.replace(${js(config.url("home"))})") } }.getOrNull()
                if (r == null) runCatching { hardTimeout(45_000) { waitIdle() } }
                // v1.0.13: was a fixed 2 s; now polls until the home page is loading/loaded (≤ 3 s).
                val until = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < until) {
                    delay(POLL_MS)
                    val href = runCatching { evalString("location.href+'|'+document.readyState") }.getOrNull().orEmpty()
                    if (NavCheck.sameTarget(href.substringBefore('|'), config.url("home")) && !href.endsWith("|loading")) break
                }
                cleanStray(st)
            }
        }
        pre["stray_cleanup"] = System.currentTimeMillis() - t0
        try {
            shielded { runSteps(config.postSteps, mapOf("text" to text)) }
        } finally {
            lastPostTimings = LinkedHashMap(pre).apply { putAll(lastStepTimings); put("total", System.currentTimeMillis() - t0) }
        }
    }

    /**
     * v1.0.14: true when a failed [post] looks like a wedged browser and nothing can have been posted or typed yet:
     * the failing step comes before the first typing step (so before the Post click) and it either hit the hard step
     * timeout ([hung]) or was the navigation step. x_post then restarts the browser once and retries.
     */
    fun wedgedBeforeTyping(e: StepFailedException): Boolean {
        val firstType = config.postSteps.indexOfFirst { it.action == "typeEditor" || it.action == "type" || it.action == "press" }
            .let { if (it < 0) config.postSteps.size else it }
        if (e.index < 1 || e.index > firstType) return false
        return hung || config.postSteps.getOrNull(e.index - 1)?.action == "goto"
    }

    /** x_post `timings_ms`: stray_cleanup, one entry per step (see [lastStepTimings]) and total. */
    @Volatile var lastPostTimings: Map<String, Long> = emptyMap()
        private set

    /**
     * Credential login through the site's login step script; credentials are never stored.
     * On failure throws [LoginFailedException] carrying URL, page text and a screenshot.
     */
    suspend fun loginWithCredentials(
        username: String, password: String, code: String = "", challenge: String = "",
        onStep: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): LoginStatus {
        try {
            runSteps(config.loginSteps, mapOf("username" to username, "password" to password, "code" to code,
                "challenge" to challenge.ifBlank { username }), onStep = onStep)
        } catch (e: StepFailedException) {
            onStep(e.index, e.total, "collecting diagnostics…")
            throw LoginFailedException(e.message ?: "login failed", diagnostics())
        }
        val st = loginStatus(navigate = false)
        if (st.state == LoginState.LOGGED_IN) bridge.saveCookies(config.sessionName)
        return st
    }

    /**
     * Cookie login: set the given cookies on the site's domain, load the home page, verify and save the session.
     * [cookies] usually comes from [CookieParser.parse].
     */
    suspend fun loginWithCookies(cookies: Map<String, String>, onStep: (Int, Int, String) -> Unit = { _, _, _ -> }): LoginStatus {
        val list = cookies.filterValues { it.isNotBlank() }
        val total = list.size + 2
        onStep(1, total, "open ${config.url("home")}")
        val nav = withTimeoutOrNull(45_000) { bridge.goto(config.url("home")) } ?: throw AutomationException("Opening ${config.url("home")} timed out after 45 s")
        if (!nav.ok) throw AutomationException("navigation failed: ${nav.errorMessage}")
        list.entries.forEachIndexed { i, (k, v) ->
            onStep(i + 2, total, "set cookie $k")
            val r = withTimeoutOrNull(15_000) { bridge.setCookie(k, v.trim(), config.domain) } ?: throw AutomationException("Setting cookie $k timed out")
            if (!r.ok) throw AutomationException("could not set cookie $k: ${r.errorMessage}")
        }
        onStep(total, total, "verify by loading ${config.url("home")}")
        val st = withTimeoutOrNull(60_000) { loginStatus(navigate = true) } ?: throw AutomationException("Verifying the session timed out")
        if (st.state == LoginState.LOGGED_IN) bridge.saveCookies(config.sessionName)
        else throw LoginFailedException("Cookies were set but ${config.displayName} still shows: ${st.reason}. " +
            "Make sure auth_token and ct0 come from a browser that is currently logged in.", diagnostics())
        return st
    }

    /**
     * WebView login import: open the home page (cookie context), import ALL captured cookies with their flags
     * (`cookies --load`, keeps httpOnly), fall back to per-cookie cookie_set for an old bridge, then verify + save.
     */
    /** Cookie dump after the last [importBrowserCookies] (which cookies Firefox has, domain/path/expiry), for Copy. */
    @Volatile var lastCookieReport: String? = null
        private set

    /**
     * TBP has no privileged cookie API (its cookie load is page JS = document.cookie: no HttpOnly, current origin only),
     * so the bridge (≥ 1.5.0) stops the daemon, writes the cookies straight into Firefox's cookie store
     * (cookies.sqlite / moz_cookies: host .x.com, path /, Secure, HttpOnly for auth_token, SameSite, expiry +1 y),
     * restarts it, opens [home], dismisses the cookie banner and reads the store back (report + final URL).
     */
    suspend fun importBrowserCookies(list: List<CapturedCookie>, origin: String? = null,
                                     onStep: (Int, Int, String) -> Unit = { _, _, _ -> }, onJob: (String) -> Unit = {}): LoginStatus {
        lastCookieReport = null
        val home = config.url("home")
        onStep(1, 2, "write ${list.size} cookies into Firefox's cookie store (restarts the internal browser)")
        // One atomic bridge job (write + restart): stopping to wait here never leaves the browser stopped.
        val r = withTimeoutOrNull(230_000) { bridge.importCookiesAtomic(config.sessionName, WebLoginCookies.toTbpJson(list), origin, home, onJob) }
            ?: throw AutomationException("Cookie import timed out after 230 s (the bridge still finishes it and restarts the browser)")
        return finishImport(r, list.size, onStep)
    }

    /** Waits again for an import job started before the app was closed/killed, then verifies it like a fresh import. */
    suspend fun resumeImport(jobId: String, count: Int, onStep: (Int, Int, String) -> Unit = { _, _, _ -> }): LoginStatus {
        lastCookieReport = null
        onStep(1, 2, "waiting for the cookie import that was already running in the internal browser")
        val r = withTimeoutOrNull(230_000) { bridge.awaitJob(jobId) }
            ?: throw AutomationException("Cookie import still not finished after 230 s")
        return finishImport(r, count, onStep)
    }

    private suspend fun finishImport(r: BridgeResult, count: Int, onStep: (Int, Int, String) -> Unit): LoginStatus {
        val data = r.raw["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val report = data?.get("report")?.jsonPrimitive?.contentOrNull
        lastCookieReport = listOfNotNull(report?.takeIf { it.isNotBlank() }, r.stderr.takeIf { it.isNotBlank() },
            data?.get("notes")?.takeIf { report.isNullOrBlank() }?.let { n -> runCatching { n.jsonArray.joinToString("\n") { "• " + it.jsonPrimitive.content } }.getOrNull() })
            .joinToString("\n").ifBlank { null }
        val out = ImportOutcome.parse(data)
        if (out.daemonDown) {
            throw BrowserDownException("The internal browser (TBP daemon) did not come back after the cookie import" +
                (out.error?.let { ": $it" } ?: "") + ". Tap Start browser.", out.daemonLog)
        }
        if (report == null && !r.ok) {
            throw AutomationException("Cookie import failed: ${r.errorMessage.take(300)}" +
                if (r.errorMessage.contains("unknown cmd")) " — update the bridge (Set up everything)" else "")
        }
        onStep(2, 2, "verify: cookies.sqlite + page opened")
        val st = out.status(config.displayName)
        if (st.state != LoginState.LOGGED_IN) {
            val shot = withTimeoutOrNull(35_000) { bridge.screenshot() } ?: Result.failure(Exception("screenshot timed out"))
            throw LoginFailedException("Imported $count cookies but ${config.displayName} still shows: ${st.reason}." +
                (r.takeIf { !it.ok }?.let { " (cookie import: ${it.errorMessage.take(200)})" } ?: ""),
                LoginDiagnostics(out.url, null, shot.getOrNull(), shot.exceptionOrNull()?.message))
        }
        bridge.saveCookies(config.sessionName)
        return st
    }

    /** Best effort: the logged-in @handle from the page (X profile link). */
    suspend fun currentHandle(): String? {
        val expr = """(()=>{const a=document.querySelector('a[data-testid="AppTabBar_Profile_Link"]');const h=a&&a.getAttribute('href');return h?h.replace(/^\//,''):''})()"""
        return evalString(expr)?.trim()?.trim('"')?.takeIf { it.isNotBlank() && !it.contains('/') }
    }

    /** Restores the saved cookie session (if any) and reports the resulting login state. */
    suspend fun restoreSession(): LoginStatus {
        runCatching { withTimeoutOrNull(60_000) { bridge.loadCookies(config.sessionName) } }
        return quickStatus()
    }

    companion object {
        /** innerText of the scope minus the innerText of every excluded subtree inside it. */
        fun scopedTextJs(scope: TextScope): String {
            val sel = JsonPrimitive(scope.scope).toString()
            val ex = JsonPrimitive(scope.exclude.joinToString(", ")).toString()
            return """(()=>{const r=document.querySelector($sel);if(!r)return null;let t=r.innerText||'';const x=$ex;
                if(x)r.querySelectorAll(x).forEach(e=>{const s=(e.innerText||'').trim();if(s)t=t.split(s).join('')});
                return t.replace(/\n{3,}/g,'\n\n').trim()})()""".trimIndent()
        }
        /** v1.0.13: was 600 ms. Each poll is one eval (the eval itself takes most of the time on a phone). */
        private const val POLL_MS = 200L
        /**
         * v1.0.14: cap of the TBP click attempt before the JS focus/click fallback (was 30 s). Bridge 1.15.0 no longer
         * uses TBP's slow `--human` click, so a normal click takes ~1 eval; this only bounds a hung one.
         */
        const val CLICK_TRY_MAX_MS = 10_000L
        private const val STRAY_SETTLE_MAX_MS = 1_500L
        /** v1.0.14: the non-cancellable Grok-shield evals after navigation / at the end of x_post never block longer. */
        private const val SHIELD_EVAL_MAX_MS = 10_000L
        /** Extra step budget for click / typeEditor fallbacks (idle wait + JS focus + insertText). */
        private const val FALLBACK_BUDGET_MS = 75_000L
        private const val OLD_BRIDGE_IDLE_MS = 3_000L
        const val TARGET_CSS = DomFinder.TARGET_CSS
    }
}
