package com.verdroid.app.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.verdroid.app.domain.model.PauseReason
import com.verdroid.app.domain.model.TaskStatus
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fires when a queued/paused task may resume. Starts the agent loop (which continues from the Room
 * checkpoint) and keeps the worker alive while it runs so WorkManager keeps the process around.
 * The run itself lives in the AgentRunner scope, so if WorkManager stops this worker (10-min limit)
 * the run continues; the Phase 8 keep-alive service covers long runs.
 */
@HiltWorker
class AgentTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val agent: AgentController,
    private val tasks: TaskRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, 0L)
        if (taskId == 0L) return Result.failure()
        val task = tasks.getTask(taskId) ?: return Result.success()
        val resumable = when (task.status) {
            TaskStatus.QUEUED, TaskStatus.RUNNING -> true
            TaskStatus.RATE_LIMITED, TaskStatus.PAUSED -> PauseReason.of(task.pauseReason)?.autoResume ?: true
            else -> false
        }
        if (!resumable) return Result.success()
        agent.start(taskId)
        withTimeoutOrNull(MAX_WAIT_MS) { agent.runningTaskIds.first { taskId !in it } }
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
        private const val MAX_WAIT_MS = 9 * 60_000L
    }
}
