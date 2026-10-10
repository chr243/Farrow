package com.verdroid.app.data.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted on/off per agent tool (Settings > Tools). Only the disabled names are stored, so new tools default to on —
 * except [DEFAULT_OFF] tools (betas), which are off until the user turns them on (stored as opt-ins).
 * [disabled] is the effective set (stored disabled + default-off tools not opted in).
 */
@Singleton
class ToolPrefs @Inject constructor(@ApplicationContext context: Context) : ToolSwitches {
    private val prefs = context.getSharedPreferences("tool_prefs", Context.MODE_PRIVATE)
    private var stored = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty().toSet()
    private var optIn = prefs.getStringSet(KEY_OPT_IN, emptySet()).orEmpty().toSet()
    private val _disabled = MutableStateFlow(effective(stored, optIn))
    val disabled: StateFlow<Set<String>> = _disabled.asStateFlow()

    override fun isEnabled(name: String): Boolean = name !in _disabled.value

    fun setEnabled(name: String, enabled: Boolean) {
        if (name in DEFAULT_OFF) {
            optIn = if (enabled) optIn + name else optIn - name
            prefs.edit().putStringSet(KEY_OPT_IN, optIn).apply()
        } else {
            stored = if (enabled) stored - name else stored + name
            prefs.edit().putStringSet(KEY_DISABLED, stored).apply()
        }
        _disabled.value = effective(stored, optIn)
    }

    companion object {
        private const val KEY_DISABLED = "disabled"
        private const val KEY_OPT_IN = "opt_in"
        /** Tools that start switched off (live crypto trading). */
        val DEFAULT_OFF: Set<String> = setOf("crypto_place_order", "crypto_cancel_order")
        fun effective(disabled: Set<String>, optIn: Set<String>): Set<String> = (disabled - DEFAULT_OFF) + (DEFAULT_OFF - optIn)
    }
}

/** What [com.verdroid.app.agent.tools.ToolRegistry] needs (testable without Android). */
fun interface ToolSwitches {
    fun isEnabled(name: String): Boolean

    companion object { val ALL_ON = ToolSwitches { true } }
}
