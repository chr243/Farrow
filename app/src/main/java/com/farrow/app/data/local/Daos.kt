package com.farrow.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """
        SELECT t.*, (SELECT COUNT(*) FROM messages m WHERE m.taskId = t.id AND m.role = 'assistant'
                     AND m.kind = 'NORMAL' AND m.createdAt > t.lastOpenedAt) AS unreadCount
        FROM tasks t ORDER BY t.updatedAt DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status, subtitle = :subtitle, resumeAt = :resumeAt, errorMessage = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, subtitle: String, resumeAt: Long?, error: String?, now: Long)

    @Query("UPDATE tasks SET subtitle = :subtitle, updatedAt = :now WHERE id = :id")
    suspend fun updateSubtitle(id: Long, subtitle: String, now: Long)

    @Query("UPDATE tasks SET currentStep = :step, model = :model, checkpointJson = :checkpoint WHERE id = :id")
    suspend fun updateCheckpoint(id: Long, step: Int, model: String?, checkpoint: String)

    @Query("UPDATE tasks SET startedAt = :now WHERE id = :id")
    suspend fun markStarted(id: Long, now: Long)

    @Query("UPDATE tasks SET lastOpenedAt = :now WHERE id = :id")
    suspend fun markOpened(id: Long, now: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tasks SET status = :status, subtitle = :subtitle, resumeAt = :resumeAt, pauseReason = :reason, attempt = :attempt, updatedAt = :now WHERE id = :id")
    suspend fun pause(id: Long, status: String, subtitle: String, resumeAt: Long?, reason: String, attempt: Int, now: Long)

    @Query("UPDATE tasks SET attempt = :attempt WHERE id = :id")
    suspend fun setAttempt(id: Long, attempt: Int)

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) ORDER BY updatedAt ASC")
    suspend fun withStatus(statuses: List<String>): List<TaskEntity>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY id ASC")
    fun observe(taskId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun getAll(taskId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET summarized = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY id ASC")
    fun observe(taskId: Long): Flow<List<ToolCallEntity>>

    @Insert
    suspend fun insert(entity: ToolCallEntity): Long

    @Query("UPDATE tool_calls SET resultJson = :result, status = :status, finishedAt = :now WHERE id = :id")
    suspend fun finish(id: Long, result: String, status: String, now: Long)
}

@Dao
interface RateLimitEventDao {
    @Query("SELECT * FROM rate_limit_events ORDER BY timestamp DESC LIMIT 500")
    fun observe(): Flow<List<RateLimitEventEntity>>

    @Insert
    suspend fun insert(e: RateLimitEventEntity): Long

    @Query("DELETE FROM rate_limit_events")
    suspend fun clear()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT 300")
    fun observe(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert suspend fun insert(m: MemoryEntity): Long
    @Update suspend fun update(m: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM memories WHERE chatId IS NULL")
    suspend fun clearGlobal()

    @Query("DELETE FROM memories WHERE chatId = :chatId")
    suspend fun clearChat(chatId: Long)

    /** Short-term memories whose chat no longer exists. */
    @Query("DELETE FROM memories WHERE chatId IS NOT NULL AND chatId NOT IN (SELECT id FROM tasks)")
    suspend fun deleteOrphans(): Int

    @Query("UPDATE memories SET chatId = :chatId, updatedAt = :now WHERE id = :id")
    suspend fun setChat(id: Long, chatId: Long?, now: Long): Int
}

@Database(
    entities = [TaskEntity::class, MessageEntity::class, ToolCallEntity::class, RateLimitEventEntity::class, NotificationEntity::class, MemoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class FarrowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun rateLimitEventDao(): RateLimitEventDao
    abstract fun notificationDao(): NotificationDao
    abstract fun memoryDao(): MemoryDao
}
