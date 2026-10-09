package com.farrow.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.farrow.app.data.work.QuotaPollWorker
import com.farrow.app.data.work.AgentScheduler
import com.farrow.app.keepalive.KeepAliveController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FarrowApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: AgentScheduler
    @Inject lateinit var keepAlive: KeepAliveController
    @Inject lateinit var agent: com.farrow.app.domain.repository.AgentController
    @Inject lateinit var sharedFolder: com.farrow.app.data.storage.SharedFolder

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** v0.9.9: the ADB-over-TCP backend is gone — drop its settings, key and any queued boot work once. */
    private fun removeLegacyAdb() {
        runCatching {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("adb-tcp-boot")
            deleteSharedPreferences("adb_shell")
            java.io.File(filesDir, "adb").deleteRecursively()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Phase 3: re-queue interrupted tasks and make sure paused ones keep their WorkManager resume job.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { scheduler.recover() }
        QuotaPollWorker.schedule(this)
        removeLegacyAdb()
        // Documents/Farrow with Input/ and Output/ (no-op until All files access is granted; Tools re-checks it).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { sharedFolder.ensure() } }
        // v0.9.19: the keep-alive service runs only while a task is running (no idle notification).
        keepAlive.watch(agent.runningTaskIds, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    }
}
