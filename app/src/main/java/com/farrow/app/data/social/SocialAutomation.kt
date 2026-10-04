package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

enum class LoginState { LOGGED_IN, LOGGED_OUT, UNKNOWN }

data class LoginStatus(val state: LoginState, val url: String?, val cookies: List<String>, val reason: String)

/** Thrown when the site shows a login wall; tools turn it into a task pause (PauseReason.SESSION_EXPIRED). */
class SessionExpiredException(val site: String, message: String) : Exception(message)

class AutomationException(message: String) : Exception(message)

class StepFailedException(val index: Int, val total: Int, val step: String, message: String) : Exception(message)

data class LoginDiagnostics(val url: String?, val pageText: String?, val screenshotPng: ByteArray?, val screenshotError: String?)

class LoginFailedException(message: String, val diagnostics: LoginDiagnostics) : Exception(message)

/** Same rule as the bridge's url_matches: same host (www. ignored), target path is a prefix. */
object NavCheck {
    fun sameTarget(url: String?, target: String): Boolean {
        if (url.isNullOrBlank()) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val t = runCatching { java.net.URI(target) }.getOrNull() ?: return false
        if ((u.host ?: "").removePrefix("www.").lowercase() != (t.host ?: "").removePrefix("www.").lowercase()) return false
        val tp = (t.path ?: "").trimEnd('/')
        return tp.isEmpty() || (u.path ?: "").trimEnd('/').startsWith(tp)
    }
}

/** `site_status` reply → [LoginStatus]. */
object SiteStatusParse {
    fun parse(d: JsonObject?): LoginStatus {
        d ?: return LoginStatus(LoginState.UNKNOWN, null, emptyList(), "no reply from the bridge")
        val state = runCatching { LoginState.valueOf(d["state"]!!.jsonPrimitive.content) }.getOrDefault(LoginState.UNKNOWN)
        val cookies = (d["cookies"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return LoginStatus(state, d["url"]?.jsonPrimitive?.contentOrNull, cookies, d["reason"]?.jsonPrimitive?.contentOrNull ?: state.name)
    }
}

/** Fast login Check (no ready-wait, no eval): cookie-store state + whether the TBP daemon is busy (null = old bridge). */
data class FastCheck(val status: LoginStatus, val busy: List<String>?, val bridgeMs: Long?, val title: String?) {
    companion object {
        fun parse(d: JsonObject?): FastCheck = FastCheck(
            SiteStatusParse.parse(d),
            d?.get("busy")?.let { runCatching { it.jsonArray.map { e -> e.jsonPrimitive.content } }.getOrNull() },
            d?.get("ms")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            d?.get("title")?.jsonPrimitive?.contentOrNull,
        )

        private val LOGIN_TITLE = Regex("""(?i)\b(log ?in|sign ?in|sign up|connexion|se connecter|inscri)""")

        /** Logged-in signal after opening the home URL: still on it (history URL) and the title isn't a login page. */
        fun verdict(url: String?, title: String?, homeUrl: String, loggedOutPatterns: List<String>): LoginState = when {
            url != null && loggedOutPatterns.any { url.contains(it) } -> LoginState.LOGGED_OUT
            !title.isNullOrBlank() && LOGIN_TITLE.containsMatchIn(title) -> LoginState.LOGGED_OUT
            NavCheck.sameTarget(url, homeUrl) -> LoginState.LOGGED_IN
            else -> LoginState.UNKNOWN
        }
    }
}

/** The cookie import left the TBP daemon down (restart failed twice); [daemonLog] = tail of ~/.tbp/daemon.log. */
class BrowserDownException(message: String, val daemonLog: String) : Exception(message)

/** x_reply failed; [diagnostics] has URL, page text and a screenshot; [steps] what was done. */
class ReplyFailedException(message: String, val diagnostics: LoginDiagnostics?, val steps: List<String>) : Exception(message)

/** x_reply result. [replyUrl] when the new reply (or the toast's View link) was found. */
data class ReplyResult(val replyUrl: String?, val confirmedBy: String, val composer: ComposerKind, val attempts: Int, val steps: List<String>)

/** Parsed `cookies_import` result (bridge ≥ 1.6.0) — verified WITHOUT page JS (cookies.sqlite + history URL). */
data class ImportOutcome(
    val present: List<String>, val missing: List<String>, val url: String?, val loggedIn: Boolean?,
    val keyCookiesOk: Boolean, val daemonDown: Boolean, val daemonLog: String, val error: String?,
) {
    companion object {
        fun parse(data: JsonObject?): ImportOutcome {
            fun list(k: String) = data?.get(k)?.let { runCatching { it.jsonArray.map { e -> e.jsonPrimitive.content } }.getOrNull() }.orEmpty()
            fun str(k: String) = data?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            fun bool(k: String) = data?.get(k)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            return ImportOutcome(list("present"), list("missing"), str("url"), bool("logged_in"),
                bool("key_cookies_ok") ?: false, bool("daemon_down") == true, str("daemon_log").orEmpty(), str("error"))
        }
    }

    /** Login state from the bridge's verdict; no JS eval (TBP's eval could be typed into the URL bar). */
    fun status(site: String): LoginStatus = when {
        loggedIn == true -> LoginStatus(LoginState.LOGGED_IN, url, present, "session cookies in Firefox and $site opened ${url ?: "the home page"}")
        loggedIn == false && !keyCookiesOk -> LoginStatus(LoginState.LOGGED_OUT, url, present, "session cookies missing in Firefox's cookie store: ${missing.joinToString()}")
        loggedIn == false -> LoginStatus(LoginState.LOGGED_OUT, url, present, "$site redirected to ${url ?: "?"} (login page) — the cookies were rejected")
        keyCookiesOk -> LoginStatus(LoginState.LOGGED_IN, url, present, "session cookies in Firefox's cookie store (final URL unknown)")
        else -> LoginStatus(LoginState.LOGGED_OUT, url, present, "session cookies missing in Firefox's cookie store: ${missing.joinToString()}")
    }
}

/**
 * Site-agnostic automation driven entirely by a [SiteConfig] (selectors + step scripts), executed through the
 * Termux Browser Pilot bridge. Used for X.com (Phase 5) and Facebook (Phase 9).
 */
class SocialAutomation(val config: SiteConfig, private val bridge: BridgeClient) {

    private fun js(s: String): String = JsonPrimitive(s).toString() // JSON string literal == valid JS string literal

    private suspend fun evalString(expression: String): String? {
        val r = bridge.eval(expression)
        if (!r.ok) throw AutomationException("eval failed: ${r.errorMessage}")
        return r.valueString()
    }

    /** Presence check that also looks inside open shadow roots and same-origin iframes. */
    suspend fun exists(selectorKey: String): Boolean = evalString(DomFinder.exists(selCss(selectorKey))) == "true"

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
        try { navigateRaw(url, timeoutMs) } finally { if (pageShield) withContext(kotlinx.coroutines.NonCancellable) { shield() } }

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
              loggedIn:q(${js(selCss("loggedIn"))}),loginForm:q(${js(selCss("loginForm"))})})})()""".trimIndent()
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
        val r = evalString(DomFinder.firstPresent(keys.map { selCss(it) }))
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
        return if (keys.isEmpty()) "" else " — selector: " + keys.joinToString(" | ") { "$it = ${selCss(it)}" }
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
        onLog: (String) -> Unit = {},
    ) {
        fun fill(t: String?): String = (t ?: "").let { var r = it; vars.forEach { (k, v) -> r = r.replace("{$k}", v) }; r }
        val stepLog = mutableListOf<String>()
        for ((i, s) in steps.withIndex()) {
            val name = "Step ${i + 1}/${steps.size} (${describe(s)})"
            onStep(i + 1, steps.size, describe(s))
            val t0 = System.currentTimeMillis()
            stepNote = null; lastPollError = null
            fun fail(why: String): Nothing {
                stepLog += ("${i + 1}. ${describe(s)}: FAILED after ${(System.currentTimeMillis() - t0) / 1000.0} s" +
                    (lastPollError?.let { " (last eval error: ${it.take(160)})" } ?: "")).also(onLog)
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
                withTimeoutOrNull(budget) { runOne(s, ::fill, urlParams) }
            } catch (e: StepFailedException) { throw e } catch (e: SessionExpiredException) { throw e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { fail("failed: ${e.message ?: e.javaClass.simpleName}") }
            when (outcome) {
                null -> fail("timed out after ${budget / 1000} s")
                is StepOutcome.Failed -> if (!s.optional) fail(outcome.why)
                StepOutcome.Ok, StepOutcome.Skipped -> Unit
            }
            stepLog += ("${i + 1}. ${describe(s)}: ${if (outcome == StepOutcome.Skipped) "skipped" else "ok"} " +
                "${(System.currentTimeMillis() - t0) / 1000.0} s" + (stepNote?.let { " ($it)" } ?: "")).also(onLog)
        }
        lastStepLog = stepLog.toList()
    }

    @Volatile private var stepNote: String? = null
    /** v1.0.10: selector keys re-pointed at the marked composer by the `target` step (composeText/composeSubmit). */
    private val selOverride = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun selCss(keyOrCss: String): String = selOverride[keyOrCss] ?: config.sel(keyOrCss)
    /** v1.0.9: x_reply's pre-check for the `target` step (null = x_post: log only). Returns null when OK, else why not. */
    @Volatile private var targetCheck: ((ReplyComposer.EditorTarget) -> String?)? = null
    @Volatile private var lastTarget: ReplyComposer.EditorTarget? = null
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
            "target" -> {
                // v1.0.9: what x_post's own selector (first composeText in the document) is about to type into; x_reply
                // also runs its "Replying to @author" pre-check here (before anything is clicked or typed).
                // v1.0.10: picks THE composer (the box in the open dialog, else the first), marks its editor and its own
                // submit button; the following steps use those unique marks, not the generic selectors.
                selOverride.clear()
                val box = config.sel(s.selector ?: "composeText")
                val submit = config.selectors["composeSubmit"]
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    val t = ReplyComposer.parseEditorTarget(runCatching { evalString(ReplyComposer.composeTargetJs(box, config.reply?.dialog ?: "[role=\"dialog\"]",
                        config.reply?.conversation ?: "main", submit)) }.getOrNull())
                    lastTarget = t
                    val why = targetCheck?.invoke(t)
                    stepNote = "target: ${t.describe()}" + (why?.let { "; pre-check: $it" } ?: if (targetCheck != null) "; pre-check ok" else "")
                    if (why == null && t.ok) {
                        selOverride["composeText"] = ReplyComposer.COMPOSE_CSS
                        if (t.submit.isNotBlank()) selOverride["composeSubmit"] = ReplyComposer.SUBMIT_CSS
                        stepNote += "; marked → ${ReplyComposer.COMPOSE_CSS}" + if (t.submit.isNotBlank()) " + ${ReplyComposer.SUBMIT_CSS}" else ""
                        break
                    }
                    if (why == null) break // no box: the next steps fail on their own (x_post)
                    if (System.currentTimeMillis() >= deadline) return StepOutcome.Failed("pre-check: $why (target ${t.describe()})")
                    delay(POLL_MS)
                }
            }
            "waitPosted" -> if (s.text != null) {
                // Posted = the compose dialog closed, or the text shows in a feed item outside the dialog.
                val box = selCss(s.selectors.firstOrNull() ?: "composeText")
                val item = config.scrape?.item ?: "[role=\"article\"]"
                val text = fill(s.text).take(80)
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    if (saveSheetOpen()) return StepOutcome.Failed(SAVE_SHEET_POST)
                    val st = try { mark(DomFinder.postedState(box, item, text)) } catch (e: AutomationException) { lastPollError = e.message; "open" }
                    if (st == "feed" || st == "closed") { stepNote = if (st == "feed") "text appeared in the feed" else "compose dialog closed"; return StepOutcome.Ok }
                    if (System.currentTimeMillis() >= deadline) return StepOutcome.Failed("the compose dialog is still open after ${s.timeoutMs / 1000} s and the post is not in the feed")
                    delay(POLL_MS)
                }
            } else {
                // Posted = the success toast shows, or the compose box closed (one eval per poll: index 0 toast, 1 box).
                val keys = listOfNotNull(s.selector ?: "postSuccess", s.selectors.firstOrNull() ?: "composeText")
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    if (saveSheetOpen()) return StepOutcome.Failed(SAVE_SHEET_POST)
                    val i = try { firstPresent(keys) } catch (e: AutomationException) { lastPollError = e.message; 1 }
                    if (i != 1) { stepNote = if (i == 0) "success toast" else "compose box closed"; return StepOutcome.Ok }
                    if (System.currentTimeMillis() >= deadline) return StepOutcome.Failed("no success toast and the compose box is still open after ${s.timeoutMs / 1000} s")
                    delay(POLL_MS)
                }
            }
            "waitFor", "waitGone" -> {
                val sel = s.selector ?: return StepOutcome.Skipped
                val ok = if (s.dismiss != null && s.action == "waitFor") {
                    val notes = mutableListOf<String>()
                    (pollDismissing(DomFinder.exists(selCss(sel)), s.dismiss, s.timeoutMs, notes, found = "true") != "no")
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
                if (sel == "composeSubmit") submitClicked = true
                if (!exists(sel)) {
                    if (s.optional) return StepOutcome.Skipped
                    if (!waitFor(sel, s.timeoutMs)) {
                        s.fallbackKey?.let { pressKey(it); stepNote = "element not found → key $it"; return StepOutcome.Ok }
                        return StepOutcome.Failed("element not found")
                    }
                }
                return sturdyClick(s, selCss(sel))
            }
            "clickText" -> {
                // Visible-text / aria-label match (regex, case-insensitive) across shadow roots/iframes, clicking away
                // popups ([AutomationStep.dismiss]) while waiting and trying [AutomationStep.fallbackUrls] when nothing matches.
                val re = textRegex(s, fill)
                val scope = (s.scope ?: s.selector)?.let { selCss(it) }
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
                    val css = s.selector?.let { selCss(it) }
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
                val r = bridge.type(selCss(sel), text, submit = s.submit)
                if (!r.ok) return StepOutcome.Failed("typing failed: ${r.errorMessage}")
            }
            "typeEditor" -> {
                val sel = s.selector ?: return StepOutcome.Skipped
                val text = fill(s.text)
                if (s.optional && text.isBlank()) return StepOutcome.Skipped
                if (!exists(sel) && !waitFor(sel, s.timeoutMs)) return StepOutcome.Failed("editor not found")
                return typeIntoEditor(selCss(sel), text, s.timeoutMs)
            }
            "settleSubmit" -> {
                // v1.0.6: the editor text EQUALS the intended text, stable for 500 ms, and the submit button is enabled.
                val box = selCss(s.selector ?: "composeText")
                val send = selCss(s.selectors.firstOrNull() ?: "composeSubmit")
                val why = settleBeforeSubmit(box, send, fill(s.text), config.reply?.stray ?: StraySpec(), { stepNote = it })
                if (why != null) return StepOutcome.Failed(why)
            }
            "press" -> bridge.press(s.key ?: "Enter")
            "sleep" -> delay(s.ms.coerceIn(0, 30_000))
            else -> return StepOutcome.Failed("unknown step action '${s.action}'")
        }
        return StepOutcome.Ok
    }

    /** Upper bound for the TBP click attempt (tests shorten it). */
    internal var clickTryMaxMs = 30_000L

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
     * Types into a (Draft.js) editor: bridge `editor_type` (focus by eval, xdotool typing, verify, insertText fallback);
     * an old bridge gets `tbp type` and then the same verify → insertText fallback from here. Never leaves without
     * checking that the text is in the editor.
     */
    private suspend fun typeIntoEditor(css: String, text: String, timeoutMs: Long): StepOutcome {
        val budget = timeoutMs + text.length * 400L
        val r = try { withTimeoutOrNull(budget) { bridge.editorType(css, text) } } catch (e: java.io.IOException) { null }
        val data = r?.data as? JsonObject
        fun bridgeSteps() = (data?.get("steps") as? kotlinx.serialization.json.JsonArray)?.joinToString(" → ") { it.jsonPrimitive.contentOrNull ?: "" }.orEmpty()
        if (r != null && r.ok) {
            // v1.0.10: read the target editor back from here too (the marked composer, not the first match); bridge ≥ 1.12.0.
            val got = if (data?.containsKey("where") == true) editorText(css) else null
            if (got != null && !DomFinder.containsText(got, text))
                return StepOutcome.Failed("bridge reported the text typed but the target editor has ${got.length} chars (${bridgeSteps().take(400)})")
            stepNote = "editor_type via ${data?.get("method")?.jsonPrimitive?.contentOrNull ?: "?"}" +
                (data?.get("seconds")?.jsonPrimitive?.contentOrNull?.let { " in $it s" } ?: "") + bridgeSteps().takeIf { it.isNotBlank() }?.let { ": ${it.take(400)}" }.orEmpty()
            return StepOutcome.Ok
        }
        if (r != null && data?.containsKey("where") == true) {
            // Bridge ≥ 1.12.0 already probed, pasted and verified THIS element: fail fast with its diagnostics. No
            // console insertText here — from the DevTools console it broke the Draft.js editor in the fixture.
            return StepOutcome.Failed("the text did not reach the composer: ${r.errorMessage.take(300)} (${bridgeSteps().take(500)})")
        }
        val notes = mutableListOf<String>()
        when {
            r == null -> { notes += "editor_type timed out"; notes += waitIdle() }
            r.unknownCmd() -> {
                notes += "old bridge"
                jsFocusOrClick(css)
                val t = try { withTimeoutOrNull(budget) { bridge.type(css, text) } } catch (e: java.io.IOException) { null }
                notes += when { t == null -> "tbp type timed out"; t.ok -> "tbp type ok"; else -> "tbp type failed" }
                if (t == null) notes += waitIdle()
            }
            else -> notes += "editor_type: ${r.errorMessage.take(160)}"
        }
        if (DomFinder.containsText(editorText(css), text)) { stepNote = notes.joinToString("; "); return StepOutcome.Ok }
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
     * page load drops them). [pageShield] is set by reply/post; scrapes install it in their own poll eval.
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
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { evalString(GrokShield.OFF) } }
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
     * focal post. The side nav / account switcher (the logged-in account's own name), the inline reply composer and
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
     * Deterministic reply (x_reply): open the post, wait for the focal post, open ITS reply composer (reply button →
     * modal) or use the inline reply box of the conversation, type with the x_post editor method, verify the text,
     * submit only with that composer's own button, confirm (toast / composer cleared / the new reply). Never touches the
     * composer toolbar (schedule, GIF, poll, emoji, location, media). A wrong dialog/page is closed with Escape and the
     * whole thing is retried once via the reply intent URL. Nothing is retried after the submit click (no double posts).
     */
    /**
     * x_reply (v1.0.9) = x_post's implementation ([composeAndSubmit]), parameterized only by how the composer is opened
     * and a "Replying to @author" pre-check in the `target` step — no separate reply typing path any more.
     *  A. x_post's compose page with the reply id: `/compose/post?in_reply_to=<id>`. Accepted only if the box x_post's
     *     selector resolves to is in the composer dialog and the dialog says "Replying to @author".
     *  B. Otherwise the post page: x_post's selector then resolves to the conversation's inline reply box under the
     *     post (like the home page's inline box); the reply bubble is clicked only when no inline box is there.
     * The pre-check fails BEFORE anything is clicked or typed, so B can run safely after A.
     */
    suspend fun reply(postUrl: String, text: String, onStep: (String) -> Unit = {}): ReplyResult {
        val spec = config.reply ?: throw AutomationException("${config.displayName} has no reply spec")
        val canon = StatusUrl.canonical(postUrl) ?: throw AutomationException("not a post URL: $postUrl")
        val steps = mutableListOf<String>()
        lastReplySteps = steps
        fun log(m: String) { synchronized(steps) { steps += m }; onStep(m) }
        val stages = Stages()
        var path = "?"
        submitClicked = false
        val r = withTimeoutOrNull(REPLY_BUDGET_MS) { shielded {
            var lastWhy = ""
            for (p in listOf("compose", "post page")) {
                path = p
                log("path: $p")
                val open = if (p == "compose") listOf(AutomationStep("goto", url = config.url("compose") + "?in_reply_to=" + canon.id, timeoutMs = 45_000))
                    else listOf(AutomationStep("goto", url = canon.url, timeoutMs = 45_000))
                if (p == "post page") openPostComposer(spec, canon, ::log)
                val ok = try {
                    composeAndSubmit(text, open = if (p == "post page") emptyList() else open, check = { t -> replyPreCheck(t, canon, spec, modal = p == "compose") }, onLog = ::log)
                    true
                } catch (e: StepFailedException) {
                    lastWhy = e.message?.substringBefore("\nStep log:") ?: "step failed"
                    log("timing: " + stages.mark(p))
                    // Only a failed pre-check (nothing clicked or typed yet) may fall through to the next path.
                    if (!lastWhy.contains("pre-check:") || submitClicked) {
                        if (submitClicked) return@shielded ReplyResult(null, "submitted (not confirmed: ${lastWhy.take(120)})", kindOf(lastTarget), if (p == "compose") 1 else 2, steps.toList())
                        log("stage timings: ${stages.summary()}")
                        throw replyFailure("x_reply ($p): $lastWhy; nothing was posted", steps.toList())
                    }
                    log("$p: ${lastWhy.substringAfter("pre-check: ")} → next path")
                    false
                }
                if (ok) {
                    log("timing: " + stages.mark(p))
                    val how = lastStepLog.lastOrNull()?.substringAfter("(", "")?.substringBefore(")")?.ifBlank { null } ?: "posted"
                    val link = runCatching { evalString("(()=>{const a=document.querySelector(${js(spec.toast + " a[href*=\"/status/\"]")});return a?a.href:''})()") }.getOrNull()?.ifBlank { null }
                    log(if (link != null) "reply found: $link" else "reply URL not in the toast")
                    log("stage timings: ${stages.summary()}")
                    return@shielded ReplyResult(link, if (how.contains("toast")) "toast" else how, kindOf(lastTarget), if (p == "compose") 1 else 2, steps.toList())
                }
            }
            log("stage timings: ${stages.summary()}")
            throw replyFailure("could not open a reply composer for ${canon.url}: $lastWhy", steps.toList())
        } }
        if (r != null) return r
        log("x_reply budget of ${REPLY_BUDGET_MS / 1000} s used up ($path); stage timings: ${stages.summary()}")
        if (submitClicked) return ReplyResult(null, "submitted (not confirmed in time)", kindOf(lastTarget), 0, steps.toList())
        throw replyFailure("x_reply timed out after ${REPLY_BUDGET_MS / 1000} s before submitting; nothing was posted", steps.toList())
    }

    private fun kindOf(t: ReplyComposer.EditorTarget?) = if (t?.inDialog == false) ComposerKind.INLINE else ComposerKind.MODAL

    /**
     * Path B opener: the post page; when the conversation has no inline reply box under the post, the post's own reply
     * bubble (trusted click) — then x_post's steps take over.
     */
    private suspend fun openPostComposer(spec: ReplyComposerSpec, canon: StatusUrl.Canon, log: (String) -> Unit) {
        log("open: " + runCatching { navigateTo(canon.url, 40_000) }.getOrElse { if (it is SessionExpiredException) throw it; "unconfirmed (${it.message?.take(80)})" })
        val inline = "${spec.conversation} ${spec.textarea}"
        if (waitFor(inline, 8_000)) { log("open: the post's inline reply box is there"); return }
        val target = findTarget(spec, canon.id)
        if (target?.replyButton != null && target.how == "status id") {
            log("open: no inline box → reply bubble of the post")
            clickBubble(target.replyButton, log)
        } else log("open: no inline box and no reply bubble for ${canon.id} (${target?.how ?: "post not found"})")
    }

    /** x_reply's pre-check on the composer the `target` step picked (v1.0.10: the dialog's box, not the first match). Null = OK. */
    internal fun replyPreCheck(t: ReplyComposer.EditorTarget, canon: StatusUrl.Canon, spec: ReplyComposerSpec, modal: Boolean): String? {
        if (!t.ok) return "no composer box (${t.why})"
        if (t.inDialog) return IntentComposer.check(t.contextJson(), spec.replyingTo, canon.user)
        if (modal) return "no reply box inside a composer dialog (only a page box, where a post would not be a reply)"
        val onPost = t.url?.let { StatusUrl.canonical(it)?.id } == canon.id
        return when {
            !onPost -> "the page is ${t.url ?: "?"}, not the post ${canon.id}"
            !t.inConversation || t.inArticle -> "the box is not the conversation's reply box under the post"
            else -> null
        }
    }

    /** Steps of the last x_reply (also for errors that are not a [ReplyFailedException]). */
    @Volatile var lastReplySteps: List<String> = emptyList()
        private set

    /** v1.0.8: per-stage durations of one x_reply ("open 3.1 s · wait box 0.8 s · …"), logged so the 60 s budget is traceable. */
    internal class Stages(private val clock: () -> Long = System::currentTimeMillis) {
        private val t0 = clock()
        private var last = t0
        private val parts = mutableListOf<String>()
        /** Records the time since the previous mark under [name]; returns e.g. "type 4.2 s (t=12.5 s)". */
        fun mark(name: String): String {
            val now = clock()
            val part = "$name ${secs(now - last)}"
            parts += part; last = now
            return "$part (t=${secs(now - t0)})"
        }
        fun elapsedMs() = clock() - t0
        fun summary() = parts.joinToString(" · ").ifBlank { "none" } + " · total ${secs(clock() - t0)}"
        private fun secs(ms: Long) = "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
    }

    /** Null = ready to submit; else why not (after ≤ [SubmitGuard.SETTLE_TIMEOUT_MS]). */
    private suspend fun settleBeforeSubmit(box: String, send: String, text: String, stray: StraySpec, log: (String) -> Unit): String? {
        val settle = SubmitGuard.Settle(text)
        val deadline = System.currentTimeMillis() + SubmitGuard.SETTLE_TIMEOUT_MS
        val probe = SubmitGuard.probeJs(box, send, stray)
        var polls = 0
        while (true) {
            val o = try { evalString(probe)?.let { Json.parseToJsonElement(it).jsonObject } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            polls++
            if (o?.get("save")?.jsonPrimitive?.booleanOrNull == true) return "a 'Save post?' sheet is open before the submit"
            val t = o?.get("t")?.jsonPrimitive?.contentOrNull
            val en = o?.get("en")?.jsonPrimitive?.booleanOrNull == true
            if (o != null && settle.feed(t, en, System.currentTimeMillis())) { log("pre-submit: text matches and is stable, button enabled ($polls probes)"); return null }
            if (System.currentTimeMillis() >= deadline) return "not submitted: ${settle.lastWhy}"
            delay(FastScrape.POLL_MS)
        }
    }

    private fun postArticle(spec: ReplyComposerSpec) = "${spec.conversation} ${spec.article}"

    private suspend fun findTarget(spec: ReplyComposerSpec, id: String): TargetPost? {
        val raw = runCatching { evalString(ReplyComposer.markJs(spec)) }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return ReplyComposer.target(o["h"]?.jsonPrimitive?.contentOrNull.orEmpty(), spec, id)
    }

    /**
     * Trusted click on the bubble like x_post's buttons: scroll it to the centre first (the mouse path is replayed to
     * on-screen coordinates), TBP human click, one more TBP click after waiting for idle. No synthetic el.click().
     */
    private suspend fun clickBubble(css: String, log: (String) -> Unit): Boolean {
        runCatching { evalString("(()=>{const e=document.querySelector(${js(css)});if(!e)return 'none';e.scrollIntoView({block:'center',inline:'center'});return 'ok'})()") }
        delay(700)
        repeat(2) { i ->
            val r = try { withTimeoutOrNull(clickTryMs(AutomationStep("click"))) { bridge.click(css) } } catch (e: java.io.IOException) { null }
            if (r?.ok == true) { log("reply bubble: trusted click" + if (i > 0) " (2nd try)" else ""); return true }
            log("reply bubble click ${if (r == null) "timed out" else "failed: ${r.errorMessage.take(80)}"}")
            waitIdle()
        }
        return false
    }

    /**
     * Closes leftover overlays (unsent posts/drafts view, schedule picker, an old composer, a "Save post?" sheet) that
     * aren't [ours]. Per overlay, escalating: Discard (never Save) → its close button → Escape → the mask. Re-checks
     * after each action. Returns the overlays still open (empty = clean).
     */
    suspend fun cleanStray(spec: ReplyComposerSpec, log: (String) -> Unit = {}, ours: String? = null): List<Stray> {
        val st = spec.stray
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
            delay(700)
        }
        if (left.isNotEmpty()) log("still open after cleanup: ${left.joinToString { it.summary }}")
        return left
    }

    private suspend fun replyFailure(why: String, steps: List<String>): ReplyFailedException {
        val d = runCatching { diagnostics() }.getOrNull()
        return ReplyFailedException(why + (d?.url?.let { " (page: $it)" } ?: ""), d, steps)
    }

    suspend fun post(text: String) {
        val done = withTimeoutOrNull(POST_BUDGET_MS) { shielded { composeAndSubmit(text, open = config.postSteps.takeWhile { it.action == "goto" }, check = null) } }
        if (done == null) throw AutomationException("x_post timed out after ${POST_BUDGET_MS / 1000} s" +
            if (submitClicked) " after the Post click: check the profile before posting again (no blind retry)" else "; nothing was posted")
    }

    /**
     * v1.0.9: x_post's implementation, shared VERBATIM by x_post and x_reply: leftover-dialog cleanup, [open] (how the
     * composer is opened), then x.json's postSteps after their goto — waitFor composeText, the `target` step (logs what
     * composeText resolves to; x_reply's "Replying to @author" pre-check [check]), click composeText, typeEditor
     * composeText (bridge editor_type), settleSubmit, click composeSubmit, waitPosted. Same selectors, waits, typing call.
     */
    private suspend fun composeAndSubmit(text: String, open: List<AutomationStep>, check: ((ReplyComposer.EditorTarget) -> String?)?,
                                         onLog: (String) -> Unit = {}) {
        config.reply?.let { spec ->
            // Same leftover-dialog cleanup as before; a stuck overlay → hard navigation home.
            if (cleanStray(spec).isNotEmpty()) {
                runCatching { bridge.eval("location.replace(${js(config.url("home"))})") }
                delay(2_000)
                cleanStray(spec)
            }
        }
        submitClicked = false
        lastTarget = null
        targetCheck = check
        selOverride.clear()
        try { runSteps(composerSteps(open), mapOf("text" to text), onLog = onLog) } finally { targetCheck = null; selOverride.clear() }
    }

    /** [open] + x_post's steps after their goto, with the `target` step right after `waitFor composeText`. */
    internal fun composerSteps(open: List<AutomationStep>): List<AutomationStep> {
        val body = config.postSteps.dropWhile { it.action == "goto" }
        if (config.reply == null) return open + body // only X has a reply composer to target/pre-check
        val i = body.indexOfFirst { it.action == "waitFor" && it.selector == "composeText" }
        val target = AutomationStep("target", selector = "composeText", timeoutMs = TARGET_CHECK_MS, label = "composer target / pre-check")
        return open + if (i < 0) listOf(target) + body else body.take(i + 1) + target + body.drop(i + 1)
    }

    /** X's "Save post?" sheet is open (the post/reply was NOT sent). */
    private suspend fun saveSheetOpen(): Boolean = config.reply?.stray?.let { st ->
        runCatching { evalString("String(" + SubmitGuard.saveSheetExpr(st) + ")") == "true" }.getOrDefault(false)
    } ?: false

    /** Set when a post script clicked its submit button (composeSubmit): a timeout after that may still have posted. */
    @Volatile private var submitClicked = false

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
        private const val POLL_MS = 600L
        /** Whole-x_reply budget (loop protection). */
        const val REPLY_BUDGET_MS = 90_000L
        /** `target` step: how long x_reply's pre-check may wait for the composer to say "Replying to @author". */
        const val TARGET_CHECK_MS = 4_000L
        /** Whole-x_post budget (loop protection). */
        const val POST_BUDGET_MS = 60_000L
        const val SAVE_SHEET_POST = "X showed 'Save post?' after the Post click: the post was NOT sent (the text stayed in the composer). " +
            "Do not retry with web_click/web_type; report it."
        /** Extra step budget for click / typeEditor fallbacks (idle wait + JS focus + insertText). */
        private const val FALLBACK_BUDGET_MS = 75_000L
        private const val OLD_BRIDGE_IDLE_MS = 3_000L
        const val TARGET_CSS = DomFinder.TARGET_CSS
    }
}
