package com.farrow.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.farrow.app.domain.repository.QuotaRepository
import com.farrow.app.ui.FarrowRoot
import com.farrow.app.ui.theme.FarrowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var quota: QuotaRepository
    @Inject lateinit var bridgeAutoStarter: com.farrow.app.data.browser.BridgeAutoStarter
    @Inject lateinit var appPrefs: com.farrow.app.data.prefs.AppPrefs
    @Inject lateinit var chatHeads: com.farrow.app.chathead.ChatHeadController

    // v0.9.8 "Auto chat head on Home": onUserLeaveHint (Home/Recents) marks a leave; onStop (really in the background,
    // not rotation, not a translucent permission dialog) opens the head. Our own startActivity calls (settings,
    // permission screens, Termux, browser) are recorded so they never trigger it.
    private var leaveHintAt = 0L
    private var ownLaunchAt = 0L
    private var autoHeadTaskId: Long? = null

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.elapsedRealtime()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - ownLaunchAt > OWN_LAUNCH_GRACE_MS) leaveHintAt = now
    }

    override fun onStop() {
        super.onStop()
        val now = android.os.SystemClock.elapsedRealtime()
        val hinted = now - leaveHintAt < LEAVE_HINT_WINDOW_MS && now - ownLaunchAt > OWN_LAUNCH_GRACE_MS
        leaveHintAt = 0L
        val taskId = com.farrow.app.chathead.VisibleChat.taskId
        if (!hinted || isChangingConfigurations || taskId == null) return
        if (!appPrefs.autoChatHeadOnHome.value || !chatHeads.canDrawOverlays()) return
        runCatching { chatHeads.startOverlay(taskId) }.onSuccess { autoHeadTaskId = taskId }
    }

    override fun onStart() {
        super.onStart()
        // Back in the app: remove the head we opened automatically when the user is on that chat (or any route).
        if (autoHeadTaskId != null) {
            autoHeadTaskId = null
            runCatching { stopService(Intent(this, com.farrow.app.chathead.ChatHeadService::class.java)) }
        }
    }

    /** Task to open from a shortcut / bubble / chat-head "Open" action. */
    private val openTaskId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.9.8: app launch — if the internal browser is fully installed, just start the bridge + daemon (never reinstalls).
        if (savedInstanceState == null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { bridgeAutoStarter.startIfInstalled() } }
        enableEdgeToEdge()
        if (savedInstanceState == null) openTaskId.value = taskIdFrom(intent)
        addOnNewIntentListener { newIntent -> taskIdFrom(newIntent)?.let { openTaskId.value = it } }
        // Poll GET /key on start and every 5 minutes while in the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    quota.refresh()
                    delay(FOREGROUND_POLL_MS)
                }
            }
        }
        setContent {
            FarrowTheme {
                FarrowRoot(openTaskId = openTaskId.value, onOpenTaskConsumed = { openTaskId.value = null })
            }
        }
    }

    companion object {
        private const val FOREGROUND_POLL_MS = 5 * 60_000L
        private const val OWN_LAUNCH_GRACE_MS = 1_500L
        private const val LEAVE_HINT_WINDOW_MS = 3_000L
        const val EXTRA_TASK_ID = "taskId"

        private fun taskIdFrom(intent: Intent?): Long? =
            intent?.getLongExtra(EXTRA_TASK_ID, 0L)?.takeIf { it != 0L }

        fun openTaskIntent(context: Context, taskId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
