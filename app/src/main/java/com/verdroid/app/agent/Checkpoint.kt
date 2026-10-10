package com.verdroid.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable progress record stored in tasks.checkpointJson (Phase 3). Messages and tool calls are
 * persisted separately in Room; the checkpoint records where the loop is so a resumed run (after a
 * pause, crash or reboot) continues instead of starting over.
 */
@Serializable
data class TaskCheckpoint(
    val version: Int = 2,
    val step: Int = 0,
    val completedSteps: List<CompletedStep> = emptyList(),
    val pendingStep: PendingStep? = null,
    val intermediateResults: List<IntermediateResult> = emptyList(),
    val lastMessageId: Long = 0,
    val model: String? = null,
    val phase: String = "new",
    val at: Long = 0,
)

@Serializable
data class CompletedStep(val step: Int, val model: String?, val toolCalls: List<String>, val finishedAt: Long)

@Serializable
data class PendingStep(val step: Int, val phase: String, val toolCallIds: List<String> = emptyList(), val startedAt: Long)

@Serializable
data class IntermediateResult(val step: Int, val tool: String, val preview: String, val isError: Boolean)

object CheckpointCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val MAX_COMPLETED = 50
    const val MAX_RESULTS = 30

    /** Lenient decode; v1 checkpoints (or garbage) only keep the step number. */
    fun decode(raw: String?, fallbackStep: Int): TaskCheckpoint =
        raw?.let { runCatching { json.decodeFromString<TaskCheckpoint>(it) }.getOrNull() }
            ?.let { if (it.version < 2) TaskCheckpoint(step = fallbackStep) else it }
            ?: TaskCheckpoint(step = fallbackStep)

    fun encode(cp: TaskCheckpoint): String = json.encodeToString(
        cp.copy(
            completedSteps = cp.completedSteps.takeLast(MAX_COMPLETED),
            intermediateResults = cp.intermediateResults.takeLast(MAX_RESULTS),
        )
    )
}
