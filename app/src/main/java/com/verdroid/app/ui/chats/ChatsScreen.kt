package com.verdroid.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.domain.model.Conversation
import com.verdroid.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenModels: () -> Unit = onOpenSettings,
    vm: ChatsViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val quotaDot by vm.quotaDot.collectAsStateWithLifecycle()
    val hasKeys by vm.hasKeys.collectAsStateWithLifecycle()
    val updateAvailable by vm.updateAvailable.collectAsStateWithLifecycle()
    val now = rememberNow()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.archivedEvents.collect { e ->
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(if (e.stopped) "Moved to archive (task stopped)" else "Moved to archive",
                actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undoArchive(e.taskId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Farrow", fontWeight = FontWeight.Bold, fontSize = 26.sp) },
                actions = {
                    quotaDot?.let { QuotaDotButton(it, onDetails = onOpenModels) }
                    IconButton(onClick = onOpenSettings) {
                        Box {
                            Icon(Icons.Filled.Settings, contentDescription = if (updateAvailable) "Settings (update available)" else "Settings")
                            if (updateAvailable) com.verdroid.app.ui.menu.UpdateDot(Modifier.align(Alignment.TopEnd))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(onClick = onNewChat, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation", modifier = Modifier.size(36.dp))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 112.dp)) {
            if (!hasKeys) {
                item("nokey") {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenKeys)) {
                        Text("🔑 Add your OpenRouter API key to start (Settings → API keys).",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
            item("search") {
                SearchBar(query, onChange = { vm.query.value = it })
            }
            if (stories.isNotEmpty()) {
                item("stories") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp)) {
                        items(stories, key = { it.task.id }) { c -> Story(c, now) { onOpenChat(c.task.id) } }
                    }
                }
            }
            if (conversations.isEmpty()) {
                item("empty") {
                    Text(
                        if (query.isBlank()) "No conversations yet.\nTap ✏️ or + to start a task." else "No matches for \"$query\"",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(conversations, key = { it.task.id }) { c ->
                ArchivableRow(c, onOpen = { onOpenChat(c.task.id) }, onArchive = { vm.archive(c) })
            }
        }
    }
}

/** Small theme-aware status dot for the OpenRouter daily free quota; tap shows the numbers and a Details link. */
@Composable
private fun QuotaDotButton(dot: QuotaDot, onDetails: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val status = com.verdroid.app.ui.theme.LocalStatusColors.current
    val color = when (dot.level) { QuotaLevel.GREEN -> status.ok; QuotaLevel.YELLOW -> status.warn; QuotaLevel.RED -> status.error }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = dot.line }) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 300.dp)) {
                Text(dot.line, style = MaterialTheme.typography.bodyMedium)
                Text("Active model: ${dot.activeModel}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open = false; onDetails() }, contentPadding = PaddingValues(0.dp)) { Text("Details") }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search conversations or tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Clear, "Clear", Modifier.clickable { onChange("") }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Story(c: Conversation, now: Long, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            TaskAvatar(c.task, size = 64.dp, ring = true)
            storyBadge(c.task, now)?.let { badge ->
                Box(Modifier.align(Alignment.TopEnd)) { SmallBadge(badge) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(c.task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
    }
}

/** v1.0.12: swipe left/right or long-press > Delete moves the chat to the archive (Undo in the snackbar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivableRow(c: Conversation, onOpen: () -> Unit, onArchive: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) { onArchive(); true } else false
    })
    var menu by remember { mutableStateOf(false) }
    SwipeToDismissBox(state, backgroundContent = {
        val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (start) Arrangement.Start else Arrangement.End) {
            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text("Archive", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            ConversationRow(c, onClick = onOpen, onLongClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onArchive() })
            }
        }
    }
}

/** Chat list row avatar diameter (Messenger-like). */
val CHAT_ROW_AVATAR = 64.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(c: Conversation, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Messenger-sized avatar: 64 dp (was the 52 dp default, ~23% larger).
        TaskAvatar(c.task, size = CHAT_ROW_AVATAR)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.task.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (c.hasUnread) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
            Text(c.task.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (c.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (c.hasUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTimestamp(c.task.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (c.hasUnread) Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            else Spacer(Modifier.size(12.dp))
        }
    }
}
