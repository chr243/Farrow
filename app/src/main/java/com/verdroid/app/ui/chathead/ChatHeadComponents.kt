package com.verdroid.app.ui.chathead

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.verdroid.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.domain.model.Task
import com.verdroid.app.ui.components.TaskAvatar

/** Floating chat-head avatar with an unread badge. */
@Composable
fun ChatHeadAvatar(task: Task?, unread: Int) {
    Box(Modifier.padding(6.dp)) {
        Surface(shape = CircleShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
            Box(Modifier.padding(3.dp)) {
                if (task == null) {
                    // No task attached yet: show the Farrow mark instead of a generic chat avatar.
                    Image(painterResource(R.drawable.ic_farrow_logo), contentDescription = "Farrow", modifier = Modifier.size(54.dp))
                } else {
                    TaskAvatar(task, size = 54.dp)
                }
            }
        }
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 9) "9+" else unread.toString(), color = MaterialTheme.colorScheme.onError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The ✕ target shown at the bottom of the screen while dragging the chat head. */
@Composable
fun DismissTarget(near: Boolean) {
    val size by animateDpAsState(if (near) 76.dp else 60.dp, label = "dismissSize")
    Box(
        Modifier.size(84.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (near) 0.9f else 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 26.sp)
        }
    }
}

@Composable
fun OverlayPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow chat heads over other apps") },
        text = {
            Text(
                "To float the conversation over other apps, Farrow needs the \"Display over other apps\" permission.\n\n" +
                    "On HyperOS/MIUI also open Other permissions and allow \"Display pop-up windows while running in the background\".",
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
