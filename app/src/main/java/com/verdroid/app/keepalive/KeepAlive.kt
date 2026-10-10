package com.verdroid.app.keepalive

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.verdroid.app.R
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground service that runs ONLY while an agent task is working (v0.9.19). It shows "Working on: <chat title>" with
 * the current step, a Stop action and a tap that opens the chat; it stops itself (removing the notification) as soon as
 * no task is running (finished, paused, rate-limited or failed). While idle there is no service and no notification.
 * Type is `specialUse` (dataSync has a 6h/day cap on Android 15).
 */
@AndroidEntryPoint
class KeepAliveService : Service() {
    @Inject lateinit var agent: AgentController
    @Inject lateinit var tasks: TaskRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification(WorkingInfo(null, "Verdroid", "Starting…", 0)))
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        scope.launch {
            agent.runningTaskIds.flatMapLatest { ids ->
                val id = ids.firstOrNull() ?: return@flatMapLatest flowOf<WorkingInfo?>(null)
                tasks.observeTask(id).map { t -> WorkingInfo(id, t?.title ?: "chat", t?.subtitle.orEmpty(), ids.size - 1) }
            }.collectLatest { info ->
                if (info == null) {
                    // Small grace so a task handing over to the next step/worker doesn't flicker the notification.
                    delay(1_500)
                    if (agent.runningTaskIds.value.isEmpty()) stopNow()
                } else notifyWorking(info)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopNow(); return START_NOT_STICKY }
            ACTION_STOP_TASK -> intent.getLongExtra(EXTRA_TASK_ID, 0L).takeIf { it != 0L }?.let { agent.stop(it) }
        }
        // Not sticky: after a kill we come back only through a running task (BootReceiver / AgentScheduler.recover()).
        return START_NOT_STICKY
    }

    private fun stopNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyWorking(info: WorkingInfo) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(info))
    }

    private fun buildNotification(info: WorkingInfo): Notification {
        val open = (if (info.taskId != null) com.verdroid.app.MainActivity.openTaskIntent(this, info.taskId)
            else packageManager.getLaunchIntentForPackage(packageName))?.let {
            PendingIntent.getActivity(this, (info.taskId ?: 0L).toInt(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(WorkingText.title(info.title))
            .setContentText(WorkingText.text(info.step, info.others))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (open != null) setContentIntent(open) }
        if (info.taskId != null) {
            val stop = PendingIntent.getService(this, 1, Intent(this, KeepAliveService::class.java)
                .setAction(ACTION_STOP_TASK).putExtra(EXTRA_TASK_ID, info.taskId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "Stop", stop)
        }
        return b.build()
    }

    private data class WorkingInfo(val taskId: Long?, val title: String, val step: String, val others: Int)

    companion object {
        /** v0.9.19: new low-importance channel (visible, silent) for the "Working on" notification. */
        const val CHANNEL_ID = "agent_working"
        const val NOTIFICATION_ID = 7_001
        const val ACTION_STOP = "com.verdroid.app.keepalive.STOP"
        const val ACTION_STOP_TASK = "com.verdroid.app.keepalive.STOP_TASK"
        const val EXTRA_TASK_ID = "taskId"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching { nm.deleteNotificationChannel("keep_alive") } // old idle "Ready in the background" channel
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent working", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown only while an agent task is running, with its current step and a Stop button."
                    setShowBadge(false)
                })
            }
        }
    }
}

/** Notification texts (pure, unit-tested). */
object WorkingText {
    fun title(chatTitle: String) = "Working on: ${chatTitle.ifBlank { "chat" }.take(60)}"
    fun text(step: String, others: Int): String =
        step.ifBlank { "Working…" } + if (others > 0) " (+$others more task${if (others > 1) "s" else ""})" else ""
}

/**
 * Starts [KeepAliveService] while a task is running (if the setting is on, default ON) and stops it when idle; also
 * offers battery & autostart helpers. Chat heads don't need it: Bubbles are notification-based and the overlay has its
 * own service, so they keep working while idle (Android may still kill the idle app).
 */
@Singleton
class KeepAliveController @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("keep_alive", Context.MODE_PRIVATE)

    /** "Keep tasks alive while working" — default ON (the service only exists while a task runs). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_WHILE_RUNNING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WHILE_RUNNING, value).apply()
            if (!value) stop() else if (lastRunning.isNotEmpty()) start()
        }

    @Volatile private var lastRunning: Set<Long> = emptySet()

    /** Follow the running tasks: start the service when one starts, stop it when none is running. */
    fun watch(running: kotlinx.coroutines.flow.StateFlow<Set<Long>>, scope: CoroutineScope) {
        scope.launch(Dispatchers.Main) {
            running.collect { ids ->
                val was = lastRunning
                lastRunning = ids
                when (KeepAlivePolicy.decide(was.isNotEmpty(), ids.isNotEmpty(), enabled)) {
                    KeepAlivePolicy.Action.START -> start()
                    KeepAlivePolicy.Action.STOP -> Unit // the service stops itself after a short grace period
                    KeepAlivePolicy.Action.NONE -> Unit
                }
            }
        }
    }

    private fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java)); true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (Android 12+) when started from the background without an exemption.
        false
    }

    fun stop() {
        context.stopService(Intent(context, KeepAliveService::class.java))
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Direct exemption prompt (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); falls back to the settings list. */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(): Intent {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** HyperOS/MIUI autostart manager; falls back to the app's details page. */
    fun openAutostartSettings(launch: (Intent) -> Unit) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (i in candidates) {
            try { launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
    }

    private companion object { const val KEY_WHILE_RUNNING = "while_running" }
}

/** Pure start/stop decision (unit-tested). */
object KeepAlivePolicy {
    enum class Action { START, STOP, NONE }
    fun decide(wasRunning: Boolean, isRunning: Boolean, enabled: Boolean): Action = when {
        isRunning && enabled && !wasRunning -> Action.START
        !isRunning && wasRunning -> Action.STOP
        else -> Action.NONE
    }
}
