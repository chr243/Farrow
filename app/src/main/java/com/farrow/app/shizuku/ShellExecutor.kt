package com.farrow.app.shizuku

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(val backend: String, val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

/** What the last run_shell attempt used, plus the per-backend errors that led there. */
data class ShellBackendStatus(val active: String? = null, val errors: Map<String, String> = emptyMap())

/**
 * run_shell backends, tried in order:
 *  1. Shizuku.newProcess via reflection — primary, fast and reliable on Shizuku 13.x
 *  2. Shizuku UserService ([ShellUserService]) — fallback, its bind can take 20+ s to fail
 * (v0.9.9: the ADB-over-TCP backend was removed.)
 * The backend that answered is remembered and shown in Settings > Shizuku & Git setup.
 */
@Singleton
class ShellExecutor @Inject constructor(private val shizuku: ShizukuManager) {
    private val _status = MutableStateFlow(ShellBackendStatus())
    val status: StateFlow<ShellBackendStatus> = _status.asStateFlow()

    suspend fun exec(command: String, workDir: String?, timeoutMs: Long): ShellResult {
        val errors = LinkedHashMap<String, String>()
        suspend fun attempt(name: String, block: suspend () -> ShellResult): ShellResult? = try {
            block().also { _status.value = ShellBackendStatus(active = it.backend, errors = errors.toMap()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            errors[name] = e.message ?: e.javaClass.simpleName
            null
        }
        attempt(ShizukuManager.BACKEND_NEW_PROCESS) { shizuku.execViaNewProcess(command, workDir, timeoutMs) }?.let { return it }
        attempt(ShizukuManager.BACKEND_SERVICE) {
            val o = JSONObject(shizuku.exec(command, workDir, timeoutMs))
            if (o.has("error")) throw IllegalStateException(o.getString("error"))
            ShellResult(ShizukuManager.BACKEND_SERVICE, o.optInt("exit_code", -1), o.optString("stdout"), o.optString("stderr"), o.optBoolean("timed_out"))
        }?.let { return it }
        _status.value = ShellBackendStatus(active = null, errors = errors.toMap())
        throw IllegalStateException("No shell backend available:\n" + errors.entries.joinToString("\n") { "• ${it.key}: ${it.value}" })
    }

    /**
     * Shizuku UserService diagnosis through a backend that doesn't need it (Shizuku.newProcess):
     * is the :shell process alive, and what did logcat say while Shizuku tried to start it.
     */
    suspend fun diagnoseShizuku(): String {
        val cmd = "echo '--- processes'; ps -A -o PID,USER,NAME 2>/dev/null | grep -iE 'farrow|shizuku' ; " +
            "echo '--- logcat'; logcat -d -v time -t 4000 2>/dev/null | grep -iE 'UserService|Shizuku|farrow|AndroidRuntime|FATAL|app_process' | grep -viE 'ActivityManagerWrapper|RecentsTaskLoader|ScnModule' | tail -n 80"
        val r = runCatching { shizuku.execViaNewProcess(cmd, null, 20_000) }
        return "${shizuku.serverInfo()}\nLast bind error: ${shizuku.lastBindError ?: "none"}\n" + r.fold(
            { "[${it.backend}]\n${it.stdout.trim()}${it.stderr.trim().takeIf { e -> e.isNotEmpty() }?.let { e -> "\n$e" } ?: ""}" },
            { "Could not read logcat: ${it.message}" })
    }

    companion object { private const val TAG = "ShellExecutor" }
}
