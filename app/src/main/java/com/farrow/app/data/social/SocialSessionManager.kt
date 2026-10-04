package com.farrow.app.data.social

import android.content.Context
import com.farrow.app.data.browser.BridgeAutoStarter
import com.farrow.app.data.browser.BridgeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a session job does (drives the "Importing X login…" notification text). */
enum class SessionJobKind { IMPORT, CHECK, LOGIN, BROWSER }

/**
 * Progress + last result of the account jobs for one site. Owned by [SocialSessionManager] (app scope), observed by the
 * screen. Everything except [status] is persisted, so a reopened screen shows the last result.
 */
@Serializable
data class SocialSessionState(
    val busy: Boolean = false,
    /** Check step 2 (opening the home page) runs in the background; the UI isn't blocked. */
    val verifying: Boolean = false,
    val kind: SessionJobKind? = null,
    val label: String? = null,
    /** Current step while busy, e.g. "Step 1/2: write 9 cookies…". */
    val progress: String? = null,
    @Transient val status: LoginStatus? = null,
    val checkedAt: Long? = null,
    val bridgeUp: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val diagUrl: String? = null,
    val diagText: String? = null,
    val diagScreenshotPath: String? = null,
    val diagScreenshotError: String? = null,
    val cookieReport: String? = null,
    val daemonDown: Boolean = false,
    val daemonLog: String? = null,
    val checkTimings: String? = null,
    val finishedAt: Long? = null,
    /** Bridge job id of a running cookie import (lets the app wait for it again after being killed). */
    val pendingJob: String? = null,
    val pendingCount: Int = 0,
) {
    val active: Boolean get() = busy || verifying

    /** Fresh-start state for a new job: keeps status/checkedAt, drops the previous result. */
    fun startJob(kind: SessionJobKind, label: String) = copy(busy = true, verifying = false, kind = kind, label = label, progress = label,
        message = null, error = null, diagUrl = null, diagText = null, diagScreenshotPath = null, diagScreenshotError = null,
        cookieReport = null, daemonDown = false, daemonLog = null, checkTimings = null)

    /** After a process restart nothing can still be running in this process. */
    fun restored() = copy(busy = false, verifying = false, progress = null)
}

/** Persistence of [SocialSessionState] per site. */
interface SessionResultStore {
    fun load(site: String): SocialSessionState?
    fun save(site: String, state: SocialSessionState)
}

@Singleton
class PrefsSessionResultStore @Inject constructor(@ApplicationContext context: Context) : SessionResultStore {
    private val prefs = context.getSharedPreferences("social_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    override fun load(site: String) = prefs.getString(site, null)?.let { runCatching { json.decodeFromString<SocialSessionState>(it) }.getOrNull() }
    override fun save(site: String, state: SocialSessionState) { prefs.edit().putString(site, json.encodeToString(SocialSessionState.serializer(), state)).apply() }
}

/**
 * The lifecycle part of [SocialSessionManager], free of Android/bridge types so it is unit-testable: one job per site
 * in an app-lifetime [scope] (never a ViewModel scope), per-site [StateFlow]s, persistence, and an [active] map that
 * drives the foreground notification. Only [cancel] cancels a job.
 */
class SocialJobRunner(
    private val scope: CoroutineScope,
    private val store: SessionResultStore,
    private val initialStatus: (String) -> KnownStatus? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<SocialSessionState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _active = MutableStateFlow<Map<String, SocialSessionState>>(emptyMap())
    /** Sites with a running job (busy or verifying) → their state. */
    val active: StateFlow<Map<String, SocialSessionState>> = _active.asStateFlow()

    private fun flow(site: String) = flows.getOrPut(site) {
        val saved = store.load(site)?.restored() ?: SocialSessionState()
        val known = initialStatus(site)
        MutableStateFlow(if (known != null) saved.copy(status = known.status, checkedAt = known.checkedAt) else saved)
    }

    fun state(site: String): StateFlow<SocialSessionState> = flow(site).asStateFlow()
    fun current(site: String): SocialSessionState = flow(site).value
    fun isRunning(site: String) = jobs[site]?.isActive == true

    fun update(site: String, f: (SocialSessionState) -> SocialSessionState) {
        val f0 = flow(site)
        f0.update(f)
        val s = f0.value
        store.save(site, s)
        _active.update { m -> if (s.active) m + (site to s) else m - site }
    }

    /** Terminal result: stamps [SocialSessionState.finishedAt]. */
    fun finish(site: String, f: (SocialSessionState) -> SocialSessionState) =
        update(site) { f(it).copy(busy = false, progress = null, finishedAt = clock()) }

    /** Starts [block] unless a job is already running for [site]. Returns false when ignored. */
    fun launch(site: String, kind: SessionJobKind, label: String, block: suspend () -> Unit): Boolean {
        if (isRunning(site) || current(site).busy) return false
        update(site) { it.startJob(kind, label) }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(site) { it.copy(verifying = false, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                // Never leave a spinner behind (e.g. a block that returned without publishing a result).
                if (current(site).busy && jobs[site] == coroutineContext[Job]) finish(site) { it }
            }
        }
        jobs[site] = job
        job.invokeOnCompletion { jobs.remove(site, job) }
        job.start()
        return true
    }

    /**
     * A new user action replaces a mere session Check (incl. its background page check); anything else keeps running.
     * Returns true when [site] is free afterwards.
     */
    suspend fun preemptCheck(site: String): Boolean {
        val s = current(site)
        if (isRunning(site) && s.kind == SessionJobKind.CHECK) {
            jobs.remove(site)?.let { j -> j.cancel(); j.join() }
            update(site) { it.copy(busy = false, verifying = false, progress = null) }
        }
        return !isRunning(site) && !current(site).busy
    }

    /** Cancel button: the only way a job is cancelled. */
    fun cancel(site: String) {
        jobs.remove(site)?.cancel()
        val pending = current(site).pendingJob
        finish(site) {
            it.copy(verifying = false, pendingJob = null, error = if (pending != null)
                "Cancelled. The internal browser still finishes writing the cookies and restarts by itself; tap Check in a moment."
                else "Cancelled.")
        }
    }
}

/**
 * App-scoped owner of the X/Facebook account jobs (cookie import from the WebView login, Check, credential login,
 * pasted-cookie import, Start browser). Runs them in its own SupervisorJob scope so leaving the screen or backgrounding
 * the app doesn't cancel them; [SocialSessionService] keeps the process in the foreground while any runs.
 */
@Singleton
class SocialSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SelectorStore,
    private val bridge: BridgeClient,
    private val guard: SessionGuard,
    private val autoStarter: BridgeAutoStarter,
    private val lastStatus: LastStatusStore,
    results: PrefsSessionResultStore,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner = SocialJobRunner(appScope, results, lastStatus::get)
    val active: StateFlow<Map<String, SocialSessionState>> get() = runner.active

    init {
        appScope.launch { runner.active.collect { if (it.isNotEmpty()) SocialSessionService.start(context) } }
    }

    fun state(site: String): StateFlow<SocialSessionState> = runner.state(site)
    fun displayName(site: String) = store.get(site).displayName
    fun cancel(site: String) = runner.cancel(site)
    fun cancelAll() = runner.active.value.keys.forEach(runner::cancel)
    fun consumeMessage(site: String) = runner.update(site) { it.copy(message = null) }
    fun dismissError(site: String) = runner.update(site) { it.copy(daemonDown = false, daemonLog = null, error = null, diagUrl = null,
        diagText = null, diagScreenshotPath = null, diagScreenshotError = null) }
    fun setError(site: String, error: String) = runner.update(site) { it.copy(error = error) }
    fun setMessage(site: String, message: String) = runner.update(site) { it.copy(message = message) }

    private fun progress(site: String) = { i: Int, n: Int, what: String -> runner.update(site) { it.copy(progress = "Step $i/$n: $what") } }

    /** Import ALL cookies captured by the WebView login into TBP's Firefox (one atomic bridge job) and verify. */
    fun importFromWebView(site: String, cookies: Map<String, String>, spec: WebLoginSpec) {
        appScope.launch { if (runner.preemptCheck(site)) importNow(site, cookies, spec) }
    }

    private fun importNow(site: String, cookies: Map<String, String>, spec: WebLoginSpec) {
        val config = store.get(site)
        var handle: String? = null
        work(site, SessionJobKind.IMPORT, "Importing ${cookies.size} cookies from the login…", { handle }) {
            val auto = SocialAutomation(config, bridge)
            val st = try {
                auto.importBrowserCookies(WebLoginCookies.toImport(cookies, spec), spec.origin, progress(site)) { job ->
                    runner.update(site) { it.copy(pendingJob = job, pendingCount = cookies.size) }
                }
            } finally {
                runner.update(site) { it.copy(cookieReport = auto.lastCookieReport, pendingJob = null) }
            }
            if (st.state == LoginState.LOGGED_IN && site == SelectorStore.X) {
                handle = runCatching { withTimeoutOrNull(10_000) { auto.currentHandle() } }.getOrNull()
                    ?: WebLoginCookies.xUserId(cookies["twid"])?.let { "id $it" }
            }
            st
        }
    }

    /** The app was killed while an import job ran in the bridge: wait for that job and show its result. */
    fun resumePendingImport(site: String) {
        val s = runner.current(site)
        val job = s.pendingJob ?: return
        if (runner.isRunning(site)) return
        val config = store.get(site)
        work(site, SessionJobKind.IMPORT, "Finishing the cookie import…") {
            val auto = SocialAutomation(config, bridge)
            try { auto.resumeImport(job, s.pendingCount, progress(site)) }
            finally { runner.update(site) { it.copy(cookieReport = auto.lastCookieReport, pendingJob = null) } }
        }
    }

    fun loginWithCredentials(site: String, username: String, password: String, code: String, challenge: String) =
        work(site, SessionJobKind.LOGIN, "Logging in…") {
            SocialAutomation(store.get(site), bridge).loginWithCredentials(username.trim(), password, code.trim(), challenge.trim(), progress(site))
        }

    fun loginWithCookies(site: String, parsed: Map<String, String>) =
        work(site, SessionJobKind.IMPORT, "Importing ${parsed.size} cookies…") {
            SocialAutomation(store.get(site), bridge).loginWithCookies(parsed, progress(site))
        }

    /** "Start browser" on the daemon-down error: forced single-flight start, then report the result. */
    fun startBrowser(site: String) {
        runner.launch(site, SessionJobKind.BROWSER, "Starting the internal browser…") {
            val r = runCatching { autoStarter.ensureDaemon(timeoutMs = 60_000, force = true) { p -> runner.update(site) { it.copy(progress = p) } } }
            val res = r.getOrNull()
            runner.finish(site) { s ->
                if (res?.running == true) s.copy(error = null, daemonDown = false, daemonLog = null,
                    message = "Internal browser running. Check the session or import again.")
                else s.copy(daemonDown = true, error = "The internal browser still doesn't start: ${res?.message ?: r.exceptionOrNull()?.message}",
                    daemonLog = res?.log?.takeIf { it.isNotBlank() } ?: s.daemonLog)
            }
        }
    }

    /**
     * Fast Check: (1) cookies.sqlite copy + history URL via site_status — < 1 s — shown at once; (2) in the background,
     * only if the TBP daemon is idle: open the home URL and see where it lands. Old bridges use restore + status.
     */
    fun check(site: String) {
        appScope.launch { if (runner.preemptCheck(site)) checkNow(site) }
    }

    private fun checkNow(site: String) {
        val config = store.get(site)
        runner.launch(site, SessionJobKind.CHECK, CHECK_LABEL) {
            val t0 = System.currentTimeMillis()
            fun secs(ms: Long) = "%.2f s".format(ms / 1000.0)
            val timings = mutableListOf<String>()
            val h = runCatching { withTimeoutOrNull(5_000) { bridge.health() } }.getOrNull()
            if (h?.bridgeOk != true) {
                runner.update(site) { it.copy(progress = "Bridge not answering — starting it…") }
                if (withTimeoutOrNull(25_000) { bridge.isAvailable() } != true) {
                    runner.finish(site) { it.copy(bridgeUp = false, error = BRIDGE_DOWN) }
                    return@launch
                }
            }
            timings += "bridge ${secs(System.currentTimeMillis() - t0)}"
            val auto = SocialAutomation(config, bridge)
            val t1 = System.currentTimeMillis()
            val fc = try { auto.fastCheck() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                runner.finish(site) { it.copy(error = "Check failed: ${e.message}", checkTimings = timings.joinToString(" · ")) }
                return@launch
            }
            if (fc == null) { // bridge < 1.7.0
                runWork(site, CHECK_LABEL, { null }) { auto.restoreSession() }
                return@launch
            }
            timings += "cookies ${secs(System.currentTimeMillis() - t1)}" + (fc.bridgeMs?.let { " (bridge ${secs(it)})" } ?: "")
            val first = if (fc.status.state == LoginState.LOGGED_IN)
                fc.status.copy(reason = "cookies present (${config.loginCookies.joinToString()})" + (fc.status.url?.let { "; last page $it" } ?: ""))
                else fc.status
            val canVerify = first.state != LoginState.LOGGED_OUT && fc.busy != null && fc.busy.isEmpty()
            publishCheck(site, first, timings, verifying = canVerify, t0 = t0)
            if (!canVerify) {
                if (first.state != LoginState.LOGGED_OUT) {
                    timings += if (fc.busy.isNullOrEmpty()) "page check skipped" else "page check skipped (browser busy: ${fc.busy.joinToString()})"
                    publishCheck(site, first, timings, verifying = false, t0 = t0)
                }
                return@launch
            }
            val t2 = System.currentTimeMillis()
            val verified = try { auto.verifyOnHome().first } catch (e: CancellationException) { throw e } catch (e: Exception) {
                LoginStatus(LoginState.UNKNOWN, first.url, first.cookies, "page check failed: ${e.message}")
            }
            timings += "home page ${secs(System.currentTimeMillis() - t2)}"
            val final = if (verified.state == LoginState.UNKNOWN) first.copy(reason = first.reason + "; " + verified.reason) else verified
            publishCheck(site, final, timings, verifying = false, t0 = t0)
        }
    }

    private fun publishCheck(site: String, st: LoginStatus, timings: List<String>, verifying: Boolean, t0: Long) {
        val now = System.currentTimeMillis()
        if (st.state == LoginState.LOGGED_IN) guard.clearSite(site)
        lastStatus.put(site, st, now)
        runner.finish(site) { it.copy(bridgeUp = true, status = st, checkedAt = now, verifying = verifying,
            checkTimings = (timings + "total %.2f s".format((now - t0) / 1000.0)).joinToString(" · ") + if (verifying) " · checking the home page…" else "",
            error = if (st.state == LoginState.UNKNOWN && !verifying) "Couldn't confirm the login state: ${st.reason}" else null) }
    }

    private fun work(site: String, kind: SessionJobKind, label: String, handle: () -> String? = { null }, block: suspend () -> LoginStatus?) {
        runner.launch(site, kind, label) { runWork(site, label, handle, block) }
    }

    /** Bridge check, overall timeout, then folds the result/error into the persisted state. */
    private suspend fun runWork(site: String, label: String, handle: () -> String?, block: suspend () -> LoginStatus?) {
        val up = withTimeoutOrNull(25_000) { bridge.isAvailable() } == true
        if (!up) { runner.finish(site) { it.copy(bridgeUp = false, error = BRIDGE_DOWN) }; return }
        val result = try {
            Result.success(withTimeout(OVERALL_TIMEOUT_MS) { block() })
        } catch (e: TimeoutCancellationException) {
            Result.failure(AutomationException("Gave up after ${OVERALL_TIMEOUT_MS / 1000} s (${runner.current(site).progress ?: label})"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.onSuccess { st ->
            val now = System.currentTimeMillis()
            if (st?.state == LoginState.LOGGED_IN) guard.clearSite(site)
            if (st != null) lastStatus.put(site, st, now)
            val h = handle()
            runner.finish(site) { s ->
                s.copy(bridgeUp = true, status = st ?: s.status, checkedAt = if (st != null) now else s.checkedAt,
                    message = if (st?.state == LoginState.LOGGED_IN) loggedInText(h) else null,
                    error = when (st?.state) {
                        LoginState.LOGGED_OUT -> if (label == CHECK_LABEL) null else "Still logged out: ${st.reason}"
                        LoginState.UNKNOWN -> "Couldn't confirm the login state: ${st.reason}"
                        else -> null
                    })
            }
        }.onFailure { e ->
            val d = (e as? LoginFailedException)?.diagnostics
            val down = e as? BrowserDownException
            val shot = d?.screenshotPng?.let { png -> runCatching { diagFile(site).apply { parentFile?.mkdirs(); writeBytes(png) }.absolutePath }.getOrNull() }
            runner.finish(site) { s ->
                s.copy(bridgeUp = true, daemonDown = down != null, daemonLog = down?.daemonLog?.takeIf { it.isNotBlank() },
                    error = e.message ?: e.javaClass.simpleName, diagUrl = d?.url, diagText = d?.pageText?.take(1500),
                    diagScreenshotPath = shot, diagScreenshotError = d?.screenshotError)
            }
        }
    }

    private fun diagFile(site: String) = File(File(context.filesDir, "social"), "$site-diag.png")

    companion object {
        const val CHECK_LABEL = "Checking session…"
        private const val OVERALL_TIMEOUT_MS = 4 * 60_000L
        private const val BRIDGE_DOWN = "Bridge not running — finish Settings > Internal browser setup (Connect / Re-check) first."

        fun loggedInText(handle: String?) = handle?.let { h -> if (h.startsWith("id ")) "✅ Logged in ($h). Session saved." else "✅ Logged in as @$h. Session saved." }
            ?: "✅ Logged in. Session saved. Go back and tap Continue."
    }
}

/** Notification texts for [SocialSessionService] (pure, unit-tested). */
object SessionNotifText {
    fun title(displayName: String, kind: SessionJobKind?) = when (kind) {
        SessionJobKind.IMPORT -> "Importing $displayName login…"
        SessionJobKind.CHECK -> "Checking $displayName session…"
        SessionJobKind.LOGIN -> "Logging in to $displayName…"
        SessionJobKind.BROWSER -> "Starting the internal browser…"
        null -> "Working on $displayName login…"
    }
    fun text(state: SocialSessionState) = state.progress ?: state.label ?: "Working…"
}
