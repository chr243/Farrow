package com.verdroid.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.verdroid.app.data.local.*
import com.verdroid.app.data.network.ClientRateLimiter
import com.verdroid.app.data.network.KeyInfoParser
import com.verdroid.app.data.network.OpenRouterApi
import com.verdroid.app.data.settings.SettingsKeys
import com.verdroid.app.domain.model.*
import com.verdroid.app.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val taskDao: TaskDao,
    private val messageDao: MessageDao,
    private val toolCallDao: ToolCallDao,
    private val memoryDao: MemoryDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : TaskRepository {
    private fun now() = System.currentTimeMillis()
    private val chartsDir by lazy { java.io.File(context.filesDir, "charts") }

    override fun observeConversations() = taskDao.observeConversations().map { rows -> rows.map { Conversation(it.task.toDomain(), it.unreadCount) } }
    override fun observeTask(taskId: Long) = taskDao.observe(taskId).map { it?.toDomain() }
    override fun observeMessages(taskId: Long) = messageDao.observe(taskId).map { l -> l.map { it.toDomain() } }
    override fun observeToolCalls(taskId: Long) = toolCallDao.observe(taskId).map { l -> l.map { it.toDomain() } }
    override suspend fun getTask(taskId: Long) = taskDao.get(taskId)?.toDomain()
    override suspend fun getMessages(taskId: Long) = messageDao.getAll(taskId).map { it.toDomain() }

    override suspend fun createTask(title: String, prompt: String, type: TaskType): Long {
        val t = now()
        return taskDao.insert(TaskEntity(title = title, prompt = prompt, type = type.name, status = TaskStatus.QUEUED.name,
            subtitle = "Queued", createdAt = t, updatedAt = t, lastOpenedAt = t))
    }

    override suspend fun updateStatus(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, error: String?) =
        taskDao.updateStatus(taskId, status.name, subtitle, resumeAt, error, now())

    override suspend fun updateSubtitle(taskId: Long, subtitle: String) = taskDao.updateSubtitle(taskId, subtitle, now())
    override suspend fun updateCheckpoint(taskId: Long, step: Int, model: String?, checkpointJson: String) =
        taskDao.updateCheckpoint(taskId, step, model, checkpointJson)
    override suspend fun markStarted(taskId: Long) = taskDao.markStarted(taskId, now())
    override suspend fun markOpened(taskId: Long) = taskDao.markOpened(taskId, now())
    override suspend fun addMessage(message: ChatMessage) = messageDao.insert(message.toEntity().copy(id = 0))
    override suspend fun markSummarized(ids: List<Long>) { if (ids.isNotEmpty()) messageDao.markSummarized(ids) }
    override suspend fun addToolCall(record: ToolCallRecord) = toolCallDao.insert(record.toEntity().copy(id = 0))
    override suspend fun finishToolCall(id: Long, resultJson: String, status: ToolCallStatus) = toolCallDao.finish(id, resultJson, status.name, now())
    override suspend fun deleteTask(taskId: Long) {
        // Chart PNGs first (their paths live in the tool results that the cascade removes).
        val pngs = com.verdroid.app.agent.tools.ChartFiles.owned(toolCallDao.results(taskId, com.verdroid.app.agent.tools.ChartTool.NAME), chartsDir)
        taskDao.delete(taskId)
        pngs.forEach { runCatching { it.delete() } }
        // The chat's short-term memory goes with it (plus any orphans left by older deletes).
        memoryDao.clearChat(taskId); memoryDao.deleteOrphans()
    }
    override fun observeArchived() = taskDao.observeArchived().map { l -> l.map { it.toDomain() } }
    override suspend fun archive(taskId: Long) { taskDao.archive(taskId, now()) }
    override suspend fun restore(taskId: Long) { taskDao.restore(taskId) }
    override suspend fun archivedIds() = taskDao.archivedIds()
    override suspend fun pause(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, reason: PauseReason, attempt: Int) =
        taskDao.pause(taskId, status.name, subtitle, resumeAt, reason.name, attempt, now())
    override suspend fun setAttempt(taskId: Long, attempt: Int) = taskDao.setAttempt(taskId, attempt)
    override suspend fun tasksWithStatus(statuses: List<TaskStatus>) = taskDao.withStatus(statuses.map { it.name }).map { it.toDomain() }
}

@Singleton
class RateLimitRepositoryImpl @Inject constructor(private val dao: RateLimitEventDao) : RateLimitRepository {
    override fun observeEvents() = dao.observe().map { l -> l.map { it.toDomain() } }
    override suspend fun log(event: RateLimitEvent) { dao.insert(event.toEntity().copy(id = 0)) }
    override suspend fun clear() = dao.clear()
}

@Singleton
class NotificationRepositoryImpl @Inject constructor(private val dao: NotificationDao) : NotificationRepository {
    override fun observe() = dao.observe().map { l -> l.map { it.toDomain() } }
    override fun observeUnreadCount() = dao.observeUnread()
    override suspend fun add(type: NotificationType, title: String, body: String, taskId: Long?) {
        dao.insert(NotificationEntity(type = type.name, title = title, body = body, taskId = taskId, createdAt = System.currentTimeMillis()))
    }
    override suspend fun markAllRead() = dao.markAllRead()
    override suspend fun clear() = dao.clear()
}

@Serializable
private data class StoredQuota(
    val keyLabel: String, val used: Int? = null, val limit: Int? = null, val remaining: Int? = null,
    val usage: Double? = null, val creditLimit: Double? = null, val rateLimitRequests: Int? = null,
    val rateLimitInterval: String? = null, val isFreeTier: Boolean? = null, val estimated: Boolean = false, val fetchedAt: Long = 0,
)

@Singleton
class QuotaRepositoryImpl @Inject constructor(
    private val api: OpenRouterApi,
    private val keys: ApiKeyRepository,
    private val settings: SettingsRepository,
    private val limiter: ClientRateLimiter,
    private val store: DataStore<Preferences>,
    private val notifications: NotificationRepository,
) : QuotaRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val quota: Flow<QuotaInfo?> = store.data.map { p ->
        p[SettingsKeys.QUOTA_JSON]?.let { raw ->
            runCatching { json.decodeFromString<StoredQuota>(raw) }.getOrNull()?.let {
                QuotaInfo(it.keyLabel, it.used, it.limit, it.remaining, it.usage, it.creditLimit, it.rateLimitRequests,
                    it.rateLimitInterval, it.isFreeTier, it.estimated, it.fetchedAt)
            }
        }
    }

    override suspend fun refresh(): QuotaInfo? {
        val key = keys.orderedForUse().firstOrNull() ?: return null
        val limits = settings.currentLimits()
        val info = try {
            val resp = api.key("Bearer ${key.key}", key.displayName)
            val body = (if (resp.isSuccessful) resp.body() else resp.errorBody())?.string() ?: return null
            if (!resp.isSuccessful) return null
            KeyInfoParser.parse(body, key.displayName, limits.requestsPerDay, limiter.usedToday(key.id)) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val stored = StoredQuota(info.keyLabel, info.used, info.limit, info.remaining, info.usage, info.creditLimit,
            info.rateLimitRequests, info.rateLimitInterval, info.isFreeTier, info.estimated, info.fetchedAt)
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        var warn = false
        store.edit { p ->
            p[SettingsKeys.QUOTA_JSON] = json.encodeToString(stored)
            val remaining = info.remaining
            if (remaining != null && remaining < limits.lowQuotaThreshold && p[SettingsKeys.LAST_LOW_QUOTA_WARN_DAY] != today) {
                p[SettingsKeys.LAST_LOW_QUOTA_WARN_DAY] = today
                warn = true
            }
        }
        if (warn) {
            notifications.add(NotificationType.QUOTA, "Low free-tier quota",
                "${info.remaining} requests remaining today (limit: ${info.limit ?: limits.requestsPerDay}). Resets at 00:00 UTC.")
        }
        return info
    }
}
