package com.farrow.app.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.agent.tools.ToolRegistry
import com.farrow.app.data.a11y.FarrowAccessibilityService
import com.farrow.app.data.git.GitCredentialStore
import com.farrow.app.data.termux.TermuxManager
import com.farrow.app.data.tools.TermuxPackage
import com.farrow.app.data.tools.TermuxPackageJobs
import com.farrow.app.data.tools.TermuxPackages
import com.farrow.app.data.tools.ToolEnv
import com.farrow.app.data.tools.ToolPrefs
import com.farrow.app.data.tools.ToolStatus
import com.farrow.app.shizuku.ShizukuManager
import com.farrow.app.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ToolRow(val name: String, val description: String, val status: ToolStatus)

enum class PkgState { UNKNOWN, INSTALLED, NOT_INSTALLED, INSTALLING, FAILED }

data class PkgRow(val pkg: TermuxPackage, val state: PkgState, val detail: String? = null)

/** Termux setup as seen from the app (allow-external-apps can only be observed by a command answering). */
data class TermuxSetup(
    val installed: Boolean = false,
    val permission: Boolean = false,
    val answering: Boolean? = null,
    /** Termux can write shared storage (termux-setup-storage), needed for selenium_* / scrapers saving to Documents/Farrow. */
    val storage: Boolean? = null,
)

/** Documents/Farrow as seen from the app: All files access granted and the folders present. */
data class StorageSetup(val access: Boolean = false, val exists: Boolean = false)

data class ToolsState(
    val tools: List<ToolRow> = emptyList(),
    val storage: StorageSetup = StorageSetup(),
    val termux: TermuxSetup = TermuxSetup(),
    val packages: List<PkgRow> = TermuxPackages.ALL.map { PkgRow(it, PkgState.UNKNOWN) },
    val packagesNote: String? = "Checking Termux…",
    val checking: Boolean = false,
    /** "Set up Termux" flow: running, current step and the message for the user. */
    val setupActive: Boolean = false,
    val setupStep: com.farrow.app.data.termux.TermuxSetupStep? = null,
    val setupMessage: String? = null,
)

/** One-shot UI actions of the Set up Termux flow (need an Activity: intents, permission dialog, clipboard). */
sealed interface SetupEvent {
    data object OpenInstall : SetupEvent
    data object RequestPermission : SetupEvent
    /** Copy the allow-external-apps command and open Termux. */
    data object PasteAllowCommand : SetupEvent
    data object OpenTermux : SetupEvent
}

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    val prefs: ToolPrefs,
    private val shizuku: ShizukuManager,
    private val gitCreds: GitCredentialStore,
    val termux: TermuxManager,
    private val pkgJobs: TermuxPackageJobs,
    val mcp: com.farrow.app.data.mcp.McpManager,
    val sharedFolder: com.farrow.app.data.storage.SharedFolder,
    private val rish: com.farrow.app.shizuku.RishStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsState(tools = rows(ToolEnv())))
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    // Must be declared before init: refresh() runs check() immediately (Main.immediate) and needs checkLock.
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<SetupEvent>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<SetupEvent> = _events
    private val checkLock = kotlinx.coroutines.sync.Mutex()
    /** Last step the flow acted on, so coming back without progress shows a hint instead of re-triggering it. */
    private var lastActed: com.farrow.app.data.termux.TermuxSetupStep? = null

    init {
        refresh()
        viewModelScope.launch { pkgJobs.jobs.collect(::applyJobs) }
    }

    private fun rows(env: ToolEnv) = registry.tools.map { ToolRow(it.name, ToolStatus.short(it.description), ToolStatus.of(it.name, env)) }

    fun setEnabled(name: String, on: Boolean) = prefs.setEnabled(name, on)

    fun refresh() {
        if (_state.value.checking) return
        viewModelScope.launch { check() }
    }

    /** "Set up Termux": re-check, then do the next step (always acts, even if it is the same step again). */
    fun setupTermux() {
        lastActed = null
        _state.update { it.copy(setupActive = true, setupMessage = "Checking Termux…") }
        viewModelScope.launch { check(); advance() }
    }

    fun cancelSetup() = _state.update { it.copy(setupActive = false, setupStep = null, setupMessage = null) }

    /** Back in Farrow (resume / permission dialog closed): re-check and continue the flow if it is running. */
    fun onReturned() {
        if (!_state.value.setupActive) return refresh()
        viewModelScope.launch { check(); advance() }
    }

    private fun nextStep(): com.farrow.app.data.termux.TermuxSetupStep = _state.value.termux.let {
        com.farrow.app.data.termux.TermuxSetupFlow.next(it.installed, it.permission, it.answering, it.storage)
    }

    private fun advance() {
        if (!_state.value.setupActive) return
        val step = nextStep()
        val retry = step == lastActed
        val msg = com.farrow.app.data.termux.TermuxSetupFlow.message(step, retry)
        _state.update { it.copy(setupStep = step, setupMessage = msg, setupActive = step != com.farrow.app.data.termux.TermuxSetupStep.DONE) }
        if (retry) return
        lastActed = step
        when (step) {
            com.farrow.app.data.termux.TermuxSetupStep.INSTALL -> _events.tryEmit(SetupEvent.OpenInstall)
            com.farrow.app.data.termux.TermuxSetupStep.GRANT -> _events.tryEmit(SetupEvent.RequestPermission)
            com.farrow.app.data.termux.TermuxSetupStep.ALLOW_EXTERNAL -> _events.tryEmit(SetupEvent.PasteAllowCommand)
            com.farrow.app.data.termux.TermuxSetupStep.STORAGE -> {
                termux.runInTerminal("termux-setup-storage && echo 'Storage OK - you can go back to Farrow'", label = "Farrow: storage setup")
                _events.tryEmit(SetupEvent.OpenTermux)
            }
            com.farrow.app.data.termux.TermuxSetupStep.DONE -> lastActed = null
        }
    }

    private suspend fun check() = checkLock.withLock {
        _state.update { it.copy(checking = true) }
        run {
            val storage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                StorageSetup(sharedFolder.hasAccess(), sharedFolder.ensure() || sharedFolder.exists())
            }
            _state.update { it.copy(storage = storage) }
            val installed = termux.isInstalled()
            val permission = installed && termux.hasRunCommandPermission()
            _state.update { it.copy(termux = TermuxSetup(installed, permission)) }
            val env = ToolEnv(
                termuxReady = permission,
                shizukuReady = runCatching { shizuku.refresh() }.getOrNull() == ShizukuState.READY,
                accessibilityOn = FarrowAccessibilityService.isRunning,
                gitToken = gitCreds.maskedToken != null,
                storageReady = storage.access,
                rishReady = rish.isStaged(),
            )
            _state.update { it.copy(tools = rows(env)) }
            detectPackages()
            _state.update { it.copy(checking = false) }
        }
    }

    /** Result of the RUN_COMMAND runtime permission request from the Termux card. */
    fun onPermissionResult() = onReturned()

    private suspend fun detectPackages() {
        val t = _state.value.termux
        if (!t.installed) return setNote("Install Termux (F-Droid build) to add these.")
        if (!t.permission) return setNote("Grant the Run commands in Termux permission (Termux card above) first.")
        val r = termux.runAndWait(TermuxPackages.detectQuery(), "pkgs-${System.nanoTime()}", 10_000, label = "Farrow status")
        val found = r?.let { TermuxPackages.parseDetect(it.stdout) }
        val storage = r?.let { TermuxPackages.parseKeys(it.stdout)["STORAGE"] == "1" }
        _state.update { it.copy(termux = it.termux.copy(answering = found != null, storage = storage)) }
        if (found == null) return setNote("Termux did not answer — paste the allow-external-apps command (Termux card above) into Termux once.")
        _state.update { s ->
            s.copy(packagesNote = null, packages = s.packages.map { row ->
                if (row.state == PkgState.INSTALLING) row
                else row.copy(state = if (found[row.pkg.pkg] == true) PkgState.INSTALLED else PkgState.NOT_INSTALLED, detail = null)
            })
        }
    }

    private fun setNote(text: String) = _state.update { it.copy(packagesNote = text) }

    /** pkg install in the background (no Termux window), app-scoped ([TermuxPackageJobs]). */
    fun install(p: TermuxPackage) { pkgJobs.install(p) }

    private fun applyJobs(jobs: Map<String, TermuxPackageJobs.Job>) = _state.update { s ->
        s.copy(packages = s.packages.map { row ->
            val j = jobs[row.pkg.pkg] ?: return@map row
            when {
                j.running -> row.copy(state = PkgState.INSTALLING, detail = j.detail)
                j.ok -> row.copy(state = PkgState.INSTALLED, detail = null)
                row.state == PkgState.INSTALLING -> row.copy(state = PkgState.FAILED, detail = j.detail)
                else -> row
            }
        })
    }
}
