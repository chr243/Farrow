package com.farrow.app.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.farrow.app.domain.model.PauseReason
import com.farrow.app.domain.model.TaskStatus
import com.farrow.app.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable task queue: every queued / paused task has one unique WorkManager job
 * ("agent-task-<id>") with an initial delay = time until it may resume. WorkManager persists the
 * job across process death and reboots; [AgentTaskWorker] then continues the task from its checkpoint.
 */
@Singleton
class AgentScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TaskRepository,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(context)

    fun schedule(taskId: Long, resumeAt: Long, replace: Boolean = true) {
        val delay = (resumeAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(AgentTaskWorker.KEY_TASK_ID to taskId))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(uniqueName(taskId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(taskId: Long) {
        wm.cancelUniqueWork(uniqueName(taskId))
    }

    /**
     * Called on app start / boot: tasks that were RUNNING or QUEUED when the process died are
     * re-queued immediately; paused tasks with an auto-resume reason keep (or regain) their job.
     */
    /** Returns the number of mid-run tasks re-queued. */
    suspend fun recover(): Int {
        val now = System.currentTimeMillis()
        val midRun = tasks.tasksWithStatus(listOf(TaskStatus.RUNNING, TaskStatus.QUEUED))
        midRun.forEach { t ->
            tasks.pause(t.id, TaskStatus.QUEUED, "Resuming after restart…", now, PauseReason.INTERRUPTED, t.attempt)
            schedule(t.id, now, replace = true)
        }
        tasks.tasksWithStatus(listOf(TaskStatus.RATE_LIMITED, TaskStatus.PAUSED)).forEach { t ->
            val reason = PauseReason.of(t.pauseReason)
            val at = t.resumeAt
            if (reason?.autoResume == true && at != null) schedule(t.id, at, replace = false)
        }
        return midRun.size
    }

    companion object {
        const val TAG = "agent-task"
        fun uniqueName(taskId: Long) = "agent-task-$taskId"
    }
}
