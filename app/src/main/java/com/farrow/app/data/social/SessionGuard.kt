package com.farrow.app.data.social

import android.content.Context
import com.farrow.app.data.notify.AlertNotifier
import com.farrow.app.domain.model.NotificationType
import com.farrow.app.domain.repository.NotificationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges "a tool hit a login wall" to "pause the task": tools call [flag]; the agent loop calls [consumePending]
 * after the step's tools ran and pauses the task with PauseReason.SESSION_EXPIRED. The site stays recorded per task
 * (persisted) so the chat can show the right Re-login button.
 */
@Singleton
class SessionGuard @Inject constructor(
    @ApplicationContext context: Context,
    private val notifications: NotificationRepository,
    private val alerts: AlertNotifier,
) {
    private val prefs = context.getSharedPreferences("session_guard", Context.MODE_PRIVATE)

    suspend fun flag(taskId: Long, site: String, displayName: String, reason: String) {
        prefs.edit().putString(pendingKey(taskId), site).putString(siteKey(taskId), site).apply()
        val title = "$displayName session expired"
        val body = "Log in again (Re-login button in the chat), then resume the task. $reason".take(300)
        notifications.add(NotificationType.SESSION, title, body, taskId.takeIf { it != 0L })
        alerts.notify(NOTIFICATION_BASE + (taskId % 10_000).toInt(), title, body, taskId)
    }

    /** Returns and clears the site that expired during the current step, if any. */
    fun consumePending(taskId: Long): String? {
        val site = prefs.getString(pendingKey(taskId), null) ?: return null
        prefs.edit().remove(pendingKey(taskId)).apply()
        return site
    }

    /** Site whose re-login this task is waiting for (kept until a successful re-login). */
    fun expiredSiteFor(taskId: Long): String? = prefs.getString(siteKey(taskId), null)

    fun clearSite(site: String) {
        val editor = prefs.edit()
        prefs.all.forEach { (k, v) -> if (k.startsWith("site_") && v == site) editor.remove(k) }
        editor.apply()
    }

    private fun pendingKey(taskId: Long) = "pending_$taskId"
    private fun siteKey(taskId: Long) = "site_$taskId"

    companion object { private const val NOTIFICATION_BASE = 40_000 }
}
