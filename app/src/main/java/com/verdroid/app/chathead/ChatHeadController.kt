package com.verdroid.app.chathead

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.verdroid.app.domain.model.ChatHeadMode
import com.verdroid.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatHeadResult { BUBBLE_SHOWN, BUBBLES_BLOCKED, NOTIFICATIONS_BLOCKED, OVERLAY_STARTED, NEEDS_OVERLAY_PERMISSION }

/**
 * Decides between the Bubbles API and the SYSTEM_ALERT_WINDOW overlay.
 * AUTO = Bubbles when allowed and not on Xiaomi/Redmi/POCO (HyperOS/MIUI has unreliable bubble
 * support), otherwise the overlay chat head.
 */
@Singleton
class ChatHeadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val notifier: BubbleNotifier,
) {
    fun isXiaomiFamily(): Boolean {
        val names = setOf("xiaomi", "redmi", "poco")
        return Build.MANUFACTURER.lowercase() in names || Build.BRAND.lowercase() in names
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)
    fun bubblesAllowed(): Boolean = notifier.bubblesAllowed()
    fun notificationsEnabled(): Boolean = notifier.notificationsEnabled()

    suspend fun resolve(): ChatHeadMode = when (val mode = settings.chatHeadMode.first()) {
        ChatHeadMode.AUTO ->
            if (!isXiaomiFamily() && notificationsEnabled() && bubblesAllowed()) ChatHeadMode.BUBBLES else ChatHeadMode.OVERLAY
        else -> mode
    }

    suspend fun open(taskId: Long): ChatHeadResult = when (resolve()) {
        ChatHeadMode.OVERLAY ->
            if (!canDrawOverlays()) ChatHeadResult.NEEDS_OVERLAY_PERMISSION
            else { startOverlay(taskId); ChatHeadResult.OVERLAY_STARTED }
        else -> when {
            !notificationsEnabled() -> ChatHeadResult.NOTIFICATIONS_BLOCKED
            !notifier.show(taskId) -> ChatHeadResult.NOTIFICATIONS_BLOCKED
            !bubblesAllowed() -> ChatHeadResult.BUBBLES_BLOCKED
            else -> ChatHeadResult.BUBBLE_SHOWN
        }
    }

    fun startOverlay(taskId: Long) {
        ContextCompat.startForegroundService(context, ChatHeadService.startIntent(context, taskId))
    }

    fun overlayPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bubbleSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
