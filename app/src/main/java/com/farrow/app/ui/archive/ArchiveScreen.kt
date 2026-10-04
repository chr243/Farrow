package com.farrow.app.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.farrow.app.domain.model.Task
import com.farrow.app.domain.usecase.ChatArchiveUseCase
import com.farrow.app.ui.components.TaskAvatar
import com.farrow.app.ui.components.Zebra
import com.farrow.app.ui.components.formatFull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArchiveViewModel @Inject constructor(private val archive: ChatArchiveUseCase) : ViewModel() {
    val chats = archive.archived.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun restore(id: Long) = viewModelScope.launch { archive.restore(id) }
    fun delete(id: Long) = viewModelScope.launch { archive.deletePermanently(id) }
    fun empty() = viewModelScope.launch { archive.emptyArchive() }
}

private sealed interface Confirm {
    data class Delete(val task: Task) : Confirm
    data object Empty : Confirm
}

/** Settings > Archive (v1.0.12): archived chats, newest first; open read-only, restore, or delete permanently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit, vm: ArchiveViewModel = hiltViewModel()) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Archive") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (chats.isNotEmpty()) TextButton(onClick = { confirm = Confirm.Empty }) { Text("Empty archive") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("info") {
                Text("Chats deleted from the home list land here. Open one to read it, Restore to continue it, or delete it " +
                    "permanently (messages, short-term memory and chart images).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (chats.isEmpty()) item("empty") {
                Text("The archive is empty.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
            itemsIndexed(chats, key = { _, t -> t.id }) { i, t ->
                ListItem(
                    leadingContent = { TaskAvatar(t.type, t.status) },
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("Archived ${formatFull(t.archivedAt ?: t.updatedAt)} · last activity ${formatFull(t.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { vm.restore(t.id) }) { Text("Restore") }
                            TextButton(onClick = { confirm = Confirm.Delete(t) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Zebra.color(i)),
                    modifier = Modifier.clickable { onOpenChat(t.id) },
                )
            }
        }
    }
    when (val c = confirm) {
        is Confirm.Delete -> AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Delete permanently?") },
            text = { Text("\"${c.task.title}\" will be deleted with its messages, short-term memory and chart images. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(c.task.id); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
        Confirm.Empty -> AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Empty archive?") },
            text = { Text("All ${chats.size} archived chats will be deleted permanently with their messages, short-term memory and chart images. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.empty(); confirm = null }) { Text("Empty archive", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
        null -> Unit
    }
}
