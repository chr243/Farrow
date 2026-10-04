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
        for ((i, s) in steps.withIndex()) {
            val name = "Step ${i + 1}/${steps.size} (${describe(s)})"
            onStep(i + 1, steps.size, describe(s))
            val t0 = System.currentTimeMillis()
            stepNote = null; lastPollError = null
            fun fail(why: String): Nothing {
                stepLog += "${i + 1}. ${describe(s)}: FAILED after ${(System.currentTimeMillis() - t0) / 1000.0} s" +
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
                withTimeoutOrNull(budget) { runOne(s, ::fill, urlParams) }
            } catch (e: StepFailedException) { throw e } catch (e: SessionExpiredException) { throw e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { fail("failed: ${e.message ?: e.javaClass.simpleName}") }
            when (outcome) {
                null -> fail("timed out after ${budget / 1000} s")
                is StepOutcome.Failed -> if (!s.optional) fail(outcome.why)
                StepOutcome.Ok, StepOutcome.Skipped -> Unit
            }
            stepLog += "${i + 1}. ${describe(s)}: ${if (outcome == StepOutcome.Skipped) "skipped" else "ok"} " +
                "${(System.currentTimeMillis() - t0) / 1000.0} s" + (stepNote?.let { " ($it)" } ?: "")
        }
        lastStepLog = stepLog.toList()
    }

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
            "waitPosted" -> if (s.text != null) {
                // Posted = the compose dialog closed, or the text shows in a feed item outside the dialog.
                val box = config.sel(s.selectors.firstOrNull() ?: "composeText")
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
                if (sel == "composeSubmit") submitClicked = true
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
            "settleSubmit" -> {
                // v1.0.6: the editor text EQUALS the intended text, stable for 500 ms, and the submit button is enabled.
                val box = config.sel(s.selector ?: "composeText")
                val send = config.sel(s.selectors.firstOrNull() ?: "composeSubmit")
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
        if (r != null && r.ok) {
            stepNote = "editor_type via ${(r.data as? JsonObject)?.get("method")?.jsonPrimitive?.contentOrNull ?: "?"}"
            return StepOutcome.Ok
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

    /**
     * v1.0.8 x_reply typing = x_post's routine on the right element: resolve + mark the contenteditable INSIDE the open
     * dialog ([ReplyComposer.editorTargetJs]; inline: the conversation box), the same trusted click as x_post's
     * `click composeText` ([sturdyClick]), a check that document.activeElement is in it, the same [typeIntoEditor]
     * (bridge editor_type: focus → xdotool → verify → insertText), then the editor's textContent is verified for ≤ 2 s.
     * Still missing → ONE retry: refocus + execCommand insertText, then a synthetic paste. Fails fast with the target.
     */
    private suspend fun focusAndType(spec: ReplyComposerSpec, pick: ComposerPick.Found, text: String, log: (String) -> Unit, stages: Stages): StepOutcome {
        val css = ReplyComposer.EDITOR_CSS
        val target = ReplyComposer.parseEditorTarget(runCatching { evalString(ReplyComposer.editorTargetJs(pick.box, spec, pick.kind == ComposerKind.MODAL)) }.getOrNull())
        log("editor target: ${target.describe()}")
        if (!target.ok) return StepOutcome.Failed("no editor to type into (${target.why})")
        if (pick.kind == ComposerKind.MODAL && !target.inDialog) return StepOutcome.Failed("the editor found is not inside the dialog (${target.describe()})")
        stepNote = null
        val click = sturdyClick(AutomationStep("click", timeoutMs = 10_000), css)
        log("focus: trusted click ${if (click == StepOutcome.Ok) "ok" else (click as StepOutcome.Failed).why}${stepNote?.let { " ($it)" } ?: ""}")
        var active = runCatching { evalString(ReplyComposer.ACTIVE_JS) }.getOrNull()
        if (active != "yes") { val f = jsFocusOrClick(css); active = runCatching { evalString(ReplyComposer.ACTIVE_JS) }.getOrNull(); log("focus: activeElement not in the editor → JS focus ${f ?: "failed"} → ${if (active == "yes") "in the editor" else "still outside"}") }
        else log("focus: activeElement is in the editor")
        log("timing: " + stages.mark("focus"))
        stepNote = null
        val typeBudget = (12_000L + text.length * 150L).coerceAtMost(maxOf(8_000L, REPLY_BUDGET_MS - 15_000L - stages.elapsedMs()))
        val typed = typeIntoEditor(css, text, typeBudget)
        log("typing: ${if (typed == StepOutcome.Ok) "ok" else (typed as StepOutcome.Failed).why}${stepNote?.let { " — $it" } ?: ""}")
        log("timing: " + stages.mark("type"))
        if (awaitEditorText(text, 2_000)) { log("verify: the dialog's editor holds the text"); log("timing: " + stages.mark("verify")); return StepOutcome.Ok }
        // One retry with an alternative input method.
        val got0 = editorTextContent()
        log("verify: editor has ${got0?.length ?: 0} chars after typing → retry: refocus + insertText")
        jsFocusOrClick(css)
        val ins = runCatching { withTimeoutOrNull(10_000) { evalString(DomFinder.insertText(css, text)) } }.getOrNull()
        if (awaitEditorText(text, 2_000)) { log("retry: insertText ${ins ?: "?"} → text present"); log("timing: " + stages.mark("retry")); return StepOutcome.Ok }
        val paste = runCatching { withTimeoutOrNull(10_000) { evalString(ReplyComposer.pasteJs(text)) } }.getOrNull()
        if (awaitEditorText(text, 1_500)) { log("retry: paste ${paste ?: "?"} → text present"); log("timing: " + stages.mark("retry")); return StepOutcome.Ok }
        val got = editorTextContent()
        log("timing: " + stages.mark("retry"))
        return StepOutcome.Failed("the text never reached the editor (typed, insertText: ${ins ?: "failed"}, paste: ${paste ?: "failed"}; " +
            "editor has ${got?.length ?: 0} chars; target ${target.describe()})")
    }

    private suspend fun editorTextContent(): String? =
        runCatching { withTimeoutOrNull(10_000) { evalString(ReplyComposer.EDITOR_TEXT_JS) } }.getOrNull()?.takeIf { it != "__missing__" }

    private suspend fun awaitEditorText(text: String, ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            if (DomFinder.containsText(editorTextContent(), text)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(FastScrape.POLL_MS)
        }
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
    suspend fun reply(postUrl: String, text: String, onStep: (String) -> Unit = {}): ReplyResult {
        val spec = config.reply ?: throw AutomationException("${config.displayName} has no reply spec")
        val canon = StatusUrl.canonical(postUrl) ?: throw AutomationException("not a post URL: $postUrl")
        val steps = mutableListOf<String>()
        lastReplySteps = steps
        fun log(m: String) { steps += m; onStep(m) }
        val run = ReplyRun()
        val t0 = System.currentTimeMillis()
        // Loop protection: the whole x_reply is bounded (~60 s); after the submit click only the confirmation runs.
        val r = withTimeoutOrNull(REPLY_BUDGET_MS) { shielded { replyAttempts(spec, canon, text, steps, ::log, run) } }
        if (r != null) return r
        log("x_reply budget of ${REPLY_BUDGET_MS / 1000} s used up after ${(System.currentTimeMillis() - t0) / 1000} s; stage timings: ${run.stages.summary()}")
        if (run.submitted) return ReplyResult(null, run.confirmed ?: "submitted (not confirmed in time)", run.kind ?: ComposerKind.MODAL, run.attempt, steps)
        throw replyFailure("x_reply timed out after ${REPLY_BUDGET_MS / 1000} s before submitting; nothing was posted", steps)
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

    private class ReplyRun { val stages = Stages(); var viaIntent = true; var submitted = false; var confirmed: String? = null; var kind: ComposerKind? = null; var attempt = 0; var saveSheetRetry = false }

    private suspend fun replyAttempts(spec: ReplyComposerSpec, canon: StatusUrl.Canon, text: String, steps: MutableList<String>,
                                      log: (String) -> Unit, run: ReplyRun): ReplyResult {
        val postUrl = canon.url
        val id = canon.id
        var lastWhy = ""
        // v1.0.7: PRIMARY = the reply intent composer (x.com/intent/post?in_reply_to=<id> → /compose/post, a bare
        // composer "Replying to @user"): the post page (Grok drawer, media, recommendations) is skipped entirely.
        // Fallback = the post's own reply bubble on the post page. A "Save post?" retry keeps the same path.
        val maxAttempts = spec.maxAttempts.coerceIn(1, 2)
        var attempt = 0
        var viaIntent = true
        while (attempt < maxAttempts) {
            attempt++
            run.attempt = attempt
            if (attempt > 1 && !run.saveSheetRetry) viaIntent = !viaIntent
            run.viaIntent = viaIntent
            val strayUrl = if (viaIntent) null else postUrl
            val pick = try {
                openComposer(spec, postUrl, id, viaIntent, log, canon)
            } catch (e: SessionExpiredException) { throw e } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { ComposerPick.Missing(e.message ?: e.javaClass.simpleName) }
            if (pick !is ComposerPick.Found) {
                lastWhy = (pick as? ComposerPick.Wrong)?.why ?: (pick as ComposerPick.Missing).why
                log("attempt $attempt: $lastWhy")
                closeStrayDialogs(spec, log, strayUrl)
                continue
            }
            run.kind = pick.kind
            log("attempt $attempt: ${pick.kind.name.lowercase()} composer")
            log("timing: " + run.stages.mark("open composer"))
            // v1.0.8: x_post's exact focus+type routine (trusted click → editor_type), on the editor INSIDE the dialog.
            val typed = focusAndType(spec, pick, text, log, run.stages)
            if (typed is StepOutcome.Failed) {
                // Fail fast (nothing was posted) instead of spending the rest of the 60 s budget.
                clearBox(ReplyComposer.EDITOR_CSS)
                log("stage timings: ${run.stages.summary()}")
                throw replyFailure("typing failed in the ${pick.kind.name.lowercase()} reply composer: ${typed.why}; nothing was posted", steps)
            }
            // Re-locate: the page must still be the reply composer (same box).
            val after = locateComposer(spec, preferInline = pick.kind == ComposerKind.INLINE)
            if (after !is ComposerPick.Found || after.box != pick.box) {
                lastWhy = "the composer changed while typing (${(after as? ComposerPick.Wrong)?.why ?: (after as? ComposerPick.Missing)?.why ?: "different box"})"
                log(lastWhy); closeStrayDialogs(spec, log, strayUrl); continue
            }
            // Settle: text EQUALS the intended text, unchanged for 500 ms, and the Reply button enabled.
            val settled = settleBeforeSubmit(after.box, after.send, text, spec.stray, log)
            if (settled != null) {
                lastWhy = settled
                log(lastWhy); clearBox(pick.box); closeStrayDialogs(spec, log, strayUrl); continue
            }
            log("timing: " + run.stages.mark("settle"))
            // Submit ONCE with the composer's own button. Nothing touches the page in between (no blur).
            run.submitted = submitOnce(after, spec, log)
            log("timing: " + run.stages.mark("submit"))
            if (!run.submitted) throw replyFailure("could not click the composer's Reply button", steps)
            when (val c = confirmReply(spec, pick, text, log)) {
                is Confirm.Ok -> {
                    run.confirmed = c.how
                    // Cheap only: the toast's View link; the post page is searched only on the bubble path (already there).
                    val url = c.link ?: if (!viaIntent) findOwnReply(postUrl, text) else null
                    log(if (url != null) "reply found: $url" else "reply URL not found in the conversation")
                    return ReplyResult(url, c.how, pick.kind, attempt, steps)
                }
                Confirm.SaveSheet -> {
                    // X kept the text and asks "Save post?": the reply was NOT sent. Discard, then retry the submit once.
                    log("X showed 'Save post?' after the submit click: the reply was not sent")
                    cleanStray(spec, log)
                    if (run.saveSheetRetry) throw replyFailure("X showed 'Save post?' after the submit click twice; the reply was not sent (discarded)", steps)
                    run.saveSheetRetry = true; run.submitted = false
                    if (attempt >= maxAttempts) attempt = maxAttempts - 1 // exactly one more try
                    closeStrayDialogs(spec, log, strayUrl)
                    continue
                }
                Confirm.Timeout -> throw replyFailure("the reply was submitted but neither a toast nor the emptied/closed composer confirmed it " +
                    "(check the post before trying again; do not re-submit blindly)", steps)
            }
        }
        throw replyFailure("could not open the reply composer: $lastWhy", steps)
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

    /**
     * One trusted click on the submit button. If the TBP click reports a failure, first check whether it went through
     * anyway (composer gone/emptied) before a single JS click — never two submits.
     */
    private suspend fun submitOnce(after: ComposerPick.Found, spec: ReplyComposerSpec, log: (String) -> Unit): Boolean {
        val clicked = try { withTimeoutOrNull(15_000) { bridge.click(after.send) } } catch (e: java.io.IOException) { null }
        if (clicked?.ok == true) { log("submit: clicked once"); return true }
        waitIdle(15)
        val st = runCatching { evalString(SubmitGuard.probeJs(after.box, after.send, spec.stray))?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        val t = st?.get("t")?.jsonPrimitive?.contentOrNull
        if (st != null && (st["send"]?.jsonPrimitive?.booleanOrNull == false || SubmitGuard.isEmpty(t))) {
            log("submit: the click went through (composer ${if (SubmitGuard.isEmpty(t)) "emptied" else "closed"})"); return true
        }
        if (jsFocusOrClick(after.send) == null) return false
        log("submit: JS click (TBP click failed)")
        return true
    }

    private suspend fun openComposer(spec: ReplyComposerSpec, postUrl: String, id: String, viaIntent: Boolean, log: (String) -> Unit,
                                     canon: StatusUrl.Canon? = null): ComposerPick {
        if (viaIntent) {
            val u = config.url("replyIntent", mapOf("id" to id))
            // The intent URL redirects (compose), so history never shows it: short confirmation, then wait for the box.
            val n = runCatching { navigateTo(u, 10_000) }.onFailure { if (it is SessionExpiredException) throw it }
            log("path: intent composer; open " + (n.getOrNull() ?: "unconfirmed (${n.exceptionOrNull()?.message?.take(80)})"))
            shield()
            // v1.0.8: wait for the box INSIDE the composer dialog. The intent opens as a modal over /home, whose own
            // timeline composer (also tweetTextarea_0, first in the document) satisfied the old wait at once.
            val tw = System.currentTimeMillis()
            if (!waitFor("${spec.dialog} ${spec.textarea}", 15_000)) { ensureLoggedIn(navigate = false); return ComposerPick.Missing("the reply intent opened no composer dialog") }
            log("intent: composer dialog after ${System.currentTimeMillis() - tw} ms")
            cleanStray(spec, log, ours = spec.textarea)
            val pick = locateComposer(spec)
            if (pick !is ComposerPick.Found) return pick
            if (pick.kind != ComposerKind.MODAL) return ComposerPick.Wrong("the intent composer dialog is not open (only the page's own box behind it)")
            // Never post a standalone post: the composer must say "Replying to @<author>".
            val ctx = runCatching { evalString(IntentComposer.contextJs(pick.box, spec.dialog)) }.getOrNull()
            val why = IntentComposer.check(ctx, spec.replyingTo, canon?.user)
            if (why != null) { log("intent composer rejected: $why"); return ComposerPick.Wrong(why) }
            log("intent composer: ${IntentComposer.summary(ctx, spec.replyingTo)}")
            return pick
        }
        if (!ensureCleanPost(spec, postUrl, log)) {
            ensureLoggedIn(navigate = false)
            return ComposerPick.Wrong("not on the post page (${StatusUrl.canonical(postUrl)?.url ?: postUrl}) or a stray dialog stays open")
        }
        locateComposer(spec).let { if (it is ComposerPick.Wrong) return it }
        // Primary path: the target post's own speech-bubble reply button → composer dialog with the box focused.
        val target = findTarget(spec, id)
        if (target?.replyButton == null || target.how != "status id") {
            // Only the article whose own permalink is the id gets the bubble click (a focal fallback could be another post).
            log(when {
                target == null -> "path: no target post found → inline box"
                target.how != "status id" -> "path: post ${id} not identified by its permalink (only ${target.how}) → inline box"
                else -> "path: target post has no reply button → inline box"
            })
        } else {
            log("target post by ${target.how}")
            if (clickBubble(target.replyButton, log) && waitFor("${spec.dialog} ${spec.textarea}", spec.bubbleWaitMs)) {
                val pick = locateComposer(spec)
                if (pick is ComposerPick.Found && pick.kind == ComposerKind.MODAL) {
                    log("path: reply bubble → dialog")
                    return pick
                }
                log("bubble opened something else: ${(pick as? ComposerPick.Wrong)?.why ?: (pick as? ComposerPick.Missing)?.why ?: "inline"}")
                if (pick is ComposerPick.Wrong) return pick
            } else log("reply bubble opened no composer within ${spec.bubbleWaitMs / 1000} s")
        }
        // Fallback: the inline reply box of the conversation (the intent URL is the next attempt).
        return locateComposer(spec, preferInline = true).also { if (it is ComposerPick.Found) log("path: inline reply box") }
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

    private suspend fun locateComposer(spec: ReplyComposerSpec, preferInline: Boolean = false): ComposerPick {
        val raw = evalString(ReplyComposer.markJs(spec)) ?: return ComposerPick.Missing("page did not answer")
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ComposerPick.Missing("bad page snapshot")
        return ReplyComposer.locate(o["h"]?.jsonPrimitive?.contentOrNull.orEmpty(), spec, o["u"]?.jsonPrimitive?.contentOrNull, preferInline)
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

    /**
     * Clean start on the post: close strays; if any remain or the URL isn't the post (/compose/, /schedule, /unsent,
     * /drafts…), hard-navigate with location.replace and wait for the focal post; if that fails, go home and back.
     */
    suspend fun ensureCleanPost(spec: ReplyComposerSpec, postUrl: String, log: (String) -> Unit): Boolean {
        val canon = StatusUrl.canonical(postUrl) ?: throw AutomationException("not a post URL: $postUrl")
        val left = cleanStray(spec, log)
        val here = probePost(spec, canon)
        val wrong = StrayDialogs.notOnPost(here?.first, spec.stray)
        if (left.isEmpty() && wrong == null && here != null && !StatusUrl.needsNavigation(here.first, canon) && here.second) {
            log("already on the post (${here.first})"); return true
        }
        log("navigate to ${canon.url} (" + (if (left.isNotEmpty()) "stray dialog open" else wrong?.let { "on ${here?.first}" }
            ?: if (here != null && !StatusUrl.needsNavigation(here.first, canon)) "post not loaded" else "on ${here?.first ?: "?"}") + ")")
        hardNav(canon, log)
        if (waitOnPost(spec, canon, 25_000) && cleanStray(spec, log).isEmpty()) return true
        log("still not on the post → x.com home, then the post again")
        runCatching { navigateTo(config.url("home"), 30_000) }.onFailure { if (it is SessionExpiredException) throw it }
        cleanStray(spec, log)
        hardNav(canon, log)
        return waitOnPost(spec, canon, 25_000) && cleanStray(spec, log).isEmpty()
    }

    /** location.href + "the target article (own permalink = the status id) is on the page"; null = eval failed. */
    private suspend fun probePost(spec: ReplyComposerSpec, c: StatusUrl.Canon): Pair<String?, Boolean>? {
        val raw = try { evalString(StatusUrl.probeJs(postArticle(spec), c.id)) } catch (e: AutomationException) { lastPollError = e.message; null } ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return o["u"]?.jsonPrimitive?.contentOrNull to (o["a"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /** Full page load of the post (keyboard navigation, confirmed by history; /i/status redirects, so not awaited long). */
    private suspend fun hardNav(c: StatusUrl.Canon, log: (String) -> Unit) {
        val r = runCatching { navigateTo(c.url, if (c.user == "i") 8_000 else 30_000) }
        r.exceptionOrNull()?.let { if (it is SessionExpiredException) throw it }
        log("open ${c.url}: " + (r.getOrNull() ?: "unconfirmed (${r.exceptionOrNull()?.message?.take(120)})"))
    }

    /** Polls until the browser is exactly on the post AND its article is there (feed articles never count). */
    private suspend fun waitOnPost(spec: ReplyComposerSpec, c: StatusUrl.Canon, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val p = probePost(spec, c)
            if (p != null && !StatusUrl.needsNavigation(p.first, c) && p.second) return true
            if (System.currentTimeMillis() >= deadline) {
                if (p?.first != null && config.sessionExpiredUrlPatterns.any { p.first!!.contains(it) })
                    throw SessionExpiredException(config.site, "${config.displayName} redirected to the login page (${p.first})")
                return false
            }
            delay(FastScrape.POLL_MS * 2)
        }
    }

    /** Before a retry: strays closed, else back to a clean post page. */
    private suspend fun closeStrayDialogs(spec: ReplyComposerSpec, log: (String) -> Unit, postUrl: String? = null) {
        val left = cleanStray(spec, log)
        if (postUrl != null && left.isNotEmpty()) ensureCleanPost(spec, postUrl, log)
    }

    private suspend fun clearBox(css: String) {
        runCatching { evalString("""(()=>{const e=document.querySelector(${js(css)});const t=e&&(e.querySelector('[contenteditable="true"]')||e);if(!t)return '';t.focus();document.execCommand('selectAll');document.execCommand('delete');return 'cleared'})()""") }
    }

    private sealed interface Confirm {
        data class Ok(val how: String, val link: String?) : Confirm
        data object SaveSheet : Confirm
        data object Timeout : Confirm
    }

    /**
     * After the submit click: toast (its View link), or our composer closed / EMPTIED — but a "Save post?" sheet means
     * the reply was not sent (v1.0.5 counted a truncated box, e.g. "testing...", as "cleared").
     */
    private suspend fun confirmReply(spec: ReplyComposerSpec, pick: ComposerPick.Found, text: String, log: (String) -> Unit): Confirm {
        val probe = """(()=>{const t=document.querySelector(${js(spec.toast)});const b=document.querySelector(${js(pick.box)});
            const a=t&&t.querySelector('a[href*="/status/"]');
            return JSON.stringify({toast:t?(t.innerText||''):null,link:a?a.href:null,box:b?(b.innerText||''):null,save:${SubmitGuard.saveSheetExpr(spec.stray)}})})()""".trimIndent()
        val err = spec.errorToast?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val deadline = System.currentTimeMillis() + 20_000
        var closedSince: Long? = null
        while (System.currentTimeMillis() < deadline) {
            val o = runCatching { evalString(probe)?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
            if (o?.get("save")?.jsonPrimitive?.booleanOrNull == true) return Confirm.SaveSheet
            val toast = o?.get("toast")?.jsonPrimitive?.contentOrNull
            if (toast != null && err?.containsMatchIn(toast) == true) { log("error toast: $toast"); throw replyFailure("X refused the reply: $toast", listOf()) }
            if (toast != null) { log("toast: ${toast.take(80)}"); return Confirm.Ok("toast", o["link"]?.jsonPrimitive?.contentOrNull) }
            val box = o?.get("box")
            val gone = o != null && (box == null || box is JsonNull)
            val emptied = o != null && !gone && SubmitGuard.isEmpty(box?.jsonPrimitive?.contentOrNull)
            if (gone || emptied) {
                // A closing dialog can still turn into the Save sheet: accept after it stays so for ~1 s.
                val now = System.currentTimeMillis()
                val since = closedSince ?: now.also { closedSince = it }
                if (now - since >= 1_000) return Confirm.Ok(if (gone) "composer closed" else "composer emptied", null)
            } else closedSince = null
            delay(FastScrape.POLL_MS * 2)
        }
        return Confirm.Timeout
    }

    /** The new reply by the logged-in account in the conversation (reloads the post once if needed). */
    private suspend fun findOwnReply(postUrl: String, text: String): String? {
        if (config.replies == null) return null
        val needle = text.trim().replace(Regex("\\s+"), " ").take(40)
        repeat(2) { round ->
            if (round == 1) runCatching { navigateTo(postUrl, if (StatusUrl.canonical(postUrl)?.user == "i") 8_000 else 20_000) }
            delay(1_500)
            val r = runCatching { scrapeReplies(postUrl, 40, maxScrolls = 2, navigate = false) }.getOrNull() ?: return@repeat
            r.replies.firstOrNull { it["is_self"]?.jsonPrimitive?.booleanOrNull == true &&
                (it["text"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(Regex("\\s+"), " ").contains(needle)) }
                ?.let { return it["url"]?.jsonPrimitive?.contentOrNull }
        }
        return null
    }

    private suspend fun replyFailure(why: String, steps: List<String>): ReplyFailedException {
        val d = runCatching { diagnostics() }.getOrNull()
        return ReplyFailedException(why + (d?.url?.let { " (page: $it)" } ?: ""), d, steps)
    }

    suspend fun post(text: String) {
        config.reply?.let { spec ->
            // Same leftover-dialog cleanup as x_reply; a stuck overlay → hard navigation home.
            if (cleanStray(spec).isNotEmpty()) {
                runCatching { bridge.eval("location.replace(${js(config.url("home"))})") }
                delay(2_000)
                cleanStray(spec)
            }
        }
        submitClicked = false
        val done = withTimeoutOrNull(POST_BUDGET_MS) { shielded { runSteps(config.postSteps, mapOf("text" to text)); true } }
        if (done == null) throw AutomationException("x_post timed out after ${POST_BUDGET_MS / 1000} s" +
            if (submitClicked) " after the Post click: check the profile before posting again (no blind retry)" else "; nothing was posted")
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
        const val REPLY_BUDGET_MS = 60_000L
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
