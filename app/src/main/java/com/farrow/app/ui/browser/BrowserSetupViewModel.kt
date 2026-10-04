package com.farrow.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.data.browser.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject

/** Note under the "Load images" switch (pure, unit-tested). */
object MediaPrefNote {
    fun of(ok: Boolean?, stderr: String?): String = when {
        ok == true -> "Saved. Applies the next time the internal browser starts (no restart is forced)."
        ok == false && stderr.orEmpty().contains("unknown cmd") -> "Saved. Update the bridge (banner above) so the internal browser can apply it."
        else -> "Saved. Applied when the bridge is reachable, at the next browser start."
    }
}

data class StepUi(val state: StepState = StepState.NOT_RUN, val exitCode: Int? = null, val logTail: String = "")

data class BrowserSetupState(
    val termuxInstalled: Boolean = false,
    val runCommandGranted: Boolean = false,
    val health: BridgeHealth? = null,
    val checking: Boolean = false,
    val connectProgress: String? = null,
    val bridgeLogTail: String? = null,
    val bridgeListening: String? = null,
    val steps: Map<Int, StepUi> = emptyMap(),
    val runningStep: Int? = null,
    val logDialogStep: Int? = null,
    val fingerprint: DeviceFingerprintInfo? = null,
    val message: String? = null,
    val install: InstallCheck? = null,
    val setupAll: SetupAllUi? = null,
    val autoStart: Boolean = true,
    /** Setup runs in a visible Termux session instead of in the background. */
    val showTermux: Boolean = false,
    /** "Browser language" (default English): Firefox intl prefs, Accept-Language, search URLs. */
    val browserLanguage: String = com.farrow.app.data.browser.BrowserLanguage.DEFAULT,
    val languageNote: String? = null,
    /** Why the TBP daemon is down (filled by Re-check). */
    val daemonLog: String? = null,
    /** "Reset browser": running flag, progress / result message and the log (with Copy). */
    val resetting: Boolean = false,
    val resetMessage: String? = null,
    val resetLog: String? = null,
    /** v1.0.7: the app-scoped action ([BrowserOpsManager]): running one + last result (persisted). */
    val ops: BrowserOpsState = BrowserOpsState(),
    /** "Load images" (default off): Firefox image/autoplay prefs, applied at the next browser start. */
    val loadImages: Boolean = false,
    val mediaNote: String? = null,
)

@HiltViewModel
class BrowserSetupViewModel @Inject constructor(
    private val config: BridgeConfig,
    private val termux: TermuxManager,
    private val installer: BridgeInstaller,
    private val bridge: BridgeClient,
    private val fingerprint: DeviceFingerprint,
    private val autoStarter: BridgeAutoStarter,
    private val prefs: com.farrow.app.data.prefs.AppPrefs,
    private val ops: BrowserOpsManager,
) : ViewModel() {
    /** Running vs. bundled bridge version → "Bridge outdated, tap to update" banner. */
    val bridgeUpdate = autoStarter.updateState
    fun checkBridgeVersion() { viewModelScope.launch { runCatching { autoStarter.checkVersion() } } }
    /** App-scoped (v1.0.7): keeps running when the screen is left. */
    fun updateBridge() { if (!ops.updateBridge()) busyNote() }

    private fun busyNote() = _state.update { it.copy(message = "${ops.state.value.label ?: "Another browser action"} is still running — wait for it or tap Cancel.") }


    private val _state = MutableStateFlow(BrowserSetupState())
    /** Screen state = local checks + the app-scoped action state ([BrowserOpsManager]). */
    val state: StateFlow<BrowserSetupState> = combine(_state, ops.state) { s, o ->
        s.copy(setupAll = o.setupAll, runningStep = o.runningStep, resetting = o.resetting, resetMessage = o.resetMessage,
            resetLog = o.resetLog, ops = o)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.copy(ops = ops.state.value, setupAll = ops.state.value.setupAll,
        resetMessage = ops.state.value.resetMessage, resetLog = ops.state.value.resetLog))

    val steps: List<SetupStep> get() = installer.steps()
    val bridgePort: Int get() = config.port

    private var connectJob: Job? = null
    private var pendingAllFrom: Int? = null

    init {
        viewModelScope.launch { TermuxResults.results.collect(::onTermuxResult) }
        viewModelScope.launch {
            val fp = withContext(Dispatchers.Default) { runCatching { fingerprint.get() }.getOrNull() }
            _state.update { it.copy(fingerprint = fp) }
        }
        // An action finished (maybe while the screen was closed): refresh the bridge status; step results → snackbar.
        viewModelScope.launch {
            var wasBusy = ops.state.value.busy
            ops.state.collect { o ->
                if (wasBusy && !o.busy) {
                    if (o.lastKind == BrowserOpKind.STEP) _state.update { it.copy(message = o.lastResult) }
                    refreshInstallCheck(); connect()
                }
                wasBusy = o.busy
            }
        }
    }

    /** Called on screen resume: refresh Termux checks, step statuses and re-check the bridge (with retry). */
    fun onResume() {
        refreshChecks()
        refreshStepStatuses()
        connect()
        refreshInstallCheck()
    }

    fun refreshInstallCheck() {
        if (!termux.hasRunCommandPermission()) return
        viewModelScope.launch {
            val c = autoStarter.checkInstalled()
            _state.update { it.copy(install = c) }
        }
    }

    fun setBrowserLanguage(code: String) {
        prefs.setBrowserLanguage(code)
        _state.update { it.copy(browserLanguage = code, languageNote = "Saving…") }
        viewModelScope.launch {
            val r = runCatching { bridge.command("set_language", kotlinx.serialization.json.buildJsonObject { put("lang", kotlinx.serialization.json.JsonPrimitive(code)) }) }.getOrNull()
            val restart = r?.raw?.get("data")?.let { runCatching { it.jsonObject["restart_needed"]?.jsonPrimitive?.booleanOrNull }.getOrNull() } == true
            val note = when {
                r == null || !r.ok -> "Saved. Applied when setup step 5 runs or the bridge is reachable."
                restart -> "Saved. Applies the next time the internal browser starts (Reset browser to apply now)."
                else -> "Saved and applied to the internal browser."
            }
            _state.update { it.copy(languageNote = note) }
        }
    }

    /**
     * "Load images" switch: the bridge (≥ 1.11.0) writes Firefox's image/autoplay prefs to user.js; they apply the next
     * time the internal browser starts — no restart is forced. Until then the page-level filter keeps blocking.
     */
    fun setLoadImages(on: Boolean) {
        prefs.setLoadImages(on)
        _state.update { it.copy(loadImages = on, mediaNote = "Saving…") }
        viewModelScope.launch {
            val r = runCatching { bridge.command("set_media", kotlinx.serialization.json.buildJsonObject { put("load_images", kotlinx.serialization.json.JsonPrimitive(on)) }) }.getOrNull()
            _state.update { it.copy(mediaNote = MediaPrefNote.of(r?.ok, r?.stderr)) }
        }
    }

    fun setShowTermux(on: Boolean) {
        prefs.setShowTermuxDuringSetup(on)
        _state.update { it.copy(showTermux = on) }
    }

    fun setAutoStart(on: Boolean) {
        autoStarter.enabled = on
        _state.update { it.copy(autoStart = on) }
    }

    /**
     * One button: prerequisites → fast install check → either just start the bridge ("Start browser", nothing is
     * reinstalled) or run the missing steps in ONE Termux session (skipping what is done) → Connect.
     * Returns false when the RUN_COMMAND permission must be requested first (the screen launches the request and
     * [onPermissionResult] resumes the run).
     */
    fun setupEverything(from: Int = 2): Boolean {
        if (ops.state.value.setupAll?.running == true) return true
        refreshChecks()
        if (!termux.isInstalled()) {
            ops.showSetupNote(SetupAllUi(error = "Termux is not installed. Install it from F-Droid (not Google Play), open it once, then tap again."))
            return true
        }
        if (!termux.hasRunCommandPermission()) {
            pendingAllFrom = from
            ops.showSetupNote(SetupAllUi(phase = "Requesting the Termux RUN_COMMAND permission…"))
            return false
        }
        if (!ops.setupEverything(from)) busyNote()
        return true
    }

    /** Status card: start only the TBP daemon (app-scoped). */
    fun startDaemon() { if (!ops.startDaemon()) busyNote() }

    /** Status card "Reset browser": a bridge-side job, waited for in the app scope; shows the log with Copy. */
    fun resetBrowser() { if (!ops.resetBrowser()) busyNote() }

    /** Cancel (screen): stops waiting for the running action; bridge/Termux-side work still completes. */
    fun cancelOp() = ops.cancel()
    fun cancelSetupAll() = ops.cancel()
    fun dismissSetupAll() = ops.dismissSetupAll()
    fun dismissLastResult() = ops.dismissLast()

    private fun refreshChecks() {
        _state.update { it.copy(termuxInstalled = termux.isInstalled(), runCommandGranted = termux.hasRunCommandPermission(), autoStart = autoStarter.enabled,
            showTermux = prefs.showTermuxDuringSetup.value, browserLanguage = prefs.browserLanguage.value, loadImages = prefs.loadImages.value) }
    }

    fun refreshStepStatuses() {
        if (!termux.hasRunCommandPermission()) return
        (1..5).forEach { n -> termux.query(StepScripts.statusQuery(n), "status-$n") }
    }

    /** Connect / Re-check: retries /health with backoff for ~10 s, then fetches bridge.log via Termux on failure. */
    fun connect() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(checking = true, bridgeLogTail = null, bridgeListening = null) }
            val delays = listOf(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L) // ≈10.5 s total
            var last: BridgeHealth? = null
            for ((i, d) in delays.withIndex()) {
                if (d > 0) delay(d)
                _state.update { it.copy(connectProgress = "Connecting to 127.0.0.1:${config.port} (attempt ${i + 1}/${delays.size})…") }
                val h = bridge.health()
                last = h
                if (h.reachable && h.error == null) break
            }
            _state.update { it.copy(health = last, checking = false, connectProgress = null, daemonLog = null) }
            val final = last
            val ok = final != null && final.reachable && final.error == null
            if (ok && !final!!.daemonRunning) {
                // Re-check also explains a stopped daemon (tbp.log / ~/.tbp/daemon.log tails from the bridge).
                val st = runCatching { bridge.daemonStatus() }.getOrNull()
                _state.update { it.copy(daemonLog = st?.logText()?.ifBlank { null } ?: final.daemonError ?: "TBP daemon not running") }
            }
            // Keep the bridge's "Load images" state in sync (user.js only; Firefox reads it at its next start).
            if (ok && !BridgeVersions.isOutdated(final!!.version, "1.11.0")) runCatching {
                bridge.command("set_media", kotlinx.serialization.json.buildJsonObject { put("load_images", kotlinx.serialization.json.JsonPrimitive(prefs.loadImages.value)) })
            }
            if (!ok && termux.hasRunCommandPermission()) {
                termux.query(StepScripts.bridgeLogQuery(config.port), TAG_BRIDGE_LOG)
            }
        }
    }

    fun run(step: SetupStep) {
        val err = ops.runStep(step)
        if (err != null) { _state.update { it.copy(message = err) }; return }
        setStep(step.number) { it.copy(state = StepState.RUNNING, exitCode = null) }
        _state.update { it.copy(message = if (prefs.showTermuxDuringSetup.value) "Started \"${step.title}\" in a Termux session." else "Running \"${step.title}\" in the background (log below).") }
    }

    /** Clears the "running" lock without waiting (e.g. the user closed Termux mid-step). */
    fun stopWaiting() {
        val n = ops.state.value.runningStep ?: return
        ops.cancel()
        setStep(n) { it.copy(state = StepState.UNKNOWN) }
    }

    fun checkStep(n: Int) {
        termux.query(StepScripts.statusQuery(n), "status-$n").onFailure { e ->
            _state.update { it.copy(message = "Can't query Termux: ${e.message}") }
        }
    }

    fun showLog(n: Int) {
        checkStep(n)
        _state.update { it.copy(logDialogStep = n) }
    }

    fun dismissLog() = _state.update { it.copy(logDialogStep = null) }

    /** Display only; the actions themselves are driven by [BrowserOpsManager]. */
    private fun onTermuxResult(r: TermuxResult) {
        when {
            r.tag == TAG_BRIDGE_LOG -> {
                val p = StepScripts.parseStatus(r.stdout, key = "LISTENING")
                _state.update {
                    it.copy(bridgeListening = p.status,
                        bridgeLogTail = p.tail.ifBlank { r.errmsg ?: r.stderr.ifBlank { "(empty)" } })
                }
            }
            r.tag == BrowserOpsManager.TAG_ALL_STATUS -> Unit
            r.tag.startsWith("status-") -> {
                val n = r.tag.removePrefix("status-").toIntOrNull() ?: return
                val p = StepScripts.parseStatus(r.stdout)
                val code = p.status.toIntOrNull()
                val newState = when {
                    p.status == "running" -> StepState.RUNNING
                    p.status == "skipped" -> StepState.SKIPPED
                    code == 0 -> StepState.SUCCEEDED
                    code != null -> StepState.FAILED
                    else -> StepState.NOT_RUN
                }
                setStep(n) { it.copy(state = newState, exitCode = code, logTail = p.tail) }
            }
            r.tag.startsWith("step-") && r.tag != BrowserOpsManager.TAG_ALL -> {
                if (r.err != null && r.err != -1 && r.errmsg != null) _state.update { it.copy(message = "Termux: ${r.errmsg}") }
            }
        }
    }

    private fun setStep(n: Int, f: (StepUi) -> StepUi) =
        _state.update { s -> s.copy(steps = s.steps + (n to f(s.steps[n] ?: StepUi()))) }

    fun pushFingerprint() {
        val fp = _state.value.fingerprint ?: return
        viewModelScope.launch {
            val msg = runCatching { bridge.pushFingerprint(Json.encodeToJsonElement(fp)) }
                .fold({ if (it.ok) "Fingerprint sent to the bridge" else it.errorMessage }, { "Bridge unreachable: ${it.message}" })
            _state.update { it.copy(message = msg) }
        }
    }

    fun regenerateToken() {
        config.regenerateToken()
        _state.update { it.copy(message = "New bridge token generated — re-run steps 4 and 5.") }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(runCommandGranted = granted, message = if (granted) "RUN_COMMAND granted" else "RUN_COMMAND denied — use Copy instead") }
        if (granted) { refreshStepStatuses(); refreshInstallCheck() }
        val from = pendingAllFrom
        pendingAllFrom = null
        if (from != null) {
            if (granted) setupEverything(from)
            else ops.showSetupNote(SetupAllUi(error = "Farrow needs the Termux \"Run commands in Termux environment\" permission. Open App info → Permissions → Additional permissions, allow it, then tap again."))
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val TAG_BRIDGE_LOG = BrowserOpsManager.TAG_BRIDGE_LOG
    }
}
