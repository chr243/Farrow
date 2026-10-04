package com.farrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farrow.app.domain.model.Task
import com.farrow.app.domain.model.TaskStatus
import com.farrow.app.domain.model.TaskType
import com.farrow.app.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun TaskType.emoji() = when (this) {
    TaskType.WEB -> "🌐"; TaskType.SOCIAL -> "👥"; TaskType.SYSTEM -> "⚙️"; TaskType.CHAT -> "💬"
}

/** Task-type accent from the theme roles (no fixed colors). */
@Composable
fun TaskType.color(): Color = when (this) {
    TaskType.WEB -> MaterialTheme.colorScheme.secondary; TaskType.SOCIAL -> MaterialTheme.colorScheme.tertiary
    TaskType.SYSTEM -> MaterialTheme.colorScheme.outline; TaskType.CHAT -> MaterialTheme.colorScheme.primary
}

/** Status dot, harmonised with the palette ([com.farrow.app.ui.theme.LocalStatusColors]). */
@Composable
fun TaskStatus.dotColor(): Color? {
    val c = com.farrow.app.ui.theme.LocalStatusColors.current
    return when (this) {
        TaskStatus.RUNNING -> c.ok
        TaskStatus.PAUSED, TaskStatus.RATE_LIMITED, TaskStatus.QUEUED -> c.warn
        TaskStatus.FAILED -> c.error
        else -> null
    }
}

@Composable
fun TaskAvatar(type: TaskType, status: TaskStatus?, size: Dp = 52.dp, ring: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (ring) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier)
                .clip(CircleShape).background(type.color().copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Text(type.emoji(), fontSize = (size.value * 0.42f).sp) }
        status?.dotColor()?.let { c ->
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.28f)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)
                    .clip(CircleShape).background(c)
            )
        }
    }
}

@Composable
fun SmallBadge(text: String, color: Color = MaterialTheme.colorScheme.primary,
               onColor: Color = if (color == MaterialTheme.colorScheme.error) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, color = onColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Recomposes every [intervalMs] with the current time (for countdowns / uptimes). */
@Composable
fun rememberNow(intervalMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) { now = System.currentTimeMillis(); delay(intervalMs) }
    }
    return now
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
    }
}

fun formatCompactDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}h"
        s >= 60 -> "${s / 60}m"
        else -> "${s}s"
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("MMM d")
private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun formatTimestamp(ms: Long): String {
    val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return if (zdt.toLocalDate() == LocalDate.now()) timeFmt.format(zdt) else dateFmt.format(zdt)
}

fun formatFull(ms: Long): String = fullFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Badge text for a story: uptime while running, time-left while rate-limited. */
fun storyBadge(task: Task, now: Long): String? = when (task.status) {
    TaskStatus.RUNNING -> task.startedAt?.let { formatCompactDuration(now - it) }
    TaskStatus.RATE_LIMITED -> task.resumeAt?.let { if (it > now) formatCompactDuration(it - now) else "0s" }
    TaskStatus.PAUSED -> task.resumeAt?.let { if (it > now) formatCompactDuration(it - now) else "⏸" } ?: "⏸"
    TaskStatus.QUEUED -> "…"
    TaskStatus.FAILED -> "!"
    else -> null
}


/** Small "Copy" button: puts [text] on the clipboard and shows a toast. Used next to errors, logs and diagnostics. */
@Composable
fun CopyButton(text: String, label: String = "Copy", modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton(
        onClick = {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Farrow", text))
            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        },
        enabled = text.isNotBlank(),
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { Text("📋 $label", fontSize = 12.sp) }
}

/** An error/log text with a Copy button underneath. */
@Composable
fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    monospace: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.foundation.layout.Column(modifier) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = maxLines,
                fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null)
        }
        CopyButton(text)
    }
}
