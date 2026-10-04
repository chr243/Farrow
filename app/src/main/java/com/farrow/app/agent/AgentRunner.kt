package com.farrow.app.agent

import com.farrow.app.data.work.AgentScheduler
import com.farrow.app.domain.model.MessageKind
import com.farrow.app.domain.model.MessageRole
import com.farrow.app.domain.model.ChatMessage
import com.farrow.app.domain.model.TaskStatus
import com.farrow.app.domain.repository.AgentController
import com.farrow.app.domain.repository.TaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs agent loops in an application-scoped coroutine scope, one independent job per task (so
 * concurrent tasks don't block each other). Pauses are turned into durable WorkManager jobs via
 * [AgentScheduler] (Phase 3), which survive process death and reboots.
 */
@Singleton
class AgentRunner @Inject constructor(
    private val loop: AgentLoop,
    private val tasks: TaskRepository,
    private val scheduler: AgentScheduler,
) : AgentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    override val runningTaskIds: StateFlow<Set<Long>> = _running.asStateFlow()

    @Synchronized
    override fun start(taskId: Long) {
        if (jobs[taskId]?.isActive == true) return
        scheduler.cancel(taskId)
        _running.update { it + taskId }
        val job = scope.launch {
            try {
                when (val result = loop.run(taskId)) {
                    is RunResult.Paused -> scheduler.schedule(taskId, result.resumeAt)
                    RunResult.Completed, RunResult.Stopped -> Unit
                }
            } catch (e: CancellationException) {
                // stopped / cancelled by the user
            } catch (e: Throwable) {
                // v0.9.8: never fail silently — the reason is posted in the chat (with Continue via the Resume bar).
                runCatching {
                    tasks.updateStatus(taskId, TaskStatus.FAILED, "Failed: ${e.message?.take(80)}", error = e.toString())
                    tasks.addMessage(statusMessage(taskId, "❌ The task stopped unexpectedly: ${e.javaClass.simpleName}: ${e.message ?: "no message"}. Tap Continue to retry."))
                }
            }
        }
        jobs[taskId] = job
        job.invokeOnCompletion {
            synchronized(this) { if (jobs[taskId] === job) jobs.remove(taskId) }
            _running.update { it - taskId }
        }
    }

    @Synchronized
    override fun stop(taskId: Long) {
        scheduler.cancel(taskId)
        val job = jobs[taskId]
        if (job?.isActive == true) {
            job.cancel()
        } else {
            scope.launch {
                val t = tasks.getTask(taskId) ?: return@launch
                if (t.status == TaskStatus.RATE_LIMITED || t.status == TaskStatus.PAUSED || t.status == TaskStatus.QUEUED) {
                    tasks.updateStatus(taskId, TaskStatus.CANCELLED, "Stopped")
                }
            }
        }
    }

    override fun forceRetry(taskId: Long) {
        scope.launch {
            scheduler.cancel(taskId)
            tasks.setAttempt(taskId, 0)
            tasks.addMessage(statusMessage(taskId, "🔁 Force retry requested."))
            start(taskId)
        }
    }

    override fun cancel(taskId: Long) {
        val job = synchronized(this) {
            scheduler.cancel(taskId)
            jobs[taskId]
        }
        scope.launch {
            job?.cancelAndJoin()
            tasks.updateStatus(taskId, TaskStatus.CANCELLED, "Cancelled")
            tasks.addMessage(statusMessage(taskId, "🚫 Task cancelled. Scheduled retries were dropped."))
        }
    }

    private fun statusMessage(taskId: Long, text: String) = ChatMessage(
        0, taskId, MessageRole.SYSTEM, text, MessageKind.STATUS, null, null, null, null, false, null, System.currentTimeMillis(),
    )
}
