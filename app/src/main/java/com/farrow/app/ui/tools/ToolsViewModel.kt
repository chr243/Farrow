package com.farrow.app.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.agent.tools.ToolRegistry
import com.farrow.app.data.a11y.FarrowAccessibilityService
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.TermuxManager
import com.farrow.app.data.git.GitCredentialStore
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
import javax.inject.Inject

data class ToolRow(val name: String, val description: String, val status: ToolStatus)

enum class PkgState { UNKNOWN, INSTALLED, NOT_INSTALLED, INSTALLING, FAILED }

data class PkgRow(val pkg: TermuxPackage, val state: PkgState, val detail: String? = null)

data class ToolsState(
    val tools: List<ToolRow> = emptyList(),
    val packages: List<PkgRow> = TermuxPackages.ALL.map { PkgRow(it, PkgState.UNKNOWN) },
    val packagesNote: String? = "Checking Termux…",
    val checking: Boolean = false,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    val prefs: ToolPrefs,
    private val bridge: BridgeClient,
    private val shizuku: ShizukuManager,
    private val termux: TermuxManager,
    private val gitCreds: GitCredentialStore,
    val mcp: com.farrow.app.data.mcp.McpManager,
    private val pkgJobs: TermuxPackageJobs,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsState(tools = rows(ToolEnv())))
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { pkgJobs.jobs.collect(::applyJobs) }
    }

    private fun rows(env: ToolEnv) = registry.tools.map { ToolRow(it.name, ToolStatus.short(it.description), ToolStatus.of(it.name, env)) }

    fun setEnabled(name: String, on: Boolean) = prefs.setEnabled(name, on)

    fun refresh() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val h = runCatching { bridge.health() }.getOrNull()
            val env = ToolEnv(
                bridgeUp = h?.bridgeOk == true,
                browserUp = h?.fullyUp == true,
                shizukuReady = runCatching { shizuku.refresh() }.getOrNull() == ShizukuState.READY,
                accessibilityOn = FarrowAccessibilityService.isRunning,
                gitToken = gitCreds.maskedToken != null,
            )
            _state.update { it.copy(tools = rows(env)) }
            detectPackages()
            _state.update { it.copy(checking = false) }
        }
    }

    private suspend fun detectPackages() {
        if (!termux.isInstalled()) return setNote("Install Termux (Settings > Internal browser setup) to add these.")
        if (!termux.hasRunCommandPermission()) return setNote("Grant the Termux RUN_COMMAND permission (Settings > Internal browser setup) first.")
        val r = termux.runAndWait(TermuxPackages.detectQuery(), "pkgs-${System.nanoTime()}", 10_000)
        val found = r?.let { TermuxPackages.parseDetect(it.stdout) }
            ?: return setNote("Termux did not answer — enable allow-external-apps (Settings > Internal browser setup, step 1).")
        _state.update { s ->
            s.copy(packagesNote = null, packages = s.packages.map { row ->
                if (row.state == PkgState.INSTALLING) row
                else row.copy(state = if (found[row.pkg.pkg] == true) PkgState.INSTALLED else PkgState.NOT_INSTALLED, detail = null)
            })
        }
    }

    private fun setNote(text: String) = _state.update { it.copy(packagesNote = text) }

    /** pkg install in the background (no Termux window), app-scoped ([TermuxPackageJobs]); detected again afterwards. */
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

    private fun updatePkg(p: TermuxPackage, f: (PkgRow) -> PkgRow) =
        _state.update { s -> s.copy(packages = s.packages.map { if (it.pkg == p) f(it) else it }) }
}
