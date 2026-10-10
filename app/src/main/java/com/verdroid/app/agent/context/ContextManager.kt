package com.verdroid.app.agent.context

import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.OpenRouterClient
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.repository.SettingsRepository
import com.verdroid.app.domain.repository.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling summarisation: when the live history exceeds ~60% of the context budget the oldest turns
 * are summarised (using the same model priority list) into a SUMMARY message; the originals are
 * flagged `summarized` so they stay visible in the UI but are no longer sent to the model.
 */
@Singleton
class ContextManager @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val client: OpenRouterClient,
) {
    /** Live (not yet summarised) messages that are sent to the model. */
    fun liveMessages(all: List<ChatMessage>) = live(all)

    companion object {
        fun live(all: List<ChatMessage>) = all.filter { it.kind != MessageKind.STATUS && !it.summarized }
    }

    fun tokensOf(m: ChatMessage) = ContextPolicy.estimateTokens(m.content) + ContextPolicy.estimateTokens(m.toolCallsJson)

    suspend fun maybeSummarize(taskId: Long, systemPrompt: String, onStatus: suspend (String) -> Unit): Boolean {
        val budget = settings.currentLimits().contextBudgetTokens
        val live = liveMessages(tasks.getMessages(taskId))
        val total = ContextPolicy.estimateTokens(systemPrompt) + live.sumOf { tokensOf(it) }
        if (!ContextPolicy.shouldSummarize(total, budget)) return false

        // Older summaries come first in the prompt and get merged into the new one.
        val ordered = live.filter { it.kind == MessageKind.SUMMARY } + live.filter { it.kind != MessageKind.SUMMARY }
        val ids = ContextPolicy.selectForSummary(ordered.map { ContextPolicy.Item(it.id, it.role == MessageRole.TOOL, tokensOf(it)) })
        if (ids.isEmpty()) return false
        val idSet = ids.toSet()
        val toSummarize = ordered.filter { it.id in idSet }

        val transcript = buildString {
            toSummarize.forEach { m ->
                val who = when {
                    m.kind == MessageKind.SUMMARY -> "PREVIOUS SUMMARY"
                    m.role == MessageRole.TOOL -> "TOOL RESULT (${m.toolName})"
                    else -> m.role.apiName.uppercase()
                }
                append(who).append(": ").append(m.content.orEmpty().take(4000))
                m.toolCallsJson?.let { append("\n[tool calls] ").append(it.take(2000)) }
                append("\n\n")
            }
        }.take(budget * 4 / 2)

        onStatus("Summarizing earlier conversation…")
        val outcome = client.complete(
            listOf(
                ApiMessage("system", "You compress conversation history for an autonomous agent. Write a concise factual summary " +
                    "(max ~250 words) of the user's goals, decisions, files touched, tool results and open TODOs. No preamble."),
                ApiMessage("user", transcript),
            ),
            tools = null, taskId = taskId,
        )
        if (outcome !is ChatOutcome.Success) return false
        val summary = outcome.completion.content?.trim().orEmpty()
        if (summary.isEmpty()) return false
        tasks.addMessage(
            ChatMessage(0, taskId, MessageRole.SYSTEM, summary, MessageKind.SUMMARY, null, null, null,
                outcome.model, false, null, System.currentTimeMillis())
        )
        tasks.markSummarized(ids)
        return true
    }
}
