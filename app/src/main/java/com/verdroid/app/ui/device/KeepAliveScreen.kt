package com.verdroid.app.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.verdroid.app.keepalive.KeepAliveController
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class KeepAliveViewModel @Inject constructor(val controller: KeepAliveController) : ViewModel()

/** Settings > Background & battery: keep-alive service, battery optimisation exemption, HyperOS autostart. */
@Composable
fun KeepAliveScreen(onBack: () -> Unit, vm: KeepAliveViewModel = hiltViewModel()) {
    val c = vm.controller
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(c.enabled) }
    var batteryOk by remember { mutableStateOf(c.isIgnoringBatteryOptimizations()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = c.isIgnoringBatteryOptimizations(); enabled = c.enabled }

    BackScaffold("Background & battery", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep tasks alive while working", style = MaterialTheme.typography.titleSmall)
                            Text("While a task runs, a foreground service shows “Working on: <chat>” with the current step and a Stop button. " +
                                "When nothing is running there is no service and no notification.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = enabled, onCheckedChange = { c.enabled = it; enabled = it })
                    }
                    Text("Chat heads keep working while idle without this service, but Android may close an idle app; " +
                        "the bubble then comes back the next time you open Farrow.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Battery optimisation", style = MaterialTheme.typography.titleSmall)
                    Text(if (batteryOk) "✅ Unrestricted — Android won't defer Farrow's resume jobs." else
                        "Optimised — Doze can delay auto-resume and kill the agent. Allow Farrow to ignore battery optimisations.")
                    if (!batteryOk) Button(onClick = { runCatching { context.startActivity(c.batteryOptimizationIntent()) } }) { Text("Allow") }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("HyperOS / MIUI autostart", style = MaterialTheme.typography.titleSmall)
                    Text("On Xiaomi/Redmi/POCO phones, also:\n" +
                        "1. Security app → Permissions → Autostart → enable Farrow (needed for resume after reboot).\n" +
                        "2. Settings → Apps → Farrow → Battery saver → No restrictions.\n" +
                        "3. Recents: long-press Farrow → lock (padlock) so it isn't swiped away.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { c.openAutostartSettings { context.startActivity(it) } }) {
                        Text(if (c.isXiaomi) "Open Autostart settings" else "Open app settings")
                    }
                }
            }
            Text("After a reboot, Farrow restarts only tasks that were mid-run; paused tasks keep their scheduled resume.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
