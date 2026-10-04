package com.farrow.app.chathead

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.MainActivity
import com.farrow.app.ui.chat.ChatViewModel
import com.farrow.app.ui.chathead.CompactChatPanel
import com.farrow.app.ui.theme.FarrowTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Expanded content of an Android bubble (embedded activity; see manifest: allowEmbedded,
 * resizeableActivity, documentLaunchMode=always). The "taskId" extra feeds ChatViewModel's
 * SavedStateHandle through the activity's default view-model arguments.
 */
@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        setContent {
            FarrowTheme {
                val vm: ChatViewModel = hiltViewModel()
                val task by vm.task.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val toolCalls by vm.toolCalls.collectAsStateWithLifecycle()
                val generating by vm.isGenerating.collectAsStateWithLifecycle()
                CompactChatPanel(
                    task = task,
                    messages = messages,
                    toolCalls = toolCalls,
                    generating = generating,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onResume = vm::resume,
                    onOpenFull = { startActivity(MainActivity.openTaskIntent(this, taskId)) },
                    onCollapse = null,
                    onSeen = vm::markOpened,
                )
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("farrow://task/$taskId"))
                .putExtra(EXTRA_TASK_ID, taskId)
    }
}
