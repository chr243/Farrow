package com.verdroid.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdroid.app.chathead.ChatHeadController
import com.verdroid.app.chathead.ChatHeadResult
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.PauseReason
import com.verdroid.app.domain.model.Task
import com.verdroid.app.domain.model.ToolCallRecord
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
import com.verdroid.app.data.storage.ChatAttachment
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.domain.usecase.SendMessageUseCase
import com.verdroid.app.domain.usecase.StartConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val agent: AgentController,
    private val startConversation: StartConversationUseCase,
    private val sendMessage: SendMessageUseCase,
    val chatHeads: ChatHeadController,
    private val sharedFolder: SharedFolder,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    /** 0 = a brand-new conversation that is created on the first send. */
    private val taskId = MutableStateFlow(savedState.get<Long>("taskId") ?: 0L)

    val task: StateFlow<Task?> = taskId.flatMapLatest { if (it == 0L) flowOf(null) else tasks.observeTask(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val messages: StateFlow<List<ChatMessage>> = taskId.flatMapLatest { if (it == 0L) flowOf(emptyList()) else tasks.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val toolCalls: StateFlow<List<ToolCallRecord>> = taskId.flatMapLatest { if (it == 0L) flowOf(emptyList()) else tasks.observeToolCalls(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val isGenerating: StateFlow<Boolean> = combine(taskId, agent.runningTaskIds) { id, running -> id in running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _pendingAttach = MutableStateFlow<ChatAttachment.Saved?>(null)
    val pendingAttach: StateFlow<ChatAttachment.Saved?> = _pendingAttach.asStateFlow()
    private val _attachError = MutableStateFlow<String?>(null)
    val attachError: StateFlow<String?> = _attachError.asStateFlow()

    /** A pick that's waiting for All files access; retried by [retryAttachAfterAccess]. */
    private val _attachNeedsAccess = MutableStateFlow<Uri?>(null)
    val attachNeedsAccess: StateFlow<Uri?> = _attachNeedsAccess.asStateFlow()

    fun clearAttachError() { _attachError.value = null; _attachNeedsAccess.value = null }
    fun clearPendingAttach() { _pendingAttach.value = null }

    /** Every "+" pick is copied into Documents/Verdroid/Input and kept until the next send. */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            _attachError.value = null
            _attachNeedsAccess.value = null
            val saved = runCatching {
                withContext(Dispatchers.IO) { ChatAttachment.saveToInput(appContext, sharedFolder, uri) }
            }.getOrElse {
                if (it is ChatAttachment.NoAccessException) _attachNeedsAccess.value = uri
                _attachError.value = it.message ?: "Could not attach file"
                return@launch
            }
            _pendingAttach.value = saved
        }
    }

    /** Back from the All files access screen: copy the waiting pick into Input/ now (keeps the prompt if still denied). */
    fun retryAttachAfterAccess() {
        val uri = _attachNeedsAccess.value ?: return
        if (sharedFolder.hasAccess()) attach(uri)
    }

    fun send(text: String) {
        val pending = _pendingAttach.value
        val t = text.trim()
        if (t.isEmpty() && pending == null) return
        if (task.value?.archived == true) return
        val body = if (pending != null) ChatAttachment.messagePrefix(pending) + t.ifEmpty { ChatAttachment.DEFAULT_PROMPT } else t
        viewModelScope.launch {
            val id = taskId.value
            if (id == 0L) taskId.value = startConversation(body) else sendMessage(id, body)
            _pendingAttach.value = null
        }
    }

    /** v1.0.12: bring an archived chat back to the home list. */
    fun restore() { taskId.value.takeIf { it != 0L }?.let { viewModelScope.launch { tasks.restore(it) } } }

    fun stop() { taskId.value.takeIf { it != 0L }?.let(agent::stop) }
    fun resume() = forceRetry()
    /** Phase 3: drop the scheduled backoff/resume and run now. */
    fun forceRetry() { taskId.value.takeIf { it != 0L }?.let(agent::forceRetry) }
    /** Phase 3: cancel the task and its scheduled retries. */
    fun cancelTask() { taskId.value.takeIf { it != 0L }?.let(agent::cancel) }
    /** Opens the current conversation as a bubble or overlay chat head (null for an unsaved new chat). */
    suspend fun openChatHead(): ChatHeadResult? = taskId.value.takeIf { it != 0L }?.let { chatHeads.open(it) }

    fun markOpened() { taskId.value.takeIf { it != 0L }?.let { viewModelScope.launch { tasks.markOpened(it) } } }
}
