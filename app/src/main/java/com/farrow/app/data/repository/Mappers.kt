package com.farrow.app.data.repository

import com.farrow.app.data.local.*
import com.farrow.app.domain.model.*

private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

/** Titles saved before v1.0.26 could be the raw "Attached file: Input/…" line; the UI always gets a clean one. */
fun TaskEntity.toDomain() = Task(
    id, AttachmentText.cleanTitle(title, prompt), prompt, enumOr(type, TaskType.CHAT), enumOr(status, TaskStatus.IDLE), subtitle, currentStep,
    checkpointJson, model, createdAt, updatedAt, startedAt, lastOpenedAt, resumeAt, errorMessage, attempt, pauseReason, archivedAt,
)

fun MessageEntity.toDomain() = ChatMessage(
    id, taskId, MessageRole.entries.firstOrNull { it.apiName == role } ?: MessageRole.SYSTEM, content,
    enumOr(kind, MessageKind.NORMAL), toolCallsJson, toolCallId, toolName, model, summarized, resumeAt, createdAt,
)

fun ChatMessage.toEntity() = MessageEntity(
    id, taskId, role.apiName, content, kind.name, toolCallsJson, toolCallId, toolName, model, summarized, resumeAt, createdAt,
)

fun ToolCallEntity.toDomain() = ToolCallRecord(
    id, taskId, messageId, callId, name, argumentsJson, resultJson, enumOr(status, ToolCallStatus.PENDING), source, startedAt, finishedAt,
)

fun ToolCallRecord.toEntity() = ToolCallEntity(
    id, taskId, messageId, callId, name, argumentsJson, resultJson, status.name, source, startedAt, finishedAt,
)

fun RateLimitEventEntity.toDomain() = RateLimitEvent(
    id, timestamp, taskId, model, keyLabel, statusCode, limitHeader, remainingHeader, resetHeader, errorType, message, outcome,
)

fun RateLimitEvent.toEntity() = RateLimitEventEntity(
    id, timestamp, taskId, model, keyLabel, statusCode, limitHeader, remainingHeader, resetHeader, errorType, message, outcome,
)

fun NotificationEntity.toDomain() = AppNotification(id, enumOr(type, NotificationType.COMPLETED), title, body, taskId, createdAt, read)
