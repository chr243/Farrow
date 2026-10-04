package com.farrow.app.data.browser

import android.content.Context
import com.farrow.app.data.prefs.AppPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class StepState { NOT_RUN, RUNNING, SUCCEEDED, SKIPPED, FAILED, UNKNOWN }

/** Live state of the one-button "Set up everything" / "Start browser" run. */
@Serializable
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

/** Browser setup actions run by [BrowserOpsManager] (one at a time). */
enum class BrowserOpKind { SETUP, START, RESET, UPDATE_BRIDGE, STEP }

/**
 * v1.0.7: progress and last result of the Settings → Internal browser actions. Owned by [BrowserOpsManager] (app
 * scope), observed by the screen; persisted so a reopened screen (or a restarted app) shows the last result.
 */
@Serializable
data class BrowserOpsState(
    /** Running action (null = idle). */
    val kind: BrowserOpKind? = null,
    val label: String? = null,
    /** Current step while running, e.g. "Step 3 of 5: …". */
    val progress: String? = null,
    val setupAll: SetupAllUi? = null,
    /** Advanced: the single setup step being run. */
    val runningStep: Int? = null,
    val resetting: Boolean = false,
    val resetMessage: String? = null,
    val resetLog: String? = null,
    val lastKind: BrowserOpKind? = null,
    val lastResult: String? = null,
    val lastOk: Boolean? = null,
    val finishedAt: Long? = null,
    /** Bridge job id of a running reset (lets the app wait for it again after being killed). */
    val pendingJob: String? = null,
) {
    val busy: Boolean get() = kind != null

    /** After a process restart nothing can still be running in this process (a bridge-side reset job can). */
    fun restored(): BrowserOpsState {
        if (kind == null) return copy(progress = null, resetting = false, runningStep = null, setupAll = setupAll?.copy(running = false))
        val note = when (kind) {
            BrowserOpKind.SETUP, BrowserOpKind.STEP -> "Interrupted: Farrow was closed while waiting. The Termux session may have finished — tap Re-check."
            BrowserOpKind.RESET -> if (pendingJob != null) "Waiting for the browser reset again…" else "Interrupted: Farrow was closed during the reset — tap Re-check."
            else -> "Interrupted: Farrow was closed — tap Re-check."
        }
        return copy(kind = null, progress = null, runningStep = null, resetting = false, lastKind = kind, lastResult = note, lastOk = null,
            setupAll = setupAll?.let { if (it.running) it.copy(running = false, phase = note) else it },
            resetMessage = if (resetting) note else resetMessage)
    }
}

interface BrowserOpsStore {
    fun load(): BrowserOpsState?
    fun save(state: BrowserOpsState)
}

@Singleton
class PrefsBrowserOpsStore @Inject constructor(@ApplicationContext context: Context) : BrowserOpsStore {
    private val prefs = context.getSharedPreferences("browser_ops", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    override fun load() = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(BrowserOpsState.serializer(), it) }.getOrNull() }
    override fun save(state: BrowserOpsState) { prefs.edit().putString(KEY, json.encodeToString(BrowserOpsState.serializer(), state)).apply() }
    private companion object { const val KEY = "state" }
}

/**
 * The lifecycle part of [BrowserOpsManager], free of Android/bridge types (unit-tested): one action at a time in an
 * app-lifetime [scope] (never a ViewModel scope), a persisted [state], and [cancel] as the only way to stop an action.
 */
class BrowserOpsRunner(
    private val scope: CoroutineScope,
    private val store: BrowserOpsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(store.load()?.restored() ?: BrowserOpsState())
    val state: StateFlow<BrowserOpsState> = _state.asStateFlow()
    @Volatile private var job: Job? = null

    val current: BrowserOpsState get() = _state.value
    fun isRunning() = job?.isActive == true

    fun update(f: (BrowserOpsState) -> BrowserOpsState) {
        _state.update(f)
        store.save(_state.value)
    }

    fun progress(text: String) = update { it.copy(progress = text) }

    /** Terminal result: shown as "Last: …" when the user comes back. */
    fun finish(ok: Boolean?, result: String?, f: (BrowserOpsState) -> BrowserOpsState = { it }) = update { s0 ->
        val s = f(s0)
        s.copy(kind = null, progress = null, runningStep = null, resetting = false, lastKind = s0.kind ?: s0.lastKind,
            lastResult = result ?: s.lastResult, lastOk = ok, finishedAt = clock(), setupAll = s.setupAll?.copy(running = false))
    }

    /** Starts [block] unless an action is running. Returns false when ignored. */
    fun launch(kind: BrowserOpKind, label: String, block: suspend () -> Unit): Boolean {
        if (isRunning() || current.busy) return false
        update { it.copy(kind = kind, label = label, progress = label) }
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(false, "❌ $label failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // Never leave a spinner behind (a block that returned without publishing a result).
                if (current.busy && job === coroutineContext[Job]) finish(null, null)
            }
        }
        job = j
        j.invokeOnCompletion { if (job === j) job = null }
        j.start()
        return true
    }

    /** Cancel button (screen or notification): stops waiting; bridge-side work (a reset job) still completes. */
    fun cancel() {
        val s = current
        job?.cancel(); job = null
        if (!s.busy) return
        val note = when (s.kind) {
            BrowserOpKind.RESET -> if (s.pendingJob != null) "Stopped waiting. The bridge still finishes the reset by itself — tap Re-check in a moment." else "Cancelled."
            BrowserOpKind.SETUP, BrowserOpKind.STEP -> "Stopped waiting (the Termux session may still be running)."
            else -> "Cancelled."
        }
        finish(null, note) { it.copy(pendingJob = null, resetMessage = if (it.resetting) note else it.resetMessage,
            setupAll = it.setupAll?.let { a -> if (a.running) a.copy(phase = note) else a }) }
    }
}

/** Notification texts for [BrowserOpsService] (pure, unit-tested). */
object BrowserOpsNotifText {
    fun title(s: BrowserOpsState) = when (s.kind) {
        BrowserOpKind.SETUP -> "Setting up the internal browser…"
        BrowserOpKind.START -> "Starting the internal browser…"
        BrowserOpKind.RESET -> "Resetting the internal browser…"
        BrowserOpKind.UPDATE_BRIDGE -> "Updating the browser bridge…"
        BrowserOpKind.STEP -> "Running setup step ${s.runningStep ?: ""}…".replace("step …", "step…")
        null -> "Internal browser"
    }
    fun text(s: BrowserOpsState) = s.progress ?: s.label ?: "Working…"
}

/**
 * v1.0.7: app-scoped owner of the Settings → Internal browser actions (Set up everything / Start browser, Start TBP
 * daemon, Reset browser, Update bridge, single setup steps). They used to run in the screen's viewModelScope, so leaving
 * the screen stopped them midway (same bug as the v1.0.1 cookie import). Now: own SupervisorJob scope,
 * [BrowserOpsService] foreground notification with the current step + Cancel, last result persisted.
 */
@Singleton
class BrowserOpsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: BridgeConfig,
    private val termux: TermuxManager,
    private val installer: BridgeInstaller,
    private val bridge: BridgeClient,
    private val autoStarter: BridgeAutoStarter,
    private val prefs: AppPrefs,
    store: PrefsBrowserOpsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = BrowserOpsRunner(scope, store)
    val state: StateFlow<BrowserOpsState> get() = runner.state

    private val steps: List<SetupStep> get() = installer.steps()
    private val allOutcome = MutableStateFlow<String?>(null)
    private val stepOutcome = MutableStateFlow<Pair<Int, Int?>?>(null)
    @Volatile private var sawRunning = false
    @Volatile private var openedTermuxUi = false

    init {
        scope.launch { TermuxResults.results.collect(::onTermuxResult) }
        scope.launch { runner.state.map { it.busy }.distinctUntilChanged().collect { if (it) BrowserOpsService.start(context) } }
        // A reset job keeps running in the bridge while the app is dead: wait for its result again.
        runner.current.pendingJob?.let { id ->
            runner.launch(BrowserOpKind.RESET, "Resetting the internal browser…") {
                runner.update { it.copy(resetting = true, resetMessage = "Waiting for the browser reset (started earlier)…") }
                publishReset(autoStarter.awaitReset(id))
            }
        }
    }

    fun cancel() = runner.cancel()
    fun dismissSetupAll() = runner.update { it.copy(setupAll = null) }
    fun dismissLast() = runner.update { it.copy(lastResult = null, lastOk = null, lastKind = null) }
    /** Pre-check notes from the screen (Termux missing, permission request…). */
    fun showSetupNote(ui: SetupAllUi) { if (!runner.current.busy) runner.update { it.copy(setupAll = ui) } }

    private fun updAll(f: (SetupAllUi) -> SetupAllUi) = runner.update { s ->
        val a = f(s.setupAll ?: SetupAllUi())
        s.copy(setupAll = a, progress = if (s.kind == BrowserOpKind.SETUP || s.kind == BrowserOpKind.START) a.phase.ifBlank { s.progress } else s.progress)
    }

    // ---------------- Set up everything / Start browser ----------------

    fun setupEverything(from: Int): Boolean = runner.launch(BrowserOpKind.SETUP, "Set up everything") { orchestrate(from) }

    private suspend fun orchestrate(from: Int) {
        runner.update { it.copy(setupAll = SetupAllUi(running = true, phase = "Checking what is already installed…")) }
        val check = autoStarter.checkInstalled()
        if (!check.termuxAnswered) {
            updAll { it.copy(running = false, failedStep = 1, error = check.error + ".\nOpen Termux, paste this command once, press Enter, then tap Retry.",
                manualCommand = steps.first { s -> s.number == 1 }.command) }
            runner.finish(false, "❌ Termux did not answer (step 1)")
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
                termux.query(StepScripts.bridgeLogQuery(config.port), TAG_BRIDGE_LOG)
                val err = "The bridge did not come up within 20 s. Check the step 5 log below, then Retry."
                updAll { it.copy(running = false, failedStep = 5, statuses = statuses + (5 to StepState.FAILED), error = err) }
                runner.finish(false, "❌ $err")
            }
            return
        }
        val start = maxOf(from, check.missing.firstOrNull() ?: 5)
        updAll { it.copy(phase = "Installing the missing parts (${check.missing.joinToString { "step $it" }}) in Termux…", statuses = statuses) }
        // Background RUN_COMMAND (no Termux window) unless the user wants to watch; progress comes from the log files.
        val fg = prefs.showTermuxDuringSetup.value
        openedTermuxUi = fg
        allOutcome.value = null
        sawRunning = false
        val r = termux.runCommand(installer.setupAllScript(start), background = !fg, label = "Farrow: set up everything", resultTag = TAG_ALL)
        if (r.isFailure) {
            val err = "Could not run in Termux: ${r.exceptionOrNull()?.message}"
            updAll { it.copy(running = false, failedStep = start, error = err) }
            runner.finish(false, "❌ $err")
            return
        }
        delay(2_000)
        val outcome = withTimeoutOrNull(45 * 60_000L) {
            var o: String? = null
            while (o == null) {
                termux.query(StepScripts.setupAllQuery((2..5).toList()), TAG_ALL_STATUS)
                o = withTimeoutOrNull(ALL_POLL_MS) { allOutcome.filterNotNull().first() }
            }
            o
        }
        when {
            outcome == null -> {
                updAll { it.copy(running = false, error = "Timed out waiting for the setup (45 min).", failedStep = it.currentStep) }
                runner.finish(false, "❌ Timed out waiting for the setup (45 min)")
            }
            outcome == "0" -> {
                bringAppToFront()
                updAll { it.copy(phase = "Installed — checking the bridge and the TBP daemon…") }
                // Step 5 started the bridge; wait for it, then require the daemon too.
                for (i in 0 until 10) { if (bridge.health().bridgeOk) break; delay(1_000) }
                finishWithDaemon(runner.current.setupAll?.statuses.orEmpty())
            }
            outcome.startsWith("termux:") -> {
                val err = "Termux: ${outcome.removePrefix("termux:")}"
                updAll { it.copy(running = false, error = err) }
                runner.finish(false, "❌ $err")
            }
            else -> {
                val n = outcome.substringBefore(':').toIntOrNull()
                val err = "Step $n failed (exit ${outcome.substringAfter(':')})."
                updAll { it.copy(running = false, failedStep = n, error = err) }
                runner.finish(false, "❌ $err")
            }
        }
    }

    private fun onAllStatus(r: TermuxResult) {
        val cur = runner.current.setupAll ?: return
        if (!cur.running || runner.current.kind != BrowserOpKind.SETUP) return
        val keys = StepScripts.parseKeys(r.stdout)
        val all = keys["ALL"] ?: return
        val tail = r.stdout.substringAfter("\n---", "").trim()
        val st = (2..5).associateWith { n -> stateOf(keys["S$n"]) }
        val step = keys["CUR"]?.toIntOrNull()
        if (all == "running") sawRunning = true
        if (!sawRunning) return // stale result of a previous run
        updAll { it.copy(statuses = it.statuses + st, currentStep = step ?: it.currentStep, logTail = tail,
            phase = if (step != null) "Step $step of 5: ${steps.firstOrNull { s -> s.number == step }?.title?.substringAfter(". ") ?: ""}" else it.phase) }
        if (all == "0" || all.contains(':')) allOutcome.value = all
    }

    /** Status card "Start TBP daemon". */
    fun startDaemon(): Boolean = runner.launch(BrowserOpKind.START, "Start the TBP daemon") {
        finishWithDaemon(runner.current.setupAll?.statuses.orEmpty())
    }

    /** Success only when the TBP daemon runs too: start it if needed (≤ 30 s), else ❌ with tbp.log / daemon.log. */
    private suspend fun finishWithDaemon(statuses: Map<Int, StepState>) {
        updAll { it.copy(running = true, currentStep = 5, phase = "Checking the TBP daemon…") }
        val r = autoStarter.ensureDaemon(30_000, force = true) { p -> updAll { it.copy(phase = p) } }
        if (r.running) {
            val msg = "✅ Browser started (bridge + TBP daemon running)"
            updAll { it.copy(running = false, done = true, error = null, phase = msg, statuses = statuses + (5 to StepState.SUCCEEDED)) }
            runner.finish(true, msg)
        } else {
            val err = "❌ ${r.message}. The bridge is up, but Firefox/Xvfb didn't start — see the log below."
            updAll { it.copy(running = false, done = false, failedStep = 5, statuses = statuses + (5 to StepState.FAILED), error = err, logTail = r.log) }
            runner.finish(false, err)
        }
    }

    // ---------------- Reset browser ----------------

    /** Status card "Reset browser": a bridge-side job; the app only waits for it (Cancel = stop waiting). */
    fun resetBrowser(): Boolean = runner.launch(BrowserOpKind.RESET, "Reset the internal browser") {
        runner.update { it.copy(resetting = true, resetMessage = "Resetting…", resetLog = null) }
        val r = autoStarter.resetDaemon(
            onProgress = { p -> runner.update { it.copy(resetMessage = p, progress = p) } },
            onJob = { id -> runner.update { it.copy(pendingJob = id) } },
        )
        publishReset(r)
    }

    private fun publishReset(r: BridgeAutoStarter.DaemonResult) =
        runner.finish(r.running, r.message) { it.copy(resetMessage = r.message, resetLog = r.log.ifBlank { null }, pendingJob = null) }

    // ---------------- Update bridge ----------------

    fun updateBridge(): Boolean = runner.launch(BrowserOpKind.UPDATE_BRIDGE, "Update the browser bridge") {
        val watch = scope.launch { autoStarter.updateState.collect { u -> u?.message?.let(runner::progress) } }
        try {
            val ok = autoStarter.updateIfOutdated(force = true)
            val msg = autoStarter.updateState.value?.message
            runner.finish(ok, msg ?: if (ok) "✅ Bridge is up to date" else "❌ Bridge update failed")
        } finally { watch.cancel() }
    }

    // ---------------- single setup step (Advanced) ----------------

    /** Returns an error text, or null when the step started. */
    fun runStep(step: SetupStep): String? {
        val busy = runner.current
        if (busy.busy) return if (busy.runningStep != null) "Step ${busy.runningStep} is still running — wait for it to finish." else "${busy.label ?: "Another action"} is still running."
        val fg = prefs.showTermuxDuringSetup.value
        val r = termux.runCommand(step.command, background = !fg, label = step.title, resultTag = "step-${step.number}")
        r.exceptionOrNull()?.let { return "Could not run in Termux: ${it.message}. Copy the command and paste it into Termux instead." }
        openedTermuxUi = fg
        val n = step.number
        stepOutcome.value = null
        runner.launch(BrowserOpKind.STEP, step.title) {
            runner.update { it.copy(runningStep = n, progress = if (fg) "Running in a Termux session…" else "Running in the background…") }
            delay(2_000)
            val done = withTimeoutOrNull(30 * 60_000L) {
                var d: Pair<Int, Int?>? = null
                while (d == null) {
                    termux.query(StepScripts.statusQuery(n), "status-$n")
                    d = withTimeoutOrNull(POLL_MS) { stepOutcome.filterNotNull().first { it.first == n } }
                }
                d
            }
            val code = done?.second
            if (code == 0) bringAppToFront()
            runner.finish(code == 0, when {
                done == null -> "Step $n: stopped waiting after 30 min."
                code == 0 -> "✅ Step $n succeeded"
                code != null -> "❌ Step $n failed (exit $code)"
                else -> "Step $n finished (no status)."
            })
        }
        return null
    }

    // ---------------- Termux results ----------------

    private fun onTermuxResult(r: TermuxResult) {
        when {
            r.tag == TAG_ALL_STATUS -> onAllStatus(r)
            r.tag == TAG_ALL -> {
                if (r.err != null && r.err != -1 && !r.errmsg.isNullOrBlank() && runner.current.kind == BrowserOpKind.SETUP) allOutcome.value = "termux:${r.errmsg}"
            }
            r.tag.startsWith("status-") -> {
                val n = r.tag.removePrefix("status-").toIntOrNull() ?: return
                if (runner.current.runningStep != n) return
                val p = StepScripts.parseStatus(r.stdout)
                if (p.status != "running") stepOutcome.value = n to p.status.toIntOrNull()
            }
            r.tag.startsWith("step-") -> {
                // The foreground session ended (user pressed Enter); read the real status from the files.
                val n = r.tag.removePrefix("step-").toIntOrNull() ?: return
                termux.query(StepScripts.statusQuery(n), "status-$n")
            }
        }
    }

    /** After a foreground Termux run: Farrow back on top (the script's `am start` does it too; this is the fallback). */
    private fun bringAppToFront() {
        if (!openedTermuxUi) return
        openedTermuxUi = false
        runCatching {
            context.startActivity(android.content.Intent(context, com.farrow.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun stateOf(v: String?): StepState = when {
        v == null || v == "none" -> StepState.NOT_RUN
        v == "running" -> StepState.RUNNING
        v == "skipped" -> StepState.SKIPPED
        v == "0" -> StepState.SUCCEEDED
        v.toIntOrNull() != null -> StepState.FAILED
        else -> StepState.UNKNOWN
    }

    companion object {
        const val TAG_BRIDGE_LOG = "bridge-log"
        const val TAG_ALL = "step-all"
        const val TAG_ALL_STATUS = "status-all"
        private const val POLL_MS = 4_000L
        private const val ALL_POLL_MS = 3_000L
    }
}
