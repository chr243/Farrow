package com.farrow.app.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.farrow.app.domain.model.Conversation
import com.farrow.app.domain.model.PauseReason
import com.farrow.app.domain.model.TaskStatus
import com.farrow.app.domain.repository.AgentController
import com.farrow.app.domain.repository.TaskRepository
import com.farrow.app.ui.chats.ConversationRow
import com.farrow.app.ui.components.formatDuration
import com.farrow.app.ui.components.rememberNow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TasksViewModel @Inject constructor(
    tasks: TaskRepository,
    private val agent: AgentController,
) : ViewModel() {
    val all = tasks.observeConversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun forceRetry(id: Long) = agent.forceRetry(id)
    fun cancel(id: Long) = agent.cancel(id)
    fun stop(id: Long) = agent.stop(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onOpenChat: (Long) -> Unit, onBack: () -> Unit = {}, vm: TasksViewModel = hiltViewModel()) {
    val all by vm.all.collectAsStateWithLifecycle()
    val now = rememberNow()
    val running = all.filter { it.task.status == TaskStatus.RUNNING }
    val waiting = all.filter { it.task.status in setOf(TaskStatus.QUEUED, TaskStatus.PAUSED, TaskStatus.RATE_LIMITED) }
    val finished = all - running.toSet() - waiting.toSet()
    Scaffold(topBar = { TopAppBar(title = { Text("Tasks") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            header("Running", running.size)
            items(running, key = { "r_${it.task.id}" }) { c ->
                ConversationRow(c) { onOpenChat(c.task.id) }
                ActionRow { OutlinedButton(onClick = { vm.stop(c.task.id) }) { Text("Stop") } }
            }
            header("Queue & paused", waiting.size)
            items(waiting, key = { "w_${it.task.id}" }) { c ->
                ConversationRow(c) { onOpenChat(c.task.id) }
                WaitingInfo(c, now)
                ActionRow {
                    FilledTonalButton(onClick = { vm.forceRetry(c.task.id) }) { Text("Force retry now") }
                    OutlinedButton(onClick = { vm.cancel(c.task.id) }) { Text("Cancel") }
                }
            }
            header("Finished", finished.size)
            items(finished, key = { "f_${it.task.id}" }) { c -> ConversationRow(c) { onOpenChat(c.task.id) } }
            if (all.isEmpty()) item {
                Text("No tasks yet.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaitingInfo(c: Conversation, now: Long) {
    val reason = PauseReason.of(c.task.pauseReason)
    val resumeAt = c.task.resumeAt
    val text = buildString {
        append(
            when (reason) {
                PauseReason.DAILY_QUOTA -> "Daily free quota exhausted"
                PauseReason.RATE_LIMIT -> "Rate-limited"
                PauseReason.TRANSIENT -> "Retrying after error (attempt ${c.task.attempt})"
                PauseReason.INTERRUPTED -> "Interrupted, re-queued"
                PauseReason.MAX_STEPS -> "Max steps reached (manual resume)"
                PauseReason.SESSION_EXPIRED -> "Session expired, re-login needed"
                PauseReason.USER -> "Paused by user"
                null -> c.task.status.name.lowercase().replaceFirstChar { it.uppercase() }
            }
        )
        if (resumeAt != null && resumeAt > now && reason?.autoResume != false) append(" · resumes in ${formatDuration(resumeAt - now)}")
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 80.dp, end = 16.dp))
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 80.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.header(title: String, count: Int) {
    if (count == 0) return
    item("h_$title") {
        Text("$title ($count)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}
