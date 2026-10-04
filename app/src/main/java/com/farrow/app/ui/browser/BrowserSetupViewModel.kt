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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject

enum class StepState { NOT_RUN, RUNNING, SUCCEEDED, SKIPPED, FAILED, UNKNOWN }

/** Live state of the one-button "Set up everything" / "Start browser" run. */
data class SetupAllUi(
    val running: Boolean = false,
    val phase: String = "",
    val currentStep: Int? = null,
    val statuses: Map<Int, StepState> = emptyMap(),
    val logTail: String = "",
    val failedStep: Int? = null,
    val error: String? = null,
    /** Manual command to show (step 1 / allow-external-apps) when Termux doesn't answer. */
    val manualCommand: String? = null,
    val done: Boolean = false,
)

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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    /** Running vs. bundled bridge version → "Bridge outdated, tap to update" banner. */
    val bridgeUpdate = autoStarter.updateState
    fun checkBridgeVersion() { viewModelScope.launch { runCatching { autoStarter.checkVersion() } } }
    fun updateBridge() { viewModelScope.launch { runCatching { autoStarter.updateIfOutdated(force = true) } } }


    private val _state = MutableStateFlow(BrowserSetupState())
    val state: StateFlow<BrowserSetupState> = _state.asStateFlow()

    val steps: List<SetupStep> get() = installer.steps()
    val bridgePort: Int get() = config.port

    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var allJob: Job? = null
    private var pendingAllFrom: Int? = null

    init {
        viewModelScope.launch { TermuxResults.results.collect(::onTermuxResult) }
        viewModelScope.launch {
            val fp = withContext(Dispatchers.Default) { runCatching { fingerprint.get() }.getOrNull() }
            _state.update { it.copy(fingerprint = fp) }
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

    fun setShowTermux(on: Boolean) {
        prefs.setShowTermuxDuringSetup(on)
        _state.update { it.copy(showTermux = on) }
    }

    /** True when the last setup command opened the Termux UI (so we bring Farrow back when it finishes). */
    @Volatile private var openedTermuxUi = false

    /** After a foreground Termux run: Farrow back on top (the script's `am start` does it too; this is the fallback). */
    private fun bringAppToFront() {
        if (!openedTermuxUi) return
        openedTermuxUi = false
        runCatching {
            appContext.startActivity(android.content.Intent(appContext, com.farrow.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
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
        if (_state.value.setupAll?.running == true) return true
        refreshChecks()
        if (!termux.isInstalled()) {
            _state.update { it.copy(setupAll = SetupAllUi(error = "Termux is not installed. Install it from F-Droid (not Google Play), open it once, then tap again.")) }
            return true
        }
        if (!termux.hasRunCommandPermission()) {
            pendingAllFrom = from
            _state.update { it.copy(setupAll = SetupAllUi(phase = "Requesting the Termux RUN_COMMAND permission…")) }
            return false
        }
        allJob?.cancel()
        allJob = viewModelScope.launch { orchestrate(from) }
        return true
    }

    private fun updAll(f: (SetupAllUi) -> SetupAllUi) = _state.update { it.copy(setupAll = f(it.setupAll ?: SetupAllUi())) }

    private suspend fun orchestrate(from: Int) {
        _state.update { it.copy(setupAll = SetupAllUi(running = true, phase = "Checking what is already installed…")) }
        val check = autoStarter.checkInstalled()
        _state.update { it.copy(install = check) }
        if (!check.termuxAnswered) {
            updAll {
                it.copy(running = false, failedStep = 1, error = check.error + ".\nOpen Termux, paste this command once, press Enter, then tap Retry.",
                    manualCommand = steps.first { s -> s.number == 1 }.command)
            }
            return
        }
        val statuses = check.installed.mapValues { (_, ok) -> if (ok) StepState.SKIPPED else StepState.NOT_RUN } + (5 to StepState.NOT_RUN)
        if (check.allInstalled) {
            // Everything installed (e.g. after a reboot): only start the bridge + daemon, in the background.
            updAll { it.copy(phase = "Everything is installed — starting the bridge and daemon…", currentStep = 5, statuses = statuses + (5 to StepState.RUNNING)) }
            val ok = autoStarter.ensureRunning(timeoutMs = 20_000, force = true)
            if (ok || bridge.health().bridgeOk) {
                finishWithDaemon(statuses)
            } else {
                termux.query(StepScripts.statusQuery(5), "status-5")
                updAll { it.copy(running = false, failedStep = 5, statuses = statuses + (5 to StepState.FAILED), error = "The bridge did not come up within 20 s. Check the step 5 log below, then Retry.") }
                termux.query(StepScripts.bridgeLogQuery(config.port), TAG_BRIDGE_LOG)
                connect()
            }
            return
        }
        val start = maxOf(from, check.missing.firstOrNull() ?: 5)
        updAll { it.copy(phase = "Installing the missing parts (${check.missing.joinToString { "step $it" }}) in Termux…", statuses = statuses) }
        // Background RUN_COMMAND (no Termux window) unless the user wants to watch; progress comes from the log files.
        val fg = prefs.showTermuxDuringSetup.value
        openedTermuxUi = fg
        val r = termux.runCommand(installer.setupAllScript(start), background = !fg, label = "Farrow: set up everything", resultTag = TAG_ALL)
        if (r.isFailure) {
            updAll { it.copy(running = false, failedStep = start, error = "Could not run in Termux: ${r.exceptionOrNull()?.message}") }
            return
        }
        val begin = System.currentTimeMillis()
        sawRunning = false
        delay(2_000)
        while (_state.value.setupAll?.running == true && System.currentTimeMillis() - begin < 45 * 60_000L) {
            termux.query(StepScripts.setupAllQuery((2..5).toList()), TAG_ALL_STATUS)
            delay(ALL_POLL_MS)
        }
        if (_state.value.setupAll?.running == true) updAll { it.copy(running = false, error = "Timed out waiting for the setup (45 min).", failedStep = it.currentStep) }
    }

    private var sawRunning = false

    private fun onAllStatus(r: TermuxResult) {
        val cur = _state.value.setupAll ?: return
        if (!cur.running) return
        val keys = StepScripts.parseKeys(r.stdout)
        val all = keys["ALL"] ?: return
        val tail = r.stdout.substringAfter("\n---", "").trim()
        val st = (2..5).associateWith { n -> stateOf(keys["S$n"]) }
        val step = keys["CUR"]?.toIntOrNull()
        if (all == "running") sawRunning = true
        if (!sawRunning) return // stale result of a previous run
        updAll { it.copy(statuses = it.statuses + st, currentStep = step ?: it.currentStep, logTail = tail,
            phase = if (step != null) "Step $step of 5: ${steps.firstOrNull { s -> s.number == step }?.title?.substringAfter(". ") ?: ""}" else it.phase) }
        when {
            all == "0" -> {
                bringAppToFront()
                updAll { it.copy(phase = "Installed — checking the bridge and the TBP daemon…") }
                refreshInstallCheck()
                viewModelScope.launch {
                    // Step 5 started the bridge; wait for it, then require the daemon too.
                    for (i in 0 until 10) { if (bridge.health().bridgeOk) break; delay(1_000) }
                    finishWithDaemon(_state.value.setupAll?.statuses.orEmpty())
                }
            }
            all.contains(':') -> {
                val n = all.substringBefore(':').toIntOrNull()
                updAll { it.copy(running = false, failedStep = n, error = "Step $n failed (exit ${all.substringAfter(':')}).") }
            }
        }
    }

    /** Success only when the TBP daemon runs too: start it if needed (≤ 30 s), else ❌ with tbp.log / daemon.log. */
    private suspend fun finishWithDaemon(statuses: Map<Int, StepState>) {
        updAll { it.copy(running = true, currentStep = 5, phase = "Checking the TBP daemon…") }
        val r = autoStarter.ensureDaemon(30_000, force = true) { p -> updAll { it.copy(phase = p) } }
        if (r.running) {
            updAll { it.copy(running = false, done = true, error = null, phase = "✅ Browser started (bridge + TBP daemon running)", statuses = statuses + (5 to StepState.SUCCEEDED)) }
        } else {
            updAll { it.copy(running = false, done = false, failedStep = 5, statuses = statuses + (5 to StepState.FAILED),
                error = "❌ ${r.message}. The bridge is up, but Firefox/Xvfb didn't start — see the log below.", logTail = r.log) }
        }
        connect()
    }

    private fun stateOf(v: String?): StepState = when {
        v == null || v == "none" -> StepState.NOT_RUN
        v == "running" -> StepState.RUNNING
        v == "skipped" -> StepState.SKIPPED
        v == "0" -> StepState.SUCCEEDED
        v.toIntOrNull() != null -> StepState.FAILED
        else -> StepState.UNKNOWN
    }

    /** Status card: start only the TBP daemon. */
    fun startDaemon() {
        if (_state.value.setupAll?.running == true) return
        allJob?.cancel()
        allJob = viewModelScope.launch { finishWithDaemon(_state.value.setupAll?.statuses.orEmpty()) }
    }

    /** Status card "Reset browser": stop everything, clear the locks, start fresh; shows the log with Copy. */
    fun resetBrowser() {
        if (_state.value.resetting || _state.value.setupAll?.running == true) return
        viewModelScope.launch {
            _state.update { it.copy(resetting = true, resetMessage = "Resetting…", resetLog = null) }
            val r = autoStarter.resetDaemon { p -> _state.update { it.copy(resetMessage = p) } }
            _state.update { it.copy(resetting = false, resetMessage = r.message, resetLog = r.log.ifBlank { null }) }
            connect()
        }
    }

    fun cancelSetupAll() {
        allJob?.cancel()
        updAll { it.copy(running = false, phase = "Stopped waiting (the Termux session may still be running).") }
    }

    fun dismissSetupAll() = _state.update { it.copy(setupAll = null) }

    private fun refreshChecks() {
        _state.update { it.copy(termuxInstalled = termux.isInstalled(), runCommandGranted = termux.hasRunCommandPermission(), autoStart = autoStarter.enabled,
            showTermux = prefs.showTermuxDuringSetup.value, browserLanguage = prefs.browserLanguage.value) }
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
            if (!ok && termux.hasRunCommandPermission()) {
                termux.query(StepScripts.bridgeLogQuery(config.port), TAG_BRIDGE_LOG)
            }
        }
    }

    fun run(step: SetupStep) {
        val busy = _state.value.runningStep
        if (busy != null && busy != step.number) {
            _state.update { it.copy(message = "Step $busy is still running — wait for it to finish.") }
            return
        }
        val fg = prefs.showTermuxDuringSetup.value
        openedTermuxUi = fg
        val r = termux.runCommand(step.command, background = !fg, label = step.title, resultTag = "step-${step.number}")
        r.onSuccess {
            setStep(step.number) { it.copy(state = StepState.RUNNING, exitCode = null) }
            _state.update { it.copy(runningStep = step.number,
                message = if (fg) "Started \"${step.title}\" in a Termux session." else "Running \"${step.title}\" in the background (log below).") }
            startPolling(step.number)
        }.onFailure { e ->
            _state.update { it.copy(message = "Could not run in Termux: ${e.message}. Copy the command and paste it into Termux instead.") }
        }
    }

    private fun startPolling(n: Int) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 30 * 60_000L
            delay(2_000)
            while (isActive && _state.value.runningStep == n && System.currentTimeMillis() < deadline) {
                termux.query(StepScripts.statusQuery(n), "status-$n")
                delay(POLL_MS)
            }
            if (_state.value.runningStep == n) _state.update { it.copy(runningStep = null) }
        }
    }

    /** Clears the "running" lock without waiting (e.g. the user closed Termux mid-step). */
    fun stopWaiting() {
        pollJob?.cancel()
        val n = _state.value.runningStep ?: return
        setStep(n) { it.copy(state = StepState.UNKNOWN) }
        _state.update { it.copy(runningStep = null) }
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

    private fun onTermuxResult(r: TermuxResult) {
        when {
            r.tag == TAG_ALL_STATUS -> onAllStatus(r)
            r.tag == TAG_ALL -> {
                if (r.err != null && r.err != -1 && !r.errmsg.isNullOrBlank()) updAll { it.copy(running = false, error = "Termux: ${r.errmsg}") }
            }
            r.tag.startsWith("probe-") || r.tag == "autostart" -> Unit
            r.tag == TAG_BRIDGE_LOG -> {
                val p = StepScripts.parseStatus(r.stdout, key = "LISTENING")
                _state.update {
                    it.copy(bridgeListening = p.status,
                        bridgeLogTail = p.tail.ifBlank { r.errmsg ?: r.stderr.ifBlank { "(empty)" } })
                }
            }
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
                if (newState != StepState.RUNNING && _state.value.runningStep == n && code == 0) bringAppToFront()
                if (newState != StepState.RUNNING && _state.value.runningStep == n) {
                    _state.update { it.copy(runningStep = null, message = if (code == 0) "✅ Step $n succeeded" else if (code != null) "❌ Step $n failed (exit $code)" else null) }
                    if (n == 5 && code == 0) connect()
                }
            }
            r.tag.startsWith("step-") -> {
                // The foreground session ended (user pressed Enter); read the real status from the files.
                val n = r.tag.removePrefix("step-").toIntOrNull() ?: return
                if (r.err != null && r.err != -1 && r.errmsg != null) {
                    _state.update { it.copy(message = "Termux: ${r.errmsg}") }
                }
                checkStep(n)
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
            else _state.update { it.copy(setupAll = SetupAllUi(error = "Farrow needs the Termux \"Run commands in Termux environment\" permission. Open App info → Permissions → Additional permissions, allow it, then tap again.")) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val TAG_BRIDGE_LOG = "bridge-log"
        const val POLL_MS = 4_000L
        const val ALL_POLL_MS = 3_000L
        const val TAG_ALL = "step-all"
        const val TAG_ALL_STATUS = "status-all"
    }
}
