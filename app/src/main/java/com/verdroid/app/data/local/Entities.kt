package com.verdroid.app.data.local

import androidx.room.*

/**
 * Schema designed for Phase 3 durable pause/resume: tasks carry status, current step,
 * a checkpoint JSON blob and resumeAt; messages and tool calls are persisted individually.
 */
@Entity(tableName = "tasks", indices = [Index("status"), Index("updatedAt"), Index("archivedAt")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val type: String,
    val status: String,
    val subtitle: String,
    val currentStep: Int = 0,
    val checkpointJson: String? = null,
    val model: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val lastOpenedAt: Long = 0,
    val resumeAt: Long? = null,
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    val pauseReason: String? = null,
    /** v5 (v1.0.12): when the chat was moved to the archive; null = on the home list. */
    val archivedAt: Long? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val role: String,
    val content: String?,
    val kind: String,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val model: String?,
    val summarized: Boolean = false,
    val resumeAt: Long? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "tool_calls",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index("messageId")],
)
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val messageId: Long,
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val resultJson: String?,
    val status: String,
    val source: String,
    val startedAt: Long,
    val finishedAt: Long?,
)

@Entity(tableName = "rate_limit_events", indices = [Index("timestamp")])
data class RateLimitEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val taskId: Long?,
    val model: String,
    val keyLabel: String,
    val statusCode: Int,
    val limitHeader: String?,
    val remainingHeader: String?,
    val resetHeader: String?,
    val errorType: String?,
    val message: String,
    val outcome: String,
)

@Entity(tableName = "notifications", indices = [Index("createdAt")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val body: String,
    val taskId: Long?,
    val createdAt: Long,
    val read: Boolean = false,
)

data class ConversationRow(
    @Embedded val task: TaskEntity,
    val unreadCount: Int,
)

/** v3: agent memory — lasting facts about the user and their preferences (also exported to files/memory/MEMORY.md). */
@Entity(tableName = "memories", indices = [Index("updatedAt"), Index("chatId")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Comma-separated, lower-case. */
    @ColumnInfo(defaultValue = "") val tags: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** v4: null = long-term (global); a chat/task id = that chat's short-term scratchpad (deleted with the chat). */
    val chatId: Long? = null,
)
