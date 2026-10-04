package com.farrow.app.ui.menu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.domain.model.ChatHeadMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHeadSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val mode by vm.chatHeadMode.collectAsStateWithLifecycle()
    // Bumped on every resume so permission states are re-read after returning from system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> refresh++ }

    val overlayGranted = remember(refresh) { vm.chatHeads.canDrawOverlays() }
    val bubblesAllowed = remember(refresh) { vm.chatHeads.bubblesAllowed() }
    val notificationsOn = remember(refresh) { vm.chatHeads.notificationsEnabled() }
    val notificationPermissionMissing = remember(refresh) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    val xiaomi = remember { vm.chatHeads.isXiaomiFamily() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Chat heads") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Open any conversation as a floating chat head with the 🫧 button in the chat's top bar.",
                style = MaterialTheme.typography.bodyMedium)
            ModeRow(ChatHeadMode.AUTO, mode, "Auto",
                "Bubbles when supported and allowed; overlay on HyperOS/MIUI or when bubbles are off" +
                    if (xiaomi) " (this device: overlay)" else "") { vm.setChatHeadMode(it) }
            ModeRow(ChatHeadMode.BUBBLES, mode, "Bubbles", "Android Bubbles API (conversation notification)") { vm.setChatHeadMode(it) }
            ModeRow(ChatHeadMode.OVERLAY, mode, "Overlay", "Draggable chat head drawn over other apps (needs permission)") { vm.setChatHeadMode(it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val autoHead by vm.appPrefs.autoChatHeadOnHome.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto chat head on Home", style = MaterialTheme.typography.bodyLarge)
                    Text("Leaving the app (Home/Recents) from a chat opens that chat as an overlay head; it is removed when you come back. Needs \"Display over other apps\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = autoHead, onCheckedChange = vm.appPrefs::setAutoChatHeadOnHome)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Permissions", style = MaterialTheme.typography.titleSmall)
            StatusRow("Display over other apps", overlayGranted) { context.startActivity(vm.chatHeads.overlayPermissionIntent()) }
            if (notificationPermissionMissing) {
                StatusRow("Notification permission", false) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                StatusRow("Notifications", notificationsOn) { context.startActivity(vm.chatHeads.notificationSettingsIntent()) }
            }
            StatusRow("Bubbles allowed", bubblesAllowed) { context.startActivity(vm.chatHeads.bubbleSettingsIntent()) }
            if (xiaomi) {
                Text("HyperOS tip: in App info → Other permissions, also allow \"Display pop-up windows while running in the background\", " +
                    "and set Battery saver to \"No restrictions\" so the chat head isn't killed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeRow(value: ChatHeadMode, selected: ChatHeadMode, title: String, subtitle: String, onSelect: (ChatHeadMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label + if (ok) " — granted" else " — needed", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onFix) { Text(if (ok) "Settings" else "Grant") }
    }
}
