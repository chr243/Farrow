package com.farrow.app.data.tools

import com.farrow.app.data.browser.TermuxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.0.7: `pkg install` (up to 15 min) runs here in an app-lifetime scope instead of the Tools screen's ViewModel, so
 * leaving the screen doesn't drop the result. The screen merges [jobs] into its rows.
 */
@Singleton
class TermuxPackageJobs @Inject constructor(private val termux: TermuxManager) {
    data class Job(val running: Boolean, val ok: Boolean = false, val detail: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    /** Package name → its last/ongoing install. */
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    fun install(p: TermuxPackage): Boolean {
        if (_jobs.value[p.pkg]?.running == true) return false
        _jobs.update { it + (p.pkg to Job(running = true, detail = "Installing in Termux (background)…")) }
        scope.launch {
            val r = runCatching {
                termux.runAndWait(TermuxPackages.installScript(p), "install-${p.pkg}-${System.nanoTime()}", 15 * 60_000L, label = "Farrow: pkg install ${p.pkg}")
            }.getOrNull()
            val ok = r?.stdout?.contains("INSTALLED=1") == true
            val detail = if (ok) null else (r?.stdout?.lines()?.filter { l -> l.isNotBlank() && !l.startsWith("RESULT=") && !l.startsWith("INSTALLED=") }
                ?.takeLast(6)?.joinToString("\n")?.ifBlank { null } ?: r?.errmsg ?: "No answer from Termux within 15 min")
            _jobs.update { it + (p.pkg to Job(running = false, ok = ok, detail = detail)) }
        }
        return true
    }
}
