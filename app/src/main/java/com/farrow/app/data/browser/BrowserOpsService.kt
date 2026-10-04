package com.farrow.app.data.browser

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.farrow.app.R
import com.farrow.app.keepalive.KeepAliveService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.0.7: foreground service that exists only while a [BrowserOpsManager] action runs (Set up everything, Start
 * browser, Reset browser, Update bridge, a setup step), so it survives the screen closing and the app going to the
 * background. Silent notification with the current step and Cancel; stops itself when the action ends.
 */
@AndroidEntryPoint
class BrowserOpsService : Service() {
    @Inject lateinit var manager: BrowserOpsManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        KeepAliveService.ensureChannel(this)
        startInForeground(build("Farrow", "Working…"))
        scope.launch {
            manager.state.collectLatest { st ->
                if (!st.busy) {
                    delay(1_000)
                    if (!manager.state.value.busy) stopNow()
                    return@collectLatest
                }
                notify(build(BrowserOpsNotifText.title(st), BrowserOpsNotifText.text(st)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) manager.cancel()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    @SuppressLint("MissingPermission")
    private fun notify(n: Notification) { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }

    private fun build(title: String, text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val cancel = PendingIntent.getService(this, 3, Intent(this, BrowserOpsService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, KeepAliveService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 7_003
        const val ACTION_CANCEL = "com.farrow.app.browser.CANCEL"

        /** Best effort: actions start from the visible screen, so the foreground start is allowed. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, BrowserOpsService::class.java)); true
        } catch (e: Exception) {
            false // ForegroundServiceStartNotAllowedException: the job still runs in the app scope.
        }
    }
}
