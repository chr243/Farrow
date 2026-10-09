package com.farrow.app.data.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What termux_run and the package installer need from Termux (faked in unit tests). */
interface TermuxRunner {
    fun isInstalled(): Boolean
    fun hasRunCommandPermission(): Boolean
    /** Background `bash -lc` command + wait for its result (null = couldn't start or no answer within [timeoutMs]). */
    suspend fun runAndWait(command: String, tag: String, timeoutMs: Long = 8_000, label: String = "Farrow"): TermuxResult?
}

/**
 * Talks to the Termux app (com.termux) through its RunCommandService, which requires:
 *  1. our app to hold the runtime permission `com.termux.permission.RUN_COMMAND` (granted by the user), and
 *  2. `allow-external-apps = true` in `~/.termux/termux.properties` inside Termux.
 * Commands run in the background (no Termux window); results come back through [TermuxResultReceiver].
 */
@Singleton
class TermuxManager @Inject constructor(@ApplicationContext private val context: Context) : TermuxRunner {

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    override fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun runCommand(command: String, label: String, resultTag: String): Result<Unit> {
        if (!isInstalled()) return Result.failure(IllegalStateException("Termux is not installed"))
        if (!hasRunCommandPermission()) return Result.failure(SecurityException("RUN_COMMAND permission not granted"))
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, "$TERMUX_PREFIX/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_PENDING_INTENT, resultIntent(resultTag))
        }
        return runCatching {
            // Termux's RunCommandService calls startForeground itself, so startForegroundService is safe here.
            ContextCompat.startForegroundService(context, intent)
            Unit
        }
    }

    /** Termux fills the "result" bundle (stdout, stderr, exitCode, err, errmsg) into this PendingIntent; it must be MUTABLE. */
    private fun resultIntent(tag: String): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_TAG, tag)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, tag.hashCode(), intent, flags)
    }

    override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { TermuxResults.results.first { it.tag == tag } }
        if (runCommand(command, label, tag).isFailure) { waiter.cancel(); return@coroutineScope null }
        withTimeoutOrNull(timeoutMs) { waiter.await() }.also { if (it == null) waiter.cancel() }
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        /** Paste once into Termux so other apps (Farrow) may run commands there. */
        const val ALLOW_EXTERNAL_APPS_CMD = "mkdir -p ~/.termux && (grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings && echo OK"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    }
}
