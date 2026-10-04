package com.farrow.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.farrow.app.data.work.AgentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** BOOT_COMPLETED / app update: re-queue interrupted (mid-run) tasks and keep paused tasks' scheduled resume. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: AgentScheduler
    @Inject lateinit var keepAlive: KeepAliveController
    @Inject lateinit var bridgeAutoStarter: com.farrow.app.data.browser.BridgeAutoStarter

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_QUICKBOOT -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Only tasks that were mid-run are restarted now (the keep-alive service follows them); paused ones keep
                // their scheduled resume. Nothing runs (no service, no notification) when no task was mid-run.
                val resumed = scheduler.recover()
                if (resumed > 0 && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { bridgeAutoStarter.startIfInstalled() } }
                }
            } finally { pending.finish() }
        }
    }

    private companion object { const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON" }
}
