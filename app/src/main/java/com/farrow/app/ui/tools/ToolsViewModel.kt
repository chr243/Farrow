package com.farrow.app.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.agent.tools.ToolRegistry
import com.farrow.app.data.a11y.FarrowAccessibilityService
import com.farrow.app.data.git.GitCredentialStore
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

data class ToolsState(
    val tools: List<ToolRow> = emptyList(),
    val checking: Boolean = false,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    val prefs: ToolPrefs,
    private val shizuku: ShizukuManager,
    private val gitCreds: GitCredentialStore,
    val mcp: com.farrow.app.data.mcp.McpManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsState(tools = rows(ToolEnv())))
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun rows(env: ToolEnv) = registry.tools.map { ToolRow(it.name, ToolStatus.short(it.description), ToolStatus.of(it.name, env)) }

    fun setEnabled(name: String, on: Boolean) = prefs.setEnabled(name, on)

    fun refresh() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val env = ToolEnv(
                shizukuReady = runCatching { shizuku.refresh() }.getOrNull() == ShizukuState.READY,
                accessibilityOn = FarrowAccessibilityService.isRunning,
                gitToken = gitCreds.maskedToken != null,
            )
            _state.update { it.copy(tools = rows(env)) }
            _state.update { it.copy(checking = false) }
        }
    }
}
