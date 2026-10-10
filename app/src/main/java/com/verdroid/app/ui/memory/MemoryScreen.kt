package com.verdroid.app.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.memory.Memory
import com.verdroid.app.data.memory.MemoryRepository
import com.verdroid.app.data.memory.MemoryText
import com.verdroid.app.domain.repository.TaskRepository
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A chat for the picker. */
data class ChatChoice(val id: Long, val title: String, val notes: Int)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: MemoryRepository,
    tasks: TaskRepository,
) : ViewModel() {
    /** Opened from a chat's overflow menu: that chat, "This chat" tab first. */
    val openedFromChat: Long? = savedState.get<Long>("chat")?.takeIf { it > 0 }
    val selectedChat = MutableStateFlow(openedFromChat)
    val query = MutableStateFlow("")
    val all = repo.memories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val global = all.map { MemoryText.global(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shownGlobal = combine(global, query) { l, q -> if (q.isBlank()) l else MemoryText.search(l, q, 500) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val chats = combine(tasks.observeConversations(), all) { convs, mems ->
        convs.sortedByDescending { it.task.updatedAt }.map { c -> ChatChoice(c.task.id, c.task.title, mems.count { it.chatId == c.task.id }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val chatNotes = combine(all, selectedChat, chats) { l, sel, cs ->
        val id = sel ?: cs.firstOrNull()?.id
        MemoryText.ofChat(l, id).sortedBy { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val autoSave = repo.autoSaveFlow
    val exportPath: String get() = repo.exportFile.absolutePath

    fun currentChat(): Long? = selectedChat.value ?: chats.value.firstOrNull()?.id
    fun setAutoSave(on: Boolean) = repo.setAutoSave(on)
    fun add(text: String, tags: String, chatId: Long?) = viewModelScope.launch { if (text.isNotBlank()) runCatching { repo.save(text, MemoryText.tags(tags), chatId) } }
    fun edit(id: Long, text: String, tags: String) = viewModelScope.launch { if (text.isNotBlank()) repo.update(id, text, MemoryText.tags(tags)) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun toLongTerm(id: Long) = viewModelScope.launch { repo.move(id, null) }
    fun clear(chatId: Long?) = viewModelScope.launch { repo.clear(chatId) }
}

/** Settings > Memory (or a chat's overflow menu): "This chat" short-term notes and long-term memory. Text-only UI. */
@Composable
fun MemoryScreen(onBack: () -> Unit, vm: MemoryViewModel = hiltViewModel()) {
    var tab by remember { mutableIntStateOf(if (vm.openedFromChat != null) 0 else 1) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Memory?>(null) }
    val chats by vm.chats.collectAsStateWithLifecycle()
    val selected by vm.selectedChat.collectAsStateWithLifecycle()
    val chatId = selected ?: chats.firstOrNull()?.id

    BackScaffold("Memory", onBack, actions = {
        TextButton(onClick = { adding = true }, enabled = tab == 1 || chatId != null) { Text("Add") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("This chat") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Long-term") })
            }
            if (tab == 0) ChatSection(vm, chats, chatId, onEdit = { editing = it }, onDelete = { confirmDelete = it }, onClear = { confirmClear = true })
            else GlobalSection(vm, onEdit = { editing = it }, onDelete = { confirmDelete = it }, onClear = { confirmClear = true })
        }
    }

    if (adding) MemoryEditDialog(null, if (tab == 0) "Add short-term note" else "Add long-term memory", onDismiss = { adding = false }) { t, tags ->
        vm.add(t, tags, if (tab == 0) chatId else null); adding = false
    }
    editing?.let { m -> MemoryEditDialog(m, "Edit memory #${m.id}", onDismiss = { editing = null }) { t, tags -> vm.edit(m.id, t, tags); editing = null } }
    confirmDelete?.let { m ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete memory #${m.id}?") }, text = { Text(m.text) },
            confirmButton = { TextButton(onClick = { vm.delete(m.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
    if (confirmClear) {
        val chatTab = tab == 0
        AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text(if (chatTab) "Clear this chat's short-term memory?" else "Clear all long-term memories?") },
            text = { Text(if (chatTab) "The agent loses its notes about this chat's task." else "The agent will forget everything it learned about you.") },
            confirmButton = { TextButton(onClick = { vm.clear(if (chatTab) chatId else null); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ChatSection(vm: MemoryViewModel, chats: List<ChatChoice>, chatId: Long?, onEdit: (Memory) -> Unit, onDelete: (Memory) -> Unit, onClear: () -> Unit) {
    val notes by vm.chatNotes.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Short-term memory: the agent's scratchpad for one chat (task progress, decisions, findings). Always in that chat's " +
                "prompt (~1k tokens, oldest trimmed) and deleted with the chat.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Box {
                OutlinedButton(onClick = { picker = true }, Modifier.fillMaxWidth(), enabled = chats.isNotEmpty()) {
                    Text(chats.firstOrNull { it.id == chatId }?.let { "Chat: ${it.title}" } ?: "No chats yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                    chats.forEach { c ->
                        DropdownMenuItem(text = { Text("${c.title} (${c.notes})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { vm.selectedChat.value = c.id; picker = false })
                    }
                }
            }
        }
        if (notes.isEmpty()) item { Text("No short-term notes for this chat.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(notes, key = { it.id }) { m ->
            MemoryCard(m, onEdit, onDelete, extra = { TextButton(onClick = { vm.toLongTerm(m.id) }) { Text("Move to long-term") } })
        }
        if (notes.isNotEmpty()) item { OutlinedButton(onClick = onClear, Modifier.fillMaxWidth()) { Text("Clear this chat") } }
    }
}

@Composable
private fun GlobalSection(vm: MemoryViewModel, onEdit: (Memory) -> Unit, onDelete: (Memory) -> Unit, onClear: () -> Unit) {
    val shown by vm.shownGlobal.collectAsStateWithLifecycle()
    val all by vm.global.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val autoSave by vm.autoSave.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Long-term memory: lasting facts about you and your preferences, shared by all chats. The ~20 most important/recent " +
                "go into the system prompt; the agent can search the rest. Exported to ${vm.exportPath}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic saving", style = MaterialTheme.typography.titleSmall)
                    Text(if (autoSave) "The agent saves lasting facts on its own." else "The agent saves long-term memories only when you ask.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoSave, onCheckedChange = vm::setAutoSave)
            }
        }
        item {
            OutlinedTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search (${all.size} memories)") })
        }
        if (shown.isEmpty()) item {
            Text(if (all.isEmpty()) "Nothing remembered yet." else "No memory matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { m -> MemoryCard(m, onEdit, onDelete) }
        if (all.isNotEmpty()) item { OutlinedButton(onClick = onClear, Modifier.fillMaxWidth()) { Text("Clear all") } }
    }
}

@Composable
private fun MemoryCard(m: Memory, onEdit: (Memory) -> Unit, onDelete: (Memory) -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Text("#${m.id}" + (if (m.tags.isNotEmpty()) " · " + m.tags.joinToString(", ") else "") + " · updated " +
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.updatedAt)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { onEdit(m) }) { Text("Edit") }
                TextButton(onClick = { onDelete(m) }) { Text("Delete") }
                extra()
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(m: Memory?, title: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var text by remember { mutableStateOf(m?.text.orEmpty()) }
    var tags by remember { mutableStateOf(m?.tags?.joinToString(", ").orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(MemoryText.MAX_TEXT) }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2)
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tags (comma-separated; \"important\" keeps it in the prompt)") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, tags) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
