package com.verdroid.app.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.verdroid.app.MainActivity
import com.verdroid.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** System notifications for things that need the user (finished tasks, setup needed). */
@Singleton
class AlertNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Action needed", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Finished tasks and setup alerts"
            })
        }
    }

    fun notify(id: Int, title: String, body: String, taskId: Long?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val intent = if (taskId != null && taskId != 0L) MainActivity.openTaskIntent(context, taskId)
            else context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = intent?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) {}
    }

    companion object { const val CHANNEL_ALERTS = "alerts" }
}
