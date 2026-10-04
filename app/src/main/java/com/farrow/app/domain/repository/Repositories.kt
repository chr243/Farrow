package com.farrow.app.domain.repository

import com.farrow.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TaskRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeTask(taskId: Long): Flow<Task?>
    fun observeMessages(taskId: Long): Flow<List<ChatMessage>>
    fun observeToolCalls(taskId: Long): Flow<List<ToolCallRecord>>
    suspend fun getTask(taskId: Long): Task?
    suspend fun getMessages(taskId: Long): List<ChatMessage>
    suspend fun createTask(title: String, prompt: String, type: TaskType): Long
    suspend fun updateStatus(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long? = null, error: String? = null)
    suspend fun updateSubtitle(taskId: Long, subtitle: String)
    suspend fun updateCheckpoint(taskId: Long, step: Int, model: String?, checkpointJson: String)
    suspend fun markStarted(taskId: Long)
    suspend fun markOpened(taskId: Long)
    suspend fun addMessage(message: ChatMessage): Long
    suspend fun markSummarized(ids: List<Long>)
    suspend fun addToolCall(record: ToolCallRecord): Long
    suspend fun finishToolCall(id: Long, resultJson: String, status: ToolCallStatus)
    /** Permanent delete: messages, tool calls, the chat's short-term memory and its chart PNGs. */
    suspend fun deleteTask(taskId: Long)
    /** v1.0.12 archive: archived chats are hidden from [observeConversations] and never auto-resume. */
    fun observeArchived(): Flow<List<Task>>
    suspend fun archive(taskId: Long)
    suspend fun restore(taskId: Long)
    suspend fun archivedIds(): List<Long>
    /** Pause with a resume time (null = manual resume only), reason and backoff attempt counter. */
    suspend fun pause(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, reason: PauseReason, attempt: Int)
    suspend fun setAttempt(taskId: Long, attempt: Int)
    suspend fun tasksWithStatus(statuses: List<TaskStatus>): List<Task>
}

interface ApiKeyRepository {
    val keys: StateFlow<List<ApiKey>>
    suspend fun add(label: String, key: String)
    suspend fun remove(id: String)
    suspend fun move(id: String, delta: Int)
    suspend fun setPrimary(id: String)
    suspend fun rename(id: String, label: String)
    /** Primary first, then the rest in user order. */
    fun orderedForUse(): List<ApiKey>
}

interface SettingsRepository {
    val modelPriority: Flow<List<String>>
    val limits: Flow<LimitSettings>
    val chatHeadMode: Flow<ChatHeadMode>
    suspend fun setChatHeadMode(mode: ChatHeadMode)
    suspend fun currentModels(): List<String>
    suspend fun currentLimits(): LimitSettings
    suspend fun setModelPriority(models: List<String>)
    suspend fun resetModelPriority()
    suspend fun updateLimits(transform: (LimitSettings) -> LimitSettings)
}

interface RateLimitRepository {
    fun observeEvents(): Flow<List<RateLimitEvent>>
    suspend fun log(event: RateLimitEvent)
    suspend fun clear()
}

interface NotificationRepository {
    fun observe(): Flow<List<AppNotification>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun add(type: NotificationType, title: String, body: String, taskId: Long? = null)
    suspend fun markAllRead()
    suspend fun clear()
}

interface QuotaRepository {
    val quota: Flow<QuotaInfo?>
    /** Polls GET /api/v1/key for the primary key. Returns null if no key or on failure. */
    suspend fun refresh(): QuotaInfo?
}

/** Implemented by the agent package; lets the UI/domain start and stop agent runs. */
interface AgentController {
    val runningTaskIds: StateFlow<Set<Long>>
    /** Start (or continue from the last checkpoint) now, in-process. */
    fun start(taskId: Long)
    /** Stop the current generation (the task can be resumed). */
    fun stop(taskId: Long)
    /** Drop any scheduled backoff/resume and run immediately. */
    fun forceRetry(taskId: Long)
    /** Stop, drop scheduled resumes and mark the task CANCELLED. */
    fun cancel(taskId: Long)
}
