package com.farrow.app.ui.social

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.FallbackBrowser
import com.farrow.app.data.social.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.BitmapFactory
import javax.inject.Inject

data class ReloginState(
    val site: String,
    val displayName: String,
    val busy: Boolean = false,
    val status: LoginStatus? = null,
    val bridgeUp: Boolean? = null,
    /** When [status] was determined (last known status is shown on open; checks only on Check). */
    val checkedAt: Long? = null,
    val message: String? = null,
    val selectorsOverridden: Boolean = false,
    val selectorsVersion: Int = 0,
    /** Current step while busy, e.g. "Step 4/14: click Next". */
    val progress: String? = null,
    /** Persistent failure details (not a snackbar): the step/selector that failed plus diagnostics. */
    val error: String? = null,
    val diagUrl: String? = null,
    val diagText: String? = null,
    /** Cookies TBP's Firefox has after a WebView import (name, domain, path, expiry, present/missing), with Copy. */
    val cookieReport: String? = null,
    val diagScreenshot: android.graphics.Bitmap? = null,
    val diagScreenshotError: String? = null,
    /** The cookie import left the TBP daemon down: show the daemon.log tail + a Start browser button. */
    val daemonDown: Boolean = false,
    val daemonLog: String? = null,
    /** Check step 2 (opening the home page) runs in the background; the UI isn't blocked. */
    val verifying: Boolean = false,
    /** Timings of the last Check, e.g. "bridge 0.04 s · cookies 0.31 s · home page 5.8 s · total 6.2 s". */
    val checkTimings: String? = null,
)

/** Re-login flow for one site (route arg "site"): credential login or cookie import through the bridge. */
@HiltViewModel
class ReloginViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: SelectorStore,
    private val bridge: BridgeClient,
    private val guard: SessionGuard,
    private val fallback: FallbackBrowser,
    private val autoStarter: com.farrow.app.data.browser.BridgeAutoStarter,
    val prefs: com.farrow.app.data.prefs.AppPrefs,
    private val lastStatus: LastStatusStore,
) : ViewModel() {
    /** Running vs. bundled bridge version → "Bridge outdated, tap to update" banner. */
    val bridgeUpdate = autoStarter.updateState
    fun checkBridgeVersion() { viewModelScope.launch { runCatching { autoStarter.checkVersion() } } }
    fun updateBridge() { viewModelScope.launch { runCatching { autoStarter.updateIfOutdated(force = true) } } }

    private val site: String = savedState.get<String>("site") ?: SelectorStore.X
    /** Opened from a chat's Re-login button: launch the WebView login right away. */
    val autoWebLogin: Boolean = savedState.get<Boolean>("web") == true
    private val config get() = store.get(site)
    private val _state = MutableStateFlow(ReloginState(site, config.displayName,
        selectorsOverridden = store.isOverridden(site), selectorsVersion = config.version))
    val state: StateFlow<ReloginState> = _state.asStateFlow()

    val cookieNames: List<String> get() = config.loginCookies

    // No automatic session check or scan on open: show the last known status; the user taps Check.
    init {
        lastStatus.get(site)?.let { k -> _state.update { it.copy(status = k.status, checkedAt = k.checkedAt) } }
    }

    private var job: Job? = null
    @Volatile private var loggedInHandle: String? = null

    /** The WebView login exists for this site (X, Facebook). */
    val webLoginSpec: WebLoginSpec? get() = WebLoginCookies.spec(site)

    /** Result of [com.farrow.app.ui.social.WebLoginActivity]: import ALL captured cookies into TBP and verify. */
    fun importFromWebView(header: String?) {
        val spec = webLoginSpec ?: return
        if (header == null) { _state.update { it.copy(message = "Login cancelled") }; return }
        val cookies = WebLoginCookies.parseHeader(header)
        val missing = WebLoginCookies.missing(cookies, spec)
        if (missing.isNotEmpty()) { _state.update { it.copy(error = "Login incomplete: missing ${missing.joinToString()} cookie(s).") }; return }
        loggedInHandle = null
        if (_state.value.busy) { job?.cancel(); _state.update { it.copy(busy = false, progress = null) } }
        work("Importing ${cookies.size} cookies from the login…") {
            val auto = SocialAutomation(config, bridge)
            val st = try {
                auto.importBrowserCookies(WebLoginCookies.toImport(cookies, spec), spec.origin, ::progress)
            } finally {
                _state.update { it.copy(cookieReport = auto.lastCookieReport) }
            }
            if (st.state == LoginState.LOGGED_IN && site == SelectorStore.X) {
                loggedInHandle = runCatching { withTimeoutOrNull(10_000) { auto.currentHandle() } }.getOrNull()
                    ?: WebLoginCookies.xUserId(cookies["twid"])?.let { "id $it" }
            }
            st
        }
    }

    private fun progress(i: Int, n: Int, what: String) = _state.update { it.copy(progress = "Step $i/$n: $what") }

    private fun work(label: String, block: suspend () -> LoginStatus?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, progress = label, error = null, diagUrl = null, diagText = null, cookieReport = null,
            diagScreenshot = null, diagScreenshotError = null, daemonDown = false, daemonLog = null) }
        job = viewModelScope.launch {
            val up = withTimeoutOrNull(25_000) { bridge.isAvailable() } == true
            if (!up) {
                _state.update { it.copy(busy = false, progress = null, bridgeUp = false,
                    error = "Bridge not running — finish Settings > Internal browser setup (Connect / Re-check) first.") }
                return@launch
            }
            val result = try {
                Result.success(withTimeout(OVERALL_TIMEOUT_MS) { block() })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AutomationException("Gave up after ${OVERALL_TIMEOUT_MS / 1000} s (${_state.value.progress ?: label})"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update { s ->
                result.fold(
                    { st ->
                        if (st?.state == LoginState.LOGGED_IN) guard.clearSite(site)
                        val now = System.currentTimeMillis()
                        if (st != null) lastStatus.put(site, st, now)
                        val text = when (st?.state) {
                            LoginState.LOGGED_IN -> loggedInHandle?.let { h -> if (h.startsWith("id ")) "✅ Logged in ($h). Session saved." else "✅ Logged in as @$h. Session saved." }
                                ?: "✅ Logged in. Session saved. Go back and tap Continue."
                            LoginState.LOGGED_OUT -> null
                            LoginState.UNKNOWN -> null
                            null -> null
                        }
                        s.copy(busy = false, progress = null, bridgeUp = true, status = st ?: s.status, message = text,
                            checkedAt = if (st != null) now else s.checkedAt,
                            error = when (st?.state) {
                                LoginState.LOGGED_OUT -> if (label == CHECK_LABEL) null else "Still logged out: ${st.reason}"
                                LoginState.UNKNOWN -> "Couldn't confirm the login state: ${st.reason}"
                                else -> null
                            })
                    },
                    { e ->
                        val d = (e as? LoginFailedException)?.diagnostics
                        val down = e as? BrowserDownException
                        s.copy(busy = false, progress = null, bridgeUp = true,
                            daemonDown = down != null, daemonLog = down?.daemonLog?.takeIf { it.isNotBlank() },
                            error = e.message ?: e.javaClass.simpleName,
                            diagUrl = d?.url, diagText = d?.pageText?.take(1500),
                            diagScreenshot = d?.screenshotPng?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() },
                            diagScreenshotError = d?.screenshotError)
                    },
                )
            }
        }
    }

    /** Cancel button on the progress card: cancels the coroutine, which also cancels the in-flight bridge HTTP call. */
    fun cancel() {
        job?.cancel(); job = null; verifyJob?.cancel()
        _state.update { it.copy(busy = false, verifying = false, progress = null, error = "Cancelled.") }
    }

    private var verifyJob: Job? = null

    /**
     * Fast Check: (1) cookies.sqlite copy + history URL via site_status — no ready-wait, no eval, < 1 s — shown at once;
     * (2) in the background, only if the TBP daemon is idle: open the home URL (keyboard nav) and see where it lands.
     * Old bridges fall back to the previous restore + status path.
     */
    fun check() {
        if (_state.value.busy) return
        verifyJob?.cancel()
        _state.update { it.copy(busy = true, verifying = false, progress = CHECK_LABEL, error = null, message = null, checkTimings = null,
            diagUrl = null, diagText = null, diagScreenshot = null, diagScreenshotError = null) }
        verifyJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            fun secs(ms: Long) = "%.2f s".format(ms / 1000.0)
            val timings = mutableListOf<String>()
            val h = runCatching { withTimeoutOrNull(5_000) { bridge.health() } }.getOrNull()
            if (h?.bridgeOk != true) {
                _state.update { it.copy(progress = "Bridge not answering — starting it…") }
                if (withTimeoutOrNull(25_000) { bridge.isAvailable() } != true) {
                    _state.update { it.copy(busy = false, progress = null, bridgeUp = false,
                        error = "Bridge not running — finish Settings > Internal browser setup (Connect / Re-check) first.") }
                    return@launch
                }
            }
            timings += "bridge ${secs(System.currentTimeMillis() - t0)}"
            val auto = SocialAutomation(config, bridge)
            val t1 = System.currentTimeMillis()
            val fc = try { auto.fastCheck() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(busy = false, progress = null, error = "Check failed: ${e.message}", checkTimings = timings.joinToString(" · ")) }
                return@launch
            }
            if (fc == null) { // bridge < 1.7.0
                _state.update { it.copy(busy = false) }
                work(CHECK_LABEL) { auto.restoreSession() }
                return@launch
            }
            timings += "cookies ${secs(System.currentTimeMillis() - t1)}" + (fc.bridgeMs?.let { " (bridge ${secs(it)})" } ?: "")
            val first = if (fc.status.state == LoginState.LOGGED_IN)
                fc.status.copy(reason = "cookies present (${config.loginCookies.joinToString()})" + (fc.status.url?.let { "; last page $it" } ?: ""))
                else fc.status
            val canVerify = first.state != LoginState.LOGGED_OUT && fc.busy != null && fc.busy.isEmpty()
            publishCheck(first, timings, verifying = canVerify, t0 = t0)
            if (!canVerify) {
                if (first.state != LoginState.LOGGED_OUT) {
                    timings += if (fc.busy.isNullOrEmpty()) "page check skipped" else "page check skipped (browser busy: ${fc.busy.joinToString()})"
                    publishCheck(first, timings, verifying = false, t0 = t0)
                }
                return@launch
            }
            val t2 = System.currentTimeMillis()
            val verified = try { auto.verifyOnHome().first } catch (e: CancellationException) { throw e } catch (e: Exception) {
                LoginStatus(LoginState.UNKNOWN, first.url, first.cookies, "page check failed: ${e.message}")
            }
            timings += "home page ${secs(System.currentTimeMillis() - t2)}"
            // An inconclusive page check keeps the cookie verdict.
            val final = if (verified.state == LoginState.UNKNOWN) first.copy(reason = first.reason + "; " + verified.reason) else verified
            publishCheck(final, timings, verifying = false, t0 = t0)
        }
    }

    private fun publishCheck(st: LoginStatus, timings: List<String>, verifying: Boolean, t0: Long) {
        val now = System.currentTimeMillis()
        if (st.state == LoginState.LOGGED_IN) guard.clearSite(site)
        lastStatus.put(site, st, now)
        _state.update { it.copy(busy = false, progress = null, bridgeUp = true, status = st, checkedAt = now, verifying = verifying,
            checkTimings = (timings + "total %.2f s".format((now - t0) / 1000.0)).joinToString(" · ") + if (verifying) " · checking the home page…" else "",
            error = if (st.state == LoginState.UNKNOWN && !verifying) "Couldn't confirm the login state: ${st.reason}" else null) }
    }

    fun loginWithCredentials(username: String, password: String, code: String, challenge: String = "") =
        work("Logging in…") { SocialAutomation(config, bridge).loginWithCredentials(username.trim(), password, code.trim(), challenge.trim(), ::progress) }

    /** [pasted]: header / JSON / Netscape export; [fields]: the individual auth_token / ct0 boxes (override pasted). */
    fun loginWithCookies(pasted: String, fields: Map<String, String>) {
        val parsed = parseCookies(pasted) + fields.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }
        val missing = config.loginCookies.filter { parsed[it].isNullOrBlank() }
        if (parsed.isEmpty() || (missing.isNotEmpty() && missing.size == config.loginCookies.size)) {
            _state.update { it.copy(error = "No ${config.loginCookies.joinToString(" / ")} cookie found in what you pasted.") }
            return
        }
        work("Importing ${parsed.size} cookies…") { SocialAutomation(config, bridge).loginWithCookies(parsed, ::progress) }
    }

    fun parseCookies(pasted: String): Map<String, String> =
        CookieParser.parse(pasted, listOf(config.domain, "twitter.com", "facebook.com").filter { it.isNotBlank() }, config.loginCookies)

    fun openInCustomTab() = runCatching { fallback.openForUser(config.url("login")) }
        .onFailure { e -> _state.update { it.copy(message = "Can't open browser: ${e.message}") } }

    fun updateSelectors(raw: String) {
        runCatching { store.update(site, raw) }
            .onSuccess { cfg -> _state.update { it.copy(selectorsOverridden = true, selectorsVersion = cfg.version, message = "Selectors updated (v${cfg.version})") } }
            .onFailure { e -> _state.update { it.copy(message = "Invalid selectors file: ${e.message}") } }
    }

    fun resetSelectors() {
        store.resetToBundled(site)
        _state.update { it.copy(selectorsOverridden = false, selectorsVersion = config.version, message = "Bundled selectors restored") }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** "Start browser" on the daemon-down error: forced single-flight start, then report the result. */
    fun startBrowser() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = "Starting the internal browser…") }
        job = viewModelScope.launch {
            val r = runCatching { autoStarter.ensureDaemon(timeoutMs = 60_000, force = true) { p -> _state.update { it.copy(progress = p) } } }
            _state.update { s ->
                val res = r.getOrNull()
                if (res?.running == true) s.copy(busy = false, progress = null, error = null, daemonDown = false, daemonLog = null,
                    message = "Internal browser running. Check the session or import again.")
                else s.copy(busy = false, progress = null, daemonDown = true,
                    error = "The internal browser still doesn't start: ${res?.message ?: r.exceptionOrNull()?.message}",
                    daemonLog = res?.log?.takeIf { it.isNotBlank() } ?: s.daemonLog)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(daemonDown = false, daemonLog = null, error = null, diagUrl = null, diagText = null, diagScreenshot = null, diagScreenshotError = null) }

    companion object {
        private const val CHECK_LABEL = "Checking session…"
        private const val OVERALL_TIMEOUT_MS = 4 * 60_000L
    }
}
