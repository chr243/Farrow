package com.verdroid.app.chathead

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.verdroid.app.MainActivity
import com.verdroid.app.R
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Bubbles API: one long-lived conversation shortcut + MessagingStyle notification per task,
 * on a channel that allows bubbles, whose BubbleMetadata opens [BubbleActivity] embedded.
 * While a bubble is alive it is re-posted when the unread count / task state changes (unread badge).
 */
@Singleton
class BubbleNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TaskRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = HashMap<Long, Job>()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (nm.getNotificationChannel(CHANNEL_BUBBLES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BUBBLES, "Chat bubbles", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Floating chat bubbles for Farrow conversations"
                    setAllowBubbles(true)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CHAT_HEAD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT_HEAD, "Chat head overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the floating chat head overlay is active"
                    setShowBadge(false)
                }
            )
        }
    }

    fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** App-level bubble preference is not "none" and our bubble channel may bubble. */
    fun bubblesAllowed(): Boolean {
        ensureChannels()
        val appLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nm.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE
        } else {
            legacyBubblesAllowed()
        }
        val channel = nm.getNotificationChannel(CHANNEL_BUBBLES)
        return appLevel && (channel == null || channel.canBubble())
    }

    @Suppress("DEPRECATION")
    private fun legacyBubblesAllowed(): Boolean = nm.areBubblesAllowed()

    /** Posts (and auto-expands) the bubble for [taskId]. Returns false if notifications are blocked. */
    suspend fun show(taskId: Long): Boolean {
        if (!notificationsEnabled()) return false
        ensureChannels()
        val ok = post(taskId, autoExpand = true)
        if (ok) watch(taskId)
        return ok
    }

    fun dismiss(taskId: Long) {
        synchronized(watchers) { watchers.remove(taskId)?.cancel() }
        nm.cancel(notificationId(taskId))
    }

    private fun isPosted(taskId: Long): Boolean =
        nm.activeNotifications.any { it.id == notificationId(taskId) }

    private fun watch(taskId: Long) {
        synchronized(watchers) {
            if (watchers[taskId]?.isActive == true) return
            watchers[taskId] = scope.launch {
                tasks.observeConversations()
                    .map { list -> list.firstOrNull { it.task.id == taskId } }
                    .distinctUntilChanged { a, b -> a?.unreadCount == b?.unreadCount && a?.task?.updatedAt == b?.task?.updatedAt }
                    .drop(1)
                    // stop when the task is deleted or the user dismissed the bubble
                    .takeWhile { c -> c != null && isPosted(taskId) }
                    .collect { post(taskId, autoExpand = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun post(taskId: Long, autoExpand: Boolean): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        val unread = tasks.observeConversations().first().firstOrNull { it.task.id == taskId }?.unreadCount ?: 0
        val recent = tasks.getMessages(taskId)
            .filter { it.kind == MessageKind.NORMAL && (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && !it.content.isNullOrBlank() }
            .takeLast(6)

        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val bot = Person.Builder().setName("Farrow").setKey("farrow").setBot(true).setIcon(icon).build()
        val me = Person.Builder().setName("You").setKey("me").build()
        val shortcutId = shortcutId(taskId)
        val launchIntent = MainActivity.openTaskIntent(context, taskId)

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(task.title.take(24).ifBlank { "Farrow" })
            .setLongLabel(task.title.ifBlank { "Farrow" })
            .setIcon(icon)
            .setIntent(launchIntent)
            .setLongLived(true)
            .setPerson(bot)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(task.title)
        if (recent.isEmpty()) {
            style.addMessage(task.subtitle, task.updatedAt, bot)
        } else {
            recent.forEach { m ->
                val sender: Person? = if (m.role == MessageRole.USER) null else bot
                style.addMessage(com.verdroid.app.domain.model.AttachmentText.preview(m.content.orEmpty()).take(500), m.createdAt, sender)
            }
        }

        val bubbleIntent = PendingIntent.getActivity(
            context, taskId.toInt(), BubbleActivity.intent(context, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val bubble = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
            .setDesiredHeight(640)
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(true)
            .build()
        val contentIntent = PendingIntent.getActivity(
            context, taskId.toInt(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_BUBBLES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(task.subtitle)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .addPerson(bot)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBubbleMetadata(bubble)
            .setContentIntent(contentIntent)
            .setNumber(unread)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(task.updatedAt)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(notificationId(taskId), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_BUBBLES = "chat_bubbles"
        const val CHANNEL_CHAT_HEAD = "chat_head_overlay"
        fun shortcutId(taskId: Long) = "task_$taskId"
        fun notificationId(taskId: Long) = 10_000 + taskId.toInt()
    }
}
