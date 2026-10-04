package com.farrow.app.data.browser

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the fast "is everything installed?" probe (steps 2–4). */
data class InstallCheck(
    /** False when Termux didn't answer — usually allow-external-apps is missing (step 1). */
    val termuxAnswered: Boolean,
    val installed: Map<Int, Boolean>,
    val error: String? = null,
) {
    val allInstalled: Boolean get() = termuxAnswered && installed.isNotEmpty() && installed.values.all { it }
    val missing: List<Int> get() = installed.filterValues { !it }.keys.sorted()
}

/** Running bridge vs. bundled script: drives the "Bridge outdated, tap to update" banner. */
data class BridgeUpdateState(
    val running: String?,
    val bundled: String,
    val updating: Boolean = false,
    val message: String? = null,
) {
    val outdated: Boolean get() = BridgeVersions.isOutdated(running, bundled)
}

/**
 * Starts the Termux bridge (step 5 only — never reinstalls) when it is needed and everything is installed:
 * on app launch / boot and when a web_* tool finds the bridge unreachable (waits up to 15 s).
 */
@Singleton
class BridgeAutoStarter @Inject constructor(
    @ApplicationContext context: Context,
    private val termux: TermuxManager,
    private val installer: BridgeInstaller,
    private val bridge: BridgeClient,
) {
    private val prefs = context.getSharedPreferences("browser_bridge", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var lastAttempt = 0L

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) { prefs.edit().putBoolean(KEY_AUTO, v).apply() }

    init {
        bridge.autoStart = { ensureRunning() }
        bridge.bundledVersion = installer.bundledVersion
        bridge.autoUpdate = { updateIfOutdated() }
    }

    private val _update = MutableStateFlow<BridgeUpdateState?>(null)
    /** Non-null once a bridge version was seen; [BridgeUpdateState.outdated] → show the update banner. */
    val updateState: StateFlow<BridgeUpdateState?> = _update.asStateFlow()
    private val updateMutex = Mutex()
    @Volatile private var lastUpdateAttempt = 0L

    /** Refreshes [updateState] from /health (no update). */
    suspend fun checkVersion(): BridgeUpdateState? {
        val bundled = installer.bundledVersion ?: return null
        val h = bridge.health()
        if (!h.bridgeOk) return _update.value
        val cur = _update.value
        val st = BridgeUpdateState(h.version, bundled, updating = cur?.updating == true, message = cur?.message)
        _update.value = st
        return st
    }

    /**
     * Bridge running an older script than the one bundled in the app → rewrite ~/.farrow/tbp_bridge.py and restart
     * the bridge in the background (steps 4 + 5 through Termux, no window), then wait ≤ [timeoutMs] for /health to
     * report the new version. The TBP daemon (Firefox) keeps running. Automatic attempts are throttled
     * ([UPDATE_THROTTLE_MS]); [force] = the banner's "tap to update". Returns true when the bridge is current.
     */
    suspend fun updateIfOutdated(force: Boolean = false, timeoutMs: Long = 45_000): Boolean = updateMutex.withLock {
        val bundled = installer.bundledVersion ?: return true
        val h = bridge.health()
        if (!h.bridgeOk) return false
        if (!BridgeVersions.isOutdated(h.version, bundled)) { _update.value = BridgeUpdateState(h.version, bundled); return true }
        _update.value = BridgeUpdateState(h.version, bundled)
        val now = System.currentTimeMillis()
        if (!force && (!enabled || now - lastUpdateAttempt < UPDATE_THROTTLE_MS)) return false
        if (!termux.isInstalled() || !termux.hasRunCommandPermission()) {
            _update.value = BridgeUpdateState(h.version, bundled, message = "Can't update: Termux RUN_COMMAND permission missing — tap Set up everything")
            return false
        }
        lastUpdateAttempt = now
        Log.i(TAG, "bridge ${h.version} is older than bundled $bundled — updating in the background")
        _update.value = BridgeUpdateState(h.version, bundled, updating = true, message = "Updating the bridge ${h.version ?: "?"} → $bundled…")
        val sent = termux.runCommand(installer.updateBridgeScript(), background = true, label = "Farrow: update bridge", resultTag = "bridge-update")
        if (sent.isFailure) {
            _update.value = BridgeUpdateState(h.version, bundled, message = "Update failed to start: ${sent.exceptionOrNull()?.message}")
            return false
        }
        delay(2_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = h.version
        while (System.currentTimeMillis() < deadline) {
            val hh = bridge.health()
            if (hh.bridgeOk) {
                last = hh.version
                if (!BridgeVersions.isOutdated(hh.version, bundled)) {
                    Log.i(TAG, "bridge updated to ${hh.version}")
                    _update.value = BridgeUpdateState(hh.version, bundled, message = "✅ Bridge updated to ${hh.version}")
                    return true
                }
            }
            delay(1_500)
        }
        _update.value = BridgeUpdateState(last, bundled, message = "Bridge still ${last ?: "unreachable"} after ${timeoutMs / 1000} s — tap to retry or use Set up everything")
        false
    }

    private suspend fun bridgeUp(): Boolean = bridge.health().let { it.bridgeOk && it.tbpInstalled }
    private suspend fun healthy(): Boolean = bridge.health().fullyUp
    @Volatile private var lastDaemonAttempt = 0L

    /** Result of [ensureDaemon]: [log] has tbp.log / daemon.log tails when it failed. */
    data class DaemonResult(val running: Boolean, val log: String, val message: String)

    private val daemonMutex = Mutex()
    @Volatile private var lastDaemonResult: Pair<Long, DaemonResult>? = null

    /**
     * Bridge up but TBP daemon down → POST /daemon/start and poll /health up to [timeoutMs]. Old bridges without the
     * endpoint get the `start` command (tbp start) instead.
     *
     * Single flight: setup, web tools and app launch share one attempt (callers queue on a mutex and then see the
     * daemon running), and a failure is reused for [DEBOUNCE_MS] instead of firing another start right away.
     */
    suspend fun ensureDaemon(timeoutMs: Long = 30_000, force: Boolean = false, onProgress: (String) -> Unit = {}): DaemonResult {
        if (daemonMutex.isLocked) onProgress("Another start of the TBP daemon is in progress — waiting for it…")
        return daemonMutex.withLock {
            val h = bridge.health()
            when {
                !h.bridgeOk -> DaemonResult(false, "", "Bridge not reachable: ${h.error}")
                !h.tbpInstalled -> DaemonResult(false, "", "tbp is not installed (setup step 3)")
                h.daemonRunning -> DaemonResult(true, "", "TBP daemon running")
                else -> {
                    val recent = lastDaemonResult
                    if (!force && recent != null && !recent.second.running && System.currentTimeMillis() - recent.first < DEBOUNCE_MS) recent.second
                    else startDaemonLocked(timeoutMs, onProgress).also { lastDaemonResult = System.currentTimeMillis() to it }
                }
            }
        }
    }

    private suspend fun startDaemonLocked(timeoutMs: Long, onProgress: (String) -> Unit): DaemonResult {
        lastDaemonAttempt = System.currentTimeMillis()
        onProgress("Starting the TBP daemon (Xvfb + Firefox)…")
        val started = runCatching { bridge.startDaemon() }
        if (started.getOrNull() == null && started.isSuccess) {
            // Bridge < 1.2.0: no /daemon/start — fall back to the `start` command.
            runCatching { withTimeoutOrNull(5_000) { bridge.command("start") } }
        }
        Log.i(TAG, "daemon start: ${started.getOrNull() ?: started.exceptionOrNull()?.message}")
        // Bridge ≥ 1.3.0 already waited for the socket; older ones return at once.
        if (started.getOrNull()?.get("running")?.jsonPrimitive?.booleanOrNull == true) return DaemonResult(true, "", "TBP daemon running")
        // Bridge ≥ 1.4.0: a live daemon pid that doesn't listen is never restarted — the user decides (Reset browser).
        started.getOrNull()?.takeIf { it["needs_reset"]?.jsonPrimitive?.booleanOrNull == true }?.let { o ->
            return DaemonResult(false, resultText(o), o["error"]?.jsonPrimitive?.contentOrNull ?: "TBP daemon is alive but not answering — tap Reset browser")
        }
        if (started.getOrNull()?.get("ok")?.jsonPrimitive?.booleanOrNull == false && started.getOrNull()?.containsKey("started") == true) {
            val o = started.getOrNull()!!
            return DaemonResult(false, resultText(o), o["error"]?.jsonPrimitive?.contentOrNull ?: "TBP daemon did not start")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var waited = 0
        while (System.currentTimeMillis() < deadline) {
            if (bridge.health().daemonRunning) return DaemonResult(true, "", "TBP daemon running")
            delay(2_000); waited += 2
            onProgress("Waiting for the TBP daemon… ${waited}s")
        }
        val st = runCatching { bridge.daemonStatus() }.getOrNull()
        val notes = started.getOrNull()?.let(::resultText).orEmpty()
        return DaemonResult(false, (notes + "\n" + st?.logText().orEmpty()).trim().ifBlank { bridge.health().daemonError.orEmpty() },
            "TBP daemon did not start within ${timeoutMs / 1000} s")
    }

    /** "Reset browser": stop the daemon, Firefox and Xvfb, clear the locks, start fresh. [DaemonResult.log] is always set. */
    suspend fun resetDaemon(onProgress: (String) -> Unit = {}): DaemonResult = daemonMutex.withLock {
        val h = bridge.health()
        if (!h.bridgeOk) return@withLock DaemonResult(false, h.error.orEmpty(), "Bridge not reachable — start it first (Start browser)")
        onProgress("Stopping TBP, Firefox and Xvfb, clearing locks, starting fresh…")
        val r = runCatching { bridge.resetDaemon() }
        val o = r.getOrNull()
        val res = when {
            r.isFailure -> DaemonResult(false, r.exceptionOrNull()?.message.orEmpty(), "Reset failed")
            o == null -> DaemonResult(false, "", "This bridge is too old for Reset — tap Set up everything to update it (bridge 1.3.0)")
            else -> {
                val ok = o["running"]?.jsonPrimitive?.booleanOrNull == true
                DaemonResult(ok, resultText(o), if (ok) "✅ Browser reset — TBP daemon running" else "❌ Reset done, but the TBP daemon didn't start")
            }
        }
        lastDaemonResult = System.currentTimeMillis() to res
        res
    }

    /** Readable log for a /daemon/start|reset reply: notes, error, tbp.log / daemon.log tails. */
    private fun resultText(o: JsonObject): String = buildString {
        o["notes"]?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { appendLine("• " + it.jsonPrimitive.content) }
        o["seconds"]?.jsonPrimitive?.contentOrNull?.let { appendLine("Daemon answered after $it s") }
        o["error"]?.jsonPrimitive?.contentOrNull?.let { appendLine("Error: $it") }
        o["tbp_log"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { appendLine("--- ~/.farrow/tbp.log"); appendLine(it) }
        o["daemon_log"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { appendLine("--- ~/.tbp/daemon.log"); appendLine(it) }
        o["firefox_log"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { appendLine("--- ~/.farrow/firefox-probe.log (Firefox stderr)"); appendLine(it) }
    }.trim()

    /** Runs [command] as a background Termux command and waits for its result PendingIntent. */
    suspend fun queryAndWait(command: String, tag: String, timeoutMs: Long = 8_000): TermuxResult? = termux.runAndWait(command, tag, timeoutMs)

    /** Fast check (a few command -v / sha256sum calls, < 1 s): which of steps 2–4 are already done. */
    suspend fun checkInstalled(): InstallCheck {
        if (!termux.isInstalled()) return InstallCheck(false, emptyMap(), "Termux is not installed")
        if (!termux.hasRunCommandPermission()) return InstallCheck(false, emptyMap(), "RUN_COMMAND permission not granted")
        val r = queryAndWait(installer.installedQuery(), "probe-${System.nanoTime()}")
            ?: return InstallCheck(false, emptyMap(), "Termux did not answer — allow-external-apps is probably not enabled (step 1)")
        val keys = StepScripts.parseKeys(r.stdout)
        if (keys["PROBE"] != "ok") {
            return InstallCheck(false, emptyMap(), r.errmsg?.takeIf { it.isNotBlank() } ?: r.stderr.ifBlank { "Termux refused the command (err ${r.err})" })
        }
        val installed = keys.filterKeys { it.startsWith("C") }.mapNotNull { (k, v) -> k.drop(1).toIntOrNull()?.let { it to (v == "1") } }.toMap()
        return InstallCheck(true, installed)
    }

    /** Starts step 5 in the background (no Termux window) if not already healthy; waits up to [timeoutMs]. */
    suspend fun ensureRunning(timeoutMs: Long = 15_000, force: Boolean = false): Boolean = mutex.withLock {
        if (healthy()) return true
        if (bridgeUp()) {
            // Bridge fine, daemon down: only (re)start the daemon.
            if (!force && (!enabled || System.currentTimeMillis() - lastDaemonAttempt < THROTTLE_MS)) return false
            if (!force && bridge.health().daemonUnresponsive) { Log.i(TAG, "daemon pid alive but not listening — not starting another"); return false }
            return ensureDaemon().running
        }
        if (!force && !enabled) return false
        if (!termux.isInstalled() || !termux.hasRunCommandPermission()) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastAttempt < THROTTLE_MS) return false
        lastAttempt = now
        Log.i(TAG, "bridge unreachable — starting it (step 5)")
        if (termux.runCommand(installer.startBridgeScript(), background = true, label = "Farrow: start bridge", resultTag = "autostart").isFailure) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            if (bridgeUp()) {
                Log.i(TAG, "bridge is up")
                runCatching { updateIfOutdated() }
                // Step 5 also launches `tbp start`; make sure the daemon really comes up.
                return ensureDaemon().running
            }
        }
        Log.w(TAG, "bridge did not come up within ${timeoutMs / 1000} s")
        false
    }

    /** App launch / boot: if everything is installed, just start the bridge + daemon (never reinstall). */
    suspend fun startIfInstalled(): Boolean {
        if (bridge.health().bridgeOk) runCatching { updateIfOutdated() }
        if (!enabled || healthy()) return false
        val check = checkInstalled()
        if (!check.allInstalled) { Log.i(TAG, "not auto-starting: ${check.error ?: "missing steps ${check.missing}"}"); return false }
        return ensureRunning(timeoutMs = 20_000)
    }

    private companion object {
        const val TAG = "BridgeAutoStart"
        const val KEY_AUTO = "auto_start"
        const val THROTTLE_MS = 30_000L
        const val DEBOUNCE_MS = 10_000L
        const val UPDATE_THROTTLE_MS = 10 * 60_000L
    }
}
