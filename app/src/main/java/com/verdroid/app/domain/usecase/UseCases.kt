package com.verdroid.app.domain.usecase

import com.verdroid.app.domain.model.*
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
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
    /**
     * Creates a task from the first prompt, stores the user message and starts the agent loop. [beforeStart] runs with
     * the new id before the agent starts (e.g. to persist tool presets picked on the still-empty chat).
     */
    suspend operator fun invoke(prompt: String, beforeStart: (Long) -> Unit = {}): Long {
        val id = tasks.createTask(titleFrom(prompt), prompt, TaskType.infer(prompt))
        tasks.addMessage(userMessage(id, prompt))
        beforeStart(id)
        agent.start(id)
        return id
    }

    companion object {
        fun titleFrom(prompt: String): String {
            // An attachment-only first message is titled with the file name, not "Attached file: Input/…".
            val d = com.verdroid.app.domain.model.AttachmentText.forDisplay(prompt)
            if (d.fileName != null && d.text.isBlank()) return com.verdroid.app.domain.model.AttachmentText.stripExtension(d.fileName).take(60)
            return com.verdroid.app.domain.model.AttachmentText.titleWords(d.text)
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

/**
 * v1.0.12 chat archive. Archiving a chat with work in progress **stops** it first (the running generation is cancelled
 * and scheduled resumes/backoffs are dropped, like the Stop button), so nothing keeps running in an archived chat;
 * archived chats are also skipped by restart recovery. Restore brings the chat back to the home list (send a message
 * to continue). Permanent delete removes messages, tool calls, short-term memory and chart PNGs.
 */
class ChatArchiveUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val agent: AgentController,
) {
    val archived: Flow<List<Task>> get() = tasks.observeArchived()

    /** Returns true when a running/scheduled task was stopped. */
    suspend fun archive(taskId: Long): Boolean {
        val t = tasks.getTask(taskId) ?: return false
        val busy = taskId in agent.runningTaskIds.value || t.status.isActive
        if (busy) agent.stop(taskId)
        tasks.archive(taskId)
        return busy
    }

    suspend fun restore(taskId: Long) = tasks.restore(taskId)
    suspend fun deletePermanently(taskId: Long) = tasks.deleteTask(taskId)

    /** Deletes every archived chat permanently; returns how many. */
    suspend fun emptyArchive(): Int {
        val ids = tasks.archivedIds()
        ids.forEach { tasks.deleteTask(it) }
        return ids.size
    }
}
