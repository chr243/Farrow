package com.farrow.app.domain

import com.farrow.app.domain.model.*
import com.farrow.app.domain.repository.AgentController
import com.farrow.app.domain.repository.TaskRepository
import com.farrow.app.domain.usecase.ChatArchiveUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatArchiveUseCaseTest {
    private class Tasks(vararg t: Task) : TaskRepository {
        val all = t.associateBy { it.id }.toMutableMap()
        val deleted = mutableListOf<Long>()
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(all.values.filter { !it.archived }.map { Conversation(it, 0) })
        override fun observeTask(taskId: Long): Flow<Task?> = flowOf(all[taskId])
        override fun observeMessages(taskId: Long): Flow<List<ChatMessage>> = flowOf(emptyList())
        override fun observeToolCalls(taskId: Long): Flow<List<ToolCallRecord>> = flowOf(emptyList())
        override suspend fun getTask(taskId: Long) = all[taskId]
        override suspend fun getMessages(taskId: Long) = emptyList<ChatMessage>()
        override suspend fun createTask(title: String, prompt: String, type: TaskType) = 0L
        override suspend fun updateStatus(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, error: String?) {}
        override suspend fun updateSubtitle(taskId: Long, subtitle: String) {}
        override suspend fun updateCheckpoint(taskId: Long, step: Int, model: String?, checkpointJson: String) {}
        override suspend fun markStarted(taskId: Long) {}
        override suspend fun markOpened(taskId: Long) {}
        override suspend fun addMessage(message: ChatMessage) = 0L
        override suspend fun markSummarized(ids: List<Long>) {}
        override suspend fun addToolCall(record: ToolCallRecord) = 0L
        override suspend fun finishToolCall(id: Long, resultJson: String, status: ToolCallStatus) {}
        override suspend fun deleteTask(taskId: Long) { all.remove(taskId); deleted += taskId }
        override fun observeArchived(): Flow<List<Task>> = flowOf(all.values.filter { it.archived }.sortedByDescending { it.archivedAt })
        override suspend fun archive(taskId: Long) { all[taskId]?.let { if (!it.archived) all[taskId] = it.copy(archivedAt = 1L) } }
        override suspend fun restore(taskId: Long) { all[taskId]?.let { all[taskId] = it.copy(archivedAt = null) } }
        override suspend fun archivedIds() = all.values.filter { it.archived }.map { it.id }
        override suspend fun pause(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, reason: PauseReason, attempt: Int) {}
        override suspend fun setAttempt(taskId: Long, attempt: Int) {}
        override suspend fun tasksWithStatus(statuses: List<TaskStatus>) = emptyList<Task>()
    }

    private class Agent(running: Set<Long>) : AgentController {
        override val runningTaskIds: StateFlow<Set<Long>> = MutableStateFlow(running)
        val stopped = mutableListOf<Long>()
        override fun start(taskId: Long) {}
        override fun stop(taskId: Long) { stopped += taskId }
        override fun forceRetry(taskId: Long) {}
        override fun cancel(taskId: Long) {}
    }

    private fun task(id: Long, status: TaskStatus) = Task(id, "T$id", "p", TaskType.CHAT, status, "", 0, null, null, 0, 0, null, 0, null, null)

    @Test fun `archiving stops running or scheduled work, idle chats are just archived`() = runTest {
        val repo = Tasks(task(1, TaskStatus.COMPLETED), task(2, TaskStatus.RUNNING), task(3, TaskStatus.RATE_LIMITED), task(4, TaskStatus.COMPLETED))
        val agent = Agent(running = setOf(4))
        val uc = ChatArchiveUseCase(repo, agent)
        assertFalse(uc.archive(1)); assertTrue(uc.archive(2)); assertTrue(uc.archive(3)); assertTrue(uc.archive(4))
        assertEquals(listOf(2L, 3L, 4L), agent.stopped)
        assertTrue(repo.all.values.all { it.archived })
        assertFalse(uc.archive(99))
    }

    @Test fun `restore, delete and empty archive`() = runTest {
        val repo = Tasks(task(1, TaskStatus.COMPLETED), task(2, TaskStatus.COMPLETED), task(3, TaskStatus.COMPLETED), task(4, TaskStatus.COMPLETED))
        val uc = ChatArchiveUseCase(repo, Agent(emptySet()))
        listOf(1L, 2L, 3L).forEach { uc.archive(it) }
        uc.restore(1); assertFalse(repo.all[1]!!.archived)
        uc.deletePermanently(2); assertEquals(listOf(2L), repo.deleted)
        assertEquals(1, uc.emptyArchive()); assertEquals(listOf(2L, 3L), repo.deleted)
        assertEquals(setOf(1L, 4L), repo.all.keys)
    }
}
