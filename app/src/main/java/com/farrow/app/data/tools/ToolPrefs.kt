package com.farrow.app.data.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted on/off per agent tool (Settings > Tools). Only the disabled names are stored, so new tools default to on. */
@Singleton
class ToolPrefs @Inject constructor(@ApplicationContext context: Context) : ToolSwitches {
    private val prefs = context.getSharedPreferences("tool_prefs", Context.MODE_PRIVATE)
    private val _disabled = MutableStateFlow(prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty().toSet())
    val disabled: StateFlow<Set<String>> = _disabled.asStateFlow()

    override fun isEnabled(name: String): Boolean = name !in _disabled.value

    fun setEnabled(name: String, enabled: Boolean) {
        val next = if (enabled) _disabled.value - name else _disabled.value + name
        prefs.edit().putStringSet(KEY_DISABLED, next).apply()
        _disabled.value = next
    }

    private companion object { const val KEY_DISABLED = "disabled" }
}

/** What [com.farrow.app.agent.tools.ToolRegistry] needs (testable without Android). */
fun interface ToolSwitches {
    fun isEnabled(name: String): Boolean

    companion object { val ALL_ON = ToolSwitches { true } }
}
