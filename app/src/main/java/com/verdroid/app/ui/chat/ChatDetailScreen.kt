package com.verdroid.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.chathead.ChatHeadResult
import com.verdroid.app.domain.model.*
import com.verdroid.app.ui.chathead.OverlayPermissionDialog
import com.verdroid.app.ui.components.*
import com.verdroid.app.ui.theme.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(onBack: () -> Unit, onChatMemory: (Long) -> Unit = {}, vm: ChatViewModel = hiltViewModel()) {
    val task by vm.task.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val toolCalls by vm.toolCalls.collectAsStateWithLifecycle()
    val generating by vm.isGenerating.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val callsByMessage = remember(toolCalls) { toolCalls.groupBy { it.messageId } }
    val visible = remember(messages) {
        messages.filter { it.role != MessageRole.TOOL && !(it.role == MessageRole.SYSTEM && it.kind == MessageKind.NORMAL) }
    }
    // Consecutive termux_run (& co.) calls fold into one expandable row.
    val rows = remember(visible, callsByMessage) { ToolStacks.rows(visible, callsByMessage) }

    // ---- chat head / bubble launch flow (POST_NOTIFICATIONS on 13+, overlay permission dialog)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOverlayDialog by remember { mutableStateOf(false) }
    var awaitingOverlayPermission by remember { mutableStateOf(false) }
    val launchChatHead: () -> Unit = {
        scope.launch {
            when (vm.openChatHead()) {
                ChatHeadResult.NEEDS_OVERLAY_PERMISSION -> showOverlayDialog = true
                ChatHeadResult.BUBBLES_BLOCKED -> {
                    Toast.makeText(context, "Bubbles are off for Verdroid. Enable them, or pick Overlay in Settings → Chat heads.", Toast.LENGTH_LONG).show()
                    context.startActivity(vm.chatHeads.bubbleSettingsIntent())
                }
                ChatHeadResult.NOTIFICATIONS_BLOCKED ->
                    Toast.makeText(context, "Notifications are blocked, and bubbles need them. Allow notifications or pick Overlay in Settings → Chat heads.", Toast.LENGTH_LONG).show()
                ChatHeadResult.OVERLAY_STARTED ->
                    Toast.makeText(context, "Chat head is floating. Switch apps to keep chatting.", Toast.LENGTH_SHORT).show()
                ChatHeadResult.BUBBLE_SHOWN, null -> Unit
            }
            Unit
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        launchChatHead()
    }
    val onChatHeadClick: () -> Unit = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else launchChatHead()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (awaitingOverlayPermission && vm.chatHeads.canDrawOverlays()) {
            awaitingOverlayPermission = false
            launchChatHead()
        }
    }
    if (showOverlayDialog) {
        OverlayPermissionDialog(
            onOpenSettings = {
                showOverlayDialog = false
                awaitingOverlayPermission = true
                context.startActivity(vm.chatHeads.overlayPermissionIntent())
            },
            onDismiss = { showOverlayDialog = false },
        )
    }

    LaunchedEffect(messages.size) {
        vm.markOpened()
        if (visible.isNotEmpty()) listState.animateScrollToItem(rows.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val t = task
                        t?.let { TaskAvatar(it, size = 36.dp) } ?: TaskAvatar(TaskType.CHAT, null, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t?.title ?: "New conversation", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (t != null) Text(t.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (task != null) {
                        // No chat-head button (v0.9.18): the chat head starts automatically; settings in Settings > Chat heads.
                        var overflow by remember { mutableStateOf(false) }
                        var showPresets by remember { mutableStateOf(false) }
                        if (showPresets) {
                            val off by vm.presetsOff.collectAsStateWithLifecycle()
                            ToolPresetsDialog(off, vm::setPreset, vm::setAllPresets) { showPresets = false }
                        }
                        Box {
                            IconButton(onClick = { overflow = true }) {
                                Text("⋮", fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = "More options" })
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text("Chat memory (short-term)") }, onClick = {
                                    overflow = false; task?.id?.let(onChatMemory)
                                })
                                DropdownMenuItem(text = { Text("Tool presets") }, onClick = {
                                    overflow = false; showPresets = true
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visible.isEmpty()) {
                    item {
                        Text("Describe a task or ask anything.\nVerdroid can read, write and list files in its sandboxed workspace.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
                    }
                }
                items(rows, key = { it.key }) { row ->
                    val m = row.message
                    when {
                        m.kind == MessageKind.STATUS -> StatusLine(m)
                        m.kind == MessageKind.SUMMARY -> SummaryCard(m)
                        m.role == MessageRole.USER -> UserBubble(m.content.orEmpty())
                        m.role == MessageRole.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!m.content.isNullOrBlank()) AgentBubble(m.content, m.model)
                            row.groups.forEach { ToolCallGroup(it) }
                        }
                    }
                }
                if (generating) item("typing") {
                    Text("● ${task?.subtitle ?: "Thinking..."}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
            }
            val status = task?.status
            if (task?.archived == true) {
                // v1.0.12: archived chats are read-only; restore to continue.
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived chat (read-only)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = vm::restore) { Text("Restore") }
                    }
                }
            } else if (generating) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = vm::stop, modifier = Modifier.padding(4.dp)) {
                        Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp)); Text("Stop Generation")
                    }
                }
            } else if (status in setOf(TaskStatus.PAUSED, TaskStatus.RATE_LIMITED, TaskStatus.QUEUED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
                val now = rememberNow()
                val resumeAt = task?.resumeAt
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (resumeAt != null && resumeAt > now && status != TaskStatus.CANCELLED && status != TaskStatus.FAILED) {
                        Text("Auto-resume in ${formatDuration(resumeAt - now)}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
                        FilledTonalButton(onClick = vm::forceRetry) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                            Text(if (resumeAt != null && resumeAt > now && status != TaskStatus.FAILED && status != TaskStatus.CANCELLED) "Force retry now" else "Continue")
                        }
                        if (status == TaskStatus.PAUSED || status == TaskStatus.RATE_LIMITED || status == TaskStatus.QUEUED) {
                            OutlinedButton(onClick = vm::cancelTask) { Text("Cancel") }
                        }
                    }
                }
            }
            if (task?.archived != true) {
                val pending by vm.pendingAttach.collectAsStateWithLifecycle()
                val attachErr by vm.attachError.collectAsStateWithLifecycle()
                val attachPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(vm::attach)
                }
                val attachAccess by vm.attachNeedsAccess.collectAsStateWithLifecycle()
                val attachCtx = androidx.compose.ui.platform.LocalContext.current
                val accessLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { _ ->
                    vm.retryAttachAfterAccess()
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    pending?.let { p ->
                        AssistChip(
                            onClick = { },
                            label = { Text(p.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (com.verdroid.app.domain.model.AttachmentText.isImageName(p.displayName)) {
                                { AttachmentThumb(p.relativePath, 24.dp) }
                            } else null,
                            trailingIcon = {
                                IconButton(onClick = vm::clearPendingAttach, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Filled.Close, "Remove attachment")
                                }
                            },
                        )
                    }
                    attachErr?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (attachAccess != null) TextButton(onClick = {
                                runCatching { accessLauncher.launch(com.verdroid.app.data.storage.SharedFolder.accessIntent(attachCtx)) }
                            }) { Text("Grant access") }
                            TextButton(onClick = vm::clearAttachError) { Text("Dismiss") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { attachPicker.launch(arrayOf("*/*")) }, enabled = !generating) {
                            Icon(Icons.Filled.Add, "Attach file")
                        }
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text(if (task == null) "Describe a new task…" else "Message Verdroid…") },
                            shape = RoundedCornerShape(24.dp), maxLines = 5,
                        )
                        Spacer(Modifier.width(6.dp))
                        FilledIconButton(
                            onClick = { vm.send(input); input = "" },
                            enabled = (input.isNotBlank() || pending != null) && !generating,
                            modifier = Modifier.size(48.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    }
                }
            }
        }
    }
}

/** Per-chat tool presets: Web / Files / Termux / Device, plus All. Everything is on by default. */
@Composable
private fun ToolPresetsDialog(
    off: Set<com.verdroid.app.data.tools.ToolPreset>,
    onSet: (com.verdroid.app.data.tools.ToolPreset, Boolean) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Tool presets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Which tools the agent can use in this chat. Memory, skills, charts and MCP always stay on; " +
                    "Settings → Tools still applies.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PresetRow("All", "Every tool category", off.isEmpty()) { onSetAll(it) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                com.verdroid.app.data.tools.ToolPreset.entries.forEach { p ->
                    PresetRow(p.label, p.description, p !in off) { onSet(p, it) }
                }
            }
        },
    )
}

@Composable
private fun PresetRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
internal fun UserBubble(text: String) {
    // The stored message keeps the model-facing "Attached file: Input/… use workspace_*…" line; the UI shows a file chip.
    val d = remember(text) { com.verdroid.app.data.storage.ChatAttachment.forDisplay(text) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val b = com.verdroid.app.ui.theme.LocalBubbleColors.current
        if (d.fileName != null) AttachmentChip(d.fileName, d.bytes, d.path.takeIf { d.isImage })
        if (d.text.isNotBlank() || d.fileName == null) {
            Surface(color = b.user, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                SelectionContainer { Text(d.text.trim(), color = b.onUser, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
            }
        }
    }
}

@Composable
private fun AttachmentChip(name: String, bytes: Long?, imagePath: String?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 300.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (imagePath != null) AttachmentThumb(imagePath, 48.dp) else Text("📎", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                bytes?.let { Text(com.verdroid.app.data.storage.ChatAttachment.humanSize(it), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
internal fun AgentBubble(text: String, model: String?) {
    val b = com.verdroid.app.ui.theme.LocalBubbleColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(color = b.agent, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer {
                MarkdownText(text, color = b.onAgent, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        if (model != null) Text(model, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
    }
}

@Composable
internal fun StatusLine(m: ChatMessage) {
    val now = rememberNow()
    val text = buildString {
        append(m.content.orEmpty())
        m.resumeAt?.let { at ->
            if (at > now) append(" Resuming in ${formatDuration(at - now)}.") else append(" Reset time reached.")
        }
    }
    Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp))
}

@Composable
internal fun SummaryCard(m: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(10.dp)) {
            Text("📝 Earlier conversation summarized ${if (expanded) "▲" else "▼"}", style = MaterialTheme.typography.labelMedium)
            AnimatedVisibility(expanded) { Text(m.content.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

private val prettyJson = Json { prettyPrint = true; isLenient = true }

private fun pretty(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(raw)) }.getOrDefault(raw)
}

@Composable
internal fun ToolCallCard(call: ToolCallRecord) {
    var expanded by remember { mutableStateOf(false) }
    val sc = com.verdroid.app.ui.theme.LocalStatusColors.current
    val (icon, color) = when (call.status) {
        ToolCallStatus.PENDING -> "⏳" to sc.warn
        ToolCallStatus.SUCCESS -> "✅" to sc.ok
        ToolCallStatus.ERROR -> "⚠️" to sc.error
    }
    // Tool cards: surfaceContainer (errors: errorContainer) so they sit between the bubbles and the background.
    Surface(shape = RoundedCornerShape(12.dp),
        color = if (call.status == ToolCallStatus.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (call.status == ToolCallStatus.ERROR) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                Text(call.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (call.source == "fenced") { SmallBadge("json-fallback", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface); Spacer(Modifier.width(6.dp)) }
                Text(icon); Spacer(Modifier.width(4.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
            }
            if (call.name == com.verdroid.app.agent.tools.ChartTool.NAME && call.status == ToolCallStatus.SUCCESS) {
                remember(call.resultJson) { com.verdroid.app.agent.tools.ChartSpecs.fromResult(call.resultJson) }
                    ?.let { (spec, png) -> com.verdroid.app.ui.chart.ChartCard(spec, png) }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CodeBlock("arguments", pretty(call.argumentsJson), if (call.name == "run_shell") "json" else null)
                    CodeBlock("result", pretty(call.resultJson).ifEmpty { "…running" }, null)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String, language: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            com.verdroid.app.ui.components.CopyButton(code)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(8.dp)) {
            SelectionContainer {
                Text(CodeHighlighter.highlight(code.take(8000), language, com.verdroid.app.ui.components.CodeColors.of(MaterialTheme.colorScheme)), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Thumbnail of an attached image under Documents/Verdroid ([relativePath] e.g. Input/photo.jpg); 🖼️ until/if it can't load. */
@Composable
internal fun AttachmentThumb(relativePath: String, size: androidx.compose.ui.unit.Dp) {
    var bmp by remember(relativePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(relativePath) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.verdroid.app.data.storage.AttachmentImages.thumbnail(
                    java.io.File(com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH, relativePath), 256)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b, contentDescription = "Attached image",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("🖼️", fontSize = (size.value * 0.45f).sp)
    }
}
