package com.farrow.app.domain.model

enum class TaskType { WEB, SOCIAL, SYSTEM, CHAT;
    companion object {
        /** Very small heuristic used to pick an avatar for a new task from its first prompt. */
        fun infer(prompt: String): TaskType {
            val p = prompt.lowercase()
            return when {
                listOf("http", "www.", "website", "browse", "scrape", "web ", "url").any { it in p } -> WEB
                listOf("twitter", "instagram", "facebook", "tiktok", "reddit", "post on", "social").any { it in p } -> SOCIAL
                listOf("file", "shell", "git", "folder", "directory", "script", "system", "workspace").any { it in p } -> SYSTEM
                else -> CHAT
            }
        }
    }
}

enum class TaskStatus {
    QUEUED, RUNNING, PAUSED, RATE_LIMITED, COMPLETED, FAILED, CANCELLED, IDLE;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == RATE_LIMITED || this == QUEUED
}

data class Task(
    val id: Long,
    val title: String,
    val prompt: String,
    val type: TaskType,
    val status: TaskStatus,
    val subtitle: String,
    val currentStep: Int,
    val checkpointJson: String?,
    val model: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val lastOpenedAt: Long,
    val resumeAt: Long?,
    val errorMessage: String?,
    /** Consecutive pause/backoff attempts since the last successful step (Phase 3). */
    val attempt: Int = 0,
    /** [PauseReason] name when paused / rate-limited. */
    val pauseReason: String? = null,
)

/** Why a task is paused; drives the system message and whether it auto-resumes. */
enum class PauseReason(val autoResume: Boolean) {
    DAILY_QUOTA(true), RATE_LIMIT(true), TRANSIENT(true), INTERRUPTED(true),
    MAX_STEPS(false), USER(false), SESSION_EXPIRED(false);

    companion object {
        fun of(name: String?): PauseReason? = entries.firstOrNull { it.name == name }
    }
}

data class Conversation(val task: Task, val unreadCount: Int) {
    val hasUnread: Boolean get() = unreadCount > 0
}

enum class MessageRole(val apiName: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");
}

enum class MessageKind {
    /** Normal chat turn sent to the model. */
    NORMAL,
    /** Centered status line shown to the user only (not sent to the model). */
    STATUS,
    /** Rolling summary of older turns (sent to the model as a system message). */
    SUMMARY,
}

data class ChatMessage(
    val id: Long,
    val taskId: Long,
    val role: MessageRole,
    val content: String?,
    val kind: MessageKind,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val model: String?,
    val summarized: Boolean,
    val resumeAt: Long?,
    val createdAt: Long,
)

enum class ToolCallStatus { PENDING, SUCCESS, ERROR }

data class ToolCallRecord(
    val id: Long,
    val taskId: Long,
    val messageId: Long,
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val resultJson: String?,
    val status: ToolCallStatus,
    /** "native" (OpenAI tool_calls) or "fenced" (JSON in a ```json block). */
    val source: String,
    val startedAt: Long,
    val finishedAt: Long?,
)

data class ApiKey(
    val id: String,
    val label: String,
    val key: String,
    val isPrimary: Boolean,
) {
    val masked: String get() = mask(key)
    val displayName: String get() = label.ifBlank { masked }

    companion object {
        fun mask(key: String): String =
            if (key.length <= 10) "••••" else key.take(8) + "••••" + key.takeLast(4)
    }
}

data class LimitSettings(
    val requestsPerMinute: Int = 50,
    val requestsPerDay: Int = 1000,
    val modelCooldownSeconds: Int = 60,
    val lowQuotaThreshold: Int = 100,
    val contextBudgetTokens: Int = 16_000,
    val maxSteps: Int = 25,
)

data class QuotaInfo(
    val keyLabel: String,
    val used: Int?,
    val limit: Int?,
    val remaining: Int?,
    val usage: Double?,
    val creditLimit: Double?,
    val rateLimitRequests: Int?,
    val rateLimitInterval: String?,
    val isFreeTier: Boolean?,
    /** true when remaining was estimated locally because the API didn't return free_model_daily_requests */
    val estimated: Boolean,
    val fetchedAt: Long,
)

data class RateLimitEvent(
    val id: Long,
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

enum class NotificationType { RATE_LIMIT, COMPLETED, FAILED, QUOTA, SESSION }

data class AppNotification(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val body: String,
    val taskId: Long?,
    val createdAt: Long,
    val read: Boolean,
)

/** How the floating chat head is shown: Android Bubbles API, a SYSTEM_ALERT_WINDOW overlay, or auto-detect. */
enum class ChatHeadMode { AUTO, BUBBLES, OVERLAY }

object DefaultModels {
    /**
     * Default priority order (tried top to bottom). `openrouter/free` is the OpenRouter auto-router for free models;
     * it is deliberately NOT last, and no code treats the last entry as a special "final fallback".
     */
    val list = listOf(
        "kilo:kilo-auto/free",
        "inclusionai/ling-3.0-flash-sante:free",
        "openrouter/free",
        "openai/gpt-oss-120b:free",
        "qwen/qwen3-coder:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "google/gemma-3-27b-it:free",
    )

    /** Defaults shipped up to v0.2.x; installs whose saved list equals this were never customised. */
    val legacyV1 = listOf(
        "openai/gpt-oss-120b:free",
        "qwen/qwen3-coder:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "google/gemma-3-27b-it:free",
        "openrouter/free",
    )

    /** Current version of the defaults (bumped whenever [list] changes). */
    const val VERSION = 3

    /** Parses the stored newline-separated list the same way the settings repository does. */
    fun parse(stored: String?): List<String>? = stored?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** True when a stored list is just an old default (never customised) and should be dropped for the new defaults. */
    fun isUncustomisedLegacy(stored: String?): Boolean = parse(stored) == legacyV1
}
