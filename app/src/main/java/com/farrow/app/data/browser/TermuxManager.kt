package com.farrow.app.data.browser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Talks to the Termux app (com.termux). Commands are executed through Termux's RunCommandService, which requires:
 *  1. our app to hold the runtime permission `com.termux.permission.RUN_COMMAND` (granted by the user), and
 *  2. `allow-external-apps = true` in `~/.termux/termux.properties` inside Termux.
 * If either is missing the wizard falls back to copy-paste commands.
 */
@Singleton
class TermuxManager @Inject constructor(@ApplicationContext private val context: Context) {

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Runs [command] with `bash -lc` inside Termux. [background] = true runs without opening a terminal session
     * (output is not returned to us — use the bridge /health to verify results).
     */
    fun runCommand(
        command: String,
        background: Boolean = true,
        label: String = "Farrow",
        resultTag: String? = null,
    ): Result<Unit> {
        if (!isInstalled()) return Result.failure(IllegalStateException("Termux is not installed"))
        if (!hasRunCommandPermission()) return Result.failure(SecurityException("RUN_COMMAND permission not granted"))
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, "$TERMUX_PREFIX/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, background)
            // 0 = open a NEW session and switch to it (the wrapper's `read` keeps it open after the step ends).
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_LABEL, label)
            if (resultTag != null) putExtra(EXTRA_PENDING_INTENT, resultIntent(resultTag))
        }
        return runCatching {
            // Termux's RunCommandService calls startForeground itself, so startForegroundService is safe here.
            ContextCompat.startForegroundService(context, intent)
            Unit
        }
    }

    /**
     * Termux fills the "result" bundle (stdout, stderr, exitCode, err, errmsg) into this PendingIntent when the command
     * ends; it must be MUTABLE for that. Background commands return stdout; foreground sessions only report the exit
     * code once the session closes, so step progress is also polled from the status/log files.
     */
    private fun resultIntent(tag: String): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_TAG, tag)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, tag.hashCode(), intent, flags)
    }

    /** Runs a short background query in Termux; the output arrives on [TermuxResults.results] with [tag]. */
    fun query(command: String, tag: String): Result<Unit> = runCommand(command, background = true, label = "Farrow status", resultTag = tag)

    /** Background command + wait for its result PendingIntent (null = Termux didn't answer in time / couldn't run). */
    suspend fun runAndWait(command: String, tag: String, timeoutMs: Long = 8_000, label: String = "Farrow status"): TermuxResult? =
        kotlinx.coroutines.coroutineScope {
            val waiter = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { TermuxResults.results.first { it.tag == tag } }
            if (runCommand(command, background = true, label = label, resultTag = tag).isFailure) { waiter.cancel(); return@coroutineScope null }
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { waiter.await() }.also { if (it == null) waiter.cancel() }
        }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    }
}
