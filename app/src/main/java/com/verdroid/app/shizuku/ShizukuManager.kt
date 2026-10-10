package com.verdroid.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.verdroid.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, PRE_V11, NO_PERMISSION, READY }

/**
 * Shizuku integration (dev.rikka.shizuku:api 13.x): availability/permission checks and a UserService
 * ([ShellUserService], AIDL [IShellService]) that runs shell commands as the shell (adb) user.
 * `Shizuku.newProcess` is private since API 13, so the UserService route is used.
 */
@Singleton
class ShizukuManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IShellService? = null
    private val bindMutex = Mutex()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { service = null; refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun computeState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> if (isInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            Shizuku.isPreV11() -> ShizukuState.PRE_V11
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
    } catch (_: Throwable) {
        if (isInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    fun refresh(): ShizukuState = computeState().also { _state.value = it }

    /** Shows Shizuku's permission dialog (result arrives through the listener → [state]). */
    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) }
    }

    /**
     * UserService args. Component = this APK's real package + service class (Shizuku starts it with app_process from
     * our APK). debuggable(false): with true Shizuku adds JDWP flags to app_process, which fails to start the service
     * on some ROMs — a likely cause of "Could not bind". version() changes every release so a stale service is replaced.
     */
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
            // Own tag per build: a stale record left by an earlier failed start can't capture the new bind.
            .tag("farrow-shell-${BuildConfig.VERSION_CODE}")
    }

    /** Server facts for the card / error messages (permission is separate from whether the service process starts). */
    // getServerPatchVersion is @RestrictTo(LIBRARY_GROUP_PREFIX) in Shizuku 13 but is the only way to show the patch level.
    @android.annotation.SuppressLint("RestrictedApi")
    fun serverInfo(): String = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching "Shizuku binder not available"
        val uid = Shizuku.getUid()
        "server v${Shizuku.getVersion()}.${Shizuku.getServerPatchVersion()} (client API ${Shizuku.getLatestServiceVersion()}), " +
            "running as ${when (uid) { 0 -> "root"; 2000 -> "shell (adb)"; else -> "uid $uid" }}, SELinux ${runCatching { Shizuku.getSELinuxContext() }.getOrNull() ?: "?"}"
    }.getOrElse { "server info unavailable: ${it.message}" }

    /** Last binding problem (exception text, null binder, timeout) — shown in the device card. */
    @Volatile var lastBindError: String? = null; private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "UserService connected: $name binder=${binder != null}")
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            if (service == null) lastBindError = "UserService connected with a dead/null binder"
            pendingBind?.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { Log.w(TAG, "UserService disconnected"); service = null }
        override fun onBindingDied(name: ComponentName?) { lastBindError = "UserService binding died"; pendingBind?.complete(null) }
        override fun onNullBinding(name: ComponentName?) { lastBindError = "UserService returned a null binding"; pendingBind?.complete(null) }
    }
    @Volatile private var pendingBind: CompletableDeferred<IShellService?>? = null

    /** Waits (briefly) for Shizuku's binder to arrive — bindUserService before that silently does nothing. */
    private suspend fun awaitBinder(timeoutMs: Long = 3_000): Boolean {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        val got = CompletableDeferred<Unit>()
        val l = Shizuku.OnBinderReceivedListener { got.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(l)
        return try { withTimeoutOrNull(timeoutMs) { got.await() } != null } finally { Shizuku.removeBinderReceivedListener(l) }
    }

    private suspend fun ensureService(): IShellService? = bindMutex.withLock {
        service?.let { if (it.asBinder().pingBinder()) return@withLock it }
        if (!awaitBinder()) { lastBindError = "Shizuku binder not received"; return@withLock null }
        if (refresh() != ShizukuState.READY) return@withLock null
        lastBindError = null
        // Attempt 1: normal bind. Attempt 2: remove any stale/half-started service record first, then bind again.
        for (attempt in 1..2) {
            if (attempt == 2) {
                Log.w(TAG, "bind attempt 1 failed (${lastBindError}); removing the user service and retrying")
                withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) } }
                delay(500)
            }
            val deferred = CompletableDeferred<IShellService?>()
            pendingBind = deferred
            try {
                withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, connection) }
            } catch (t: Throwable) {
                Log.e(TAG, "bindUserService failed", t)
                lastBindError = "bindUserService threw ${t.javaClass.simpleName}: ${t.message}"
                pendingBind = null
                continue
            }
            val timeout = if (attempt == 1) BIND_TIMEOUT_MS else RETRY_TIMEOUT_MS
            val svc = withTimeoutOrNull(timeout) { deferred.await() }
            pendingBind = null
            if (svc != null) { lastBindError = null; Log.i(TAG, "UserService bound on attempt $attempt"); return@withLock svc }
            if (lastBindError == null) lastBindError = "UserService process did not connect within ${timeout / 1000} s"
        }
        lastBindError = "$lastBindError — ${serverInfo()}." + bindFailureLogcat()
        null
    }

    /** On bind failure: grab the relevant logcat lines through newProcess (works as uid 2000) and attach them. */
    private suspend fun bindFailureLogcat(): String = runCatching {
        val r = execViaNewProcess(
            "logcat -d -v time -t 3000 2>/dev/null | grep -iE 'UserService|Shizuku|farrow|AndroidRuntime|FATAL|app_process' | grep -viE 'ActivityManagerWrapper|RecentsTaskLoader|ScnModule' | tail -n 25",
            null, 15_000)
        r.stdout.trim().takeIf { it.isNotEmpty() }?.let { "\nlogcat (via newProcess):\n$it" } ?: "\n(logcat via newProcess: no matching lines)"
    }.getOrElse { "\n(logcat via newProcess failed: ${it.message})" }

    private fun notReadyMessage(): String = when (_state.value) {
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed (Settings > Shizuku setup)"
        ShizukuState.NOT_RUNNING -> "Shizuku is not running — start it from the Shizuku app (wireless debugging)"
        ShizukuState.PRE_V11 -> "Shizuku is too old (pre-v11); update it"
        ShizukuState.NO_PERMISSION -> "Farrow has no Shizuku permission — grant it in Settings > Shizuku setup"
        ShizukuState.READY -> "Could not bind the Shizuku shell service" + (lastBindError?.let { ": $it" } ?: "")
    }

    /** Runs a command through the Shizuku UserService; returns the service's JSON or throws IllegalStateException. */
    suspend fun exec(command: String, workDir: String?, timeoutMs: Long): String {
        val svc = ensureService() ?: throw IllegalStateException(notReadyMessage())
        return withContext(Dispatchers.IO) { svc.exec(command, workDir ?: "", timeoutMs) }
    }

    /**
     * Fallback: Shizuku.newProcess (private static since API 13, still present in 13.1.x) via reflection, running
     * `sh -c command` as the Shizuku user. Throws IllegalStateException when unavailable.
     */
    suspend fun execViaNewProcess(command: String, workDir: String?, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (refresh() != ShizukuState.READY) throw IllegalStateException(notReadyMessage())
        val m = try {
            Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
        } catch (t: Throwable) { throw IllegalStateException("Shizuku.newProcess not available: ${t.javaClass.simpleName}") }
        val dir = workDir?.takeIf { it.isNotBlank() }
        val p = try {
            m.invoke(null, arrayOf("sh", "-c", command), null, dir) as Process
        } catch (t: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("Shizuku.newProcess failed: ${t.targetException?.message ?: t.targetException}")
        }
        val out = StringBuilder(); val err = StringBuilder()
        val tOut = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 200_000) out.appendLine(it) } } }
        val tErr = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (err.length < 200_000) err.appendLine(it) } } }
        tOut.start(); tErr.start()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, 600_000)
        var code: Int? = null
        while (code == null && System.currentTimeMillis() < deadline) {
            code = runCatching { p.exitValue() }.getOrNull()
            if (code == null) delay(100)
        }
        if (code == null) runCatching { p.destroy() }
        tOut.join(2_000); tErr.join(2_000)
        ShellResult(BACKEND_NEW_PROCESS, code ?: 124, out.toString(), err.toString(), timedOut = code == null)
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4242
        private const val BIND_TIMEOUT_MS = 12_000L
        private const val RETRY_TIMEOUT_MS = 10_000L
        private const val TAG = "ShizukuManager"
        const val BACKEND_SERVICE = "shizuku-service"
        const val BACKEND_NEW_PROCESS = "shizuku-newProcess"
    }
}
