package com.farrow.app.domain.usecase

import com.farrow.app.domain.model.*
import com.farrow.app.domain.repository.AgentController
import com.farrow.app.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveConversationsUseCase @Inject constructor(private val tasks: TaskRepository) {
    operator fun invoke(query: Flow<String>): Flow<List<Conversation>> =
        combine(tasks.observeConversations(), query) { list, q -> filter(list, q) }

    companion object {
        fun filter(list: List<Conversation>, query: String): List<Conversation> {
            val q = query.trim()
            if (q.isEmpty()) return list
            return list.filter {
                it.task.title.contains(q, ignoreCase = true) || it.task.subtitle.contains(q, ignoreCase = true)
            }
        }
    }
}

class StartConversationUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val agent: AgentController,
) {
    /** Creates a task from the first prompt, stores the user message and starts the agent loop. */
    suspend operator fun invoke(prompt: String): Long {
        val id = tasks.createTask(titleFrom(prompt), prompt, TaskType.infer(prompt))
        tasks.addMessage(userMessage(id, prompt))
        agent.start(id)
        return id
    }

    companion object {
        fun titleFrom(prompt: String): String {
            val words = prompt.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val title = words.take(6).joinToString(" ")
            return if (words.size > 6) "$title…" else title.ifBlank { "New conversation" }
        }
    }
}

class SendMessageUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val agent: AgentController,
) {
    suspend operator fun invoke(taskId: Long, text: String) {
        tasks.addMessage(userMessage(taskId, text))
        agent.start(taskId)
    }
}

internal fun userMessage(taskId: Long, text: String) = ChatMessage(
    id = 0, taskId = taskId, role = MessageRole.USER, content = text, kind = MessageKind.NORMAL,
    toolCallsJson = null, toolCallId = null, toolName = null, model = null, summarized = false,
    resumeAt = null, createdAt = System.currentTimeMillis(),
)
