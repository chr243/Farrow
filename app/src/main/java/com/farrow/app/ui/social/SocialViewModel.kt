package com.farrow.app.ui.social

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.FallbackBrowser
import com.farrow.app.data.social.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * Re-login flow for one site (route arg "site"). All jobs (WebView cookie import, Check, credential login, pasted
 * cookies, Start browser) run in the app-scoped [SocialSessionManager]; this ViewModel only observes its state, so
 * leaving the screen never cancels them. Only [cancel] (the Cancel button) does.
 */
@HiltViewModel
class ReloginViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: SelectorStore,
    private val fallback: FallbackBrowser,
    private val autoStarter: com.farrow.app.data.browser.BridgeAutoStarter,
    val prefs: com.farrow.app.data.prefs.AppPrefs,
    private val sessions: SocialSessionManager,
) : ViewModel() {
    /** Running vs. bundled bridge version → "Bridge outdated, tap to update" banner. */
    val bridgeUpdate = autoStarter.updateState
    fun checkBridgeVersion() { viewModelScope.launch { runCatching { autoStarter.checkVersion() } } }
    fun updateBridge() { viewModelScope.launch { runCatching { autoStarter.updateIfOutdated(force = true) } } }

    private val site: String = savedState.get<String>("site") ?: SelectorStore.X
    /** Opened from a chat's Re-login button: launch the WebView login right away. */
    val autoWebLogin: Boolean = savedState.get<Boolean>("web") == true
    private val config get() = store.get(site)

    /** Screen-local bits (selectors file); everything else comes from the manager. */
    private data class Local(val selectorsOverridden: Boolean, val selectorsVersion: Int)
    private val local = MutableStateFlow(Local(store.isOverridden(site), config.version))

    private var shotCache: Pair<String, android.graphics.Bitmap?>? = null
    private fun shot(path: String?): android.graphics.Bitmap? = path?.let { p ->
        shotCache?.takeIf { it.first == p }?.second ?: runCatching { BitmapFactory.decodeFile(p) }.getOrNull().also { shotCache = p to it }
    }

    private fun merge(s: SocialSessionState, l: Local) = ReloginState(site, config.displayName, busy = s.busy, status = s.status,
        bridgeUp = s.bridgeUp, checkedAt = s.checkedAt, message = s.message, selectorsOverridden = l.selectorsOverridden,
        selectorsVersion = l.selectorsVersion, progress = s.progress, error = s.error, diagUrl = s.diagUrl, diagText = s.diagText,
        cookieReport = s.cookieReport, diagScreenshot = shot(s.diagScreenshotPath), diagScreenshotError = s.diagScreenshotError,
        daemonDown = s.daemonDown, daemonLog = s.daemonLog, verifying = s.verifying, checkTimings = s.checkTimings)

    val state: StateFlow<ReloginState> = combine(sessions.state(site), local, ::merge)
        .stateIn(viewModelScope, SharingStarted.Eagerly, merge(sessions.state(site).value, local.value))

    val cookieNames: List<String> get() = config.loginCookies

    // No automatic session check on open: the last known status and the last job result are shown (persisted).
    // An import that was still running in the bridge when the app was killed is waited for again.
    init { sessions.resumePendingImport(site) }

    /** The WebView login exists for this site (X, Facebook). */
    val webLoginSpec: WebLoginSpec? get() = WebLoginCookies.spec(site)

    /** Result of [com.farrow.app.ui.social.WebLoginActivity]: import ALL captured cookies into TBP and verify. */
    fun importFromWebView(header: String?) {
        val spec = webLoginSpec ?: return
        if (header == null) { sessions.setMessage(site, "Login cancelled"); return }
        val cookies = WebLoginCookies.parseHeader(header)
        val missing = WebLoginCookies.missing(cookies, spec)
        if (missing.isNotEmpty()) { sessions.setError(site, "Login incomplete: missing ${missing.joinToString()} cookie(s)."); return }
        sessions.importFromWebView(site, cookies, spec)
    }

    /** Cancel button on the progress card: the only thing that cancels a session job. */
    fun cancel() = sessions.cancel(site)

    fun check() = sessions.check(site)

    fun loginWithCredentials(username: String, password: String, code: String, challenge: String = "") =
        sessions.loginWithCredentials(site, username, password, code, challenge)

    /** [pasted]: header / JSON / Netscape export; [fields]: the individual auth_token / ct0 boxes (override pasted). */
    fun loginWithCookies(pasted: String, fields: Map<String, String>) {
        val parsed = parseCookies(pasted) + fields.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }
        val missing = config.loginCookies.filter { parsed[it].isNullOrBlank() }
        if (parsed.isEmpty() || (missing.isNotEmpty() && missing.size == config.loginCookies.size)) {
            sessions.setError(site, "No ${config.loginCookies.joinToString(" / ")} cookie found in what you pasted.")
            return
        }
        sessions.loginWithCookies(site, parsed)
    }

    fun parseCookies(pasted: String): Map<String, String> =
        CookieParser.parse(pasted, listOf(config.domain, "twitter.com", "facebook.com").filter { it.isNotBlank() }, config.loginCookies)

    fun openInCustomTab() = runCatching { fallback.openForUser(config.url("login")) }
        .onFailure { e -> sessions.setMessage(site, "Can't open browser: ${e.message}") }

    fun updateSelectors(raw: String) {
        runCatching { store.update(site, raw) }
            .onSuccess { cfg -> local.update { it.copy(selectorsOverridden = true, selectorsVersion = cfg.version) }; sessions.setMessage(site, "Selectors updated (v${cfg.version})") }
            .onFailure { e -> sessions.setMessage(site, "Invalid selectors file: ${e.message}") }
    }

    fun resetSelectors() {
        store.resetToBundled(site)
        local.update { it.copy(selectorsOverridden = false, selectorsVersion = config.version) }
        sessions.setMessage(site, "Bundled selectors restored")
    }

    fun consumeMessage() = sessions.consumeMessage(site)

    /** "Start browser" on the daemon-down error: forced single-flight start, then report the result. */
    fun startBrowser() = sessions.startBrowser(site)

    fun dismissError() = sessions.dismissError(site)
}
