package com.verdroid.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.update.AppUpdater
import com.verdroid.app.data.update.UpdateState

/** Small "update available" dot (gear on the chat list, the App update row). */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

/** Settings > App update: current version, Check for updates, release notes, Update (download + install). */
@Composable
fun AppUpdateRow(updater: AppUpdater, index: Int) {
    val state by updater.state.collectAsStateWithLifecycle()
    val available by updater.updateAvailable.collectAsStateWithLifecycle()
    // Download runs in the updater's app scope; the installer opens by itself only while this row is visible.
    var autoInstall by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var needsPermission by remember { mutableStateOf(false) }
    fun install(st: UpdateState.ReadyToInstall) {
        if (!updater.canInstall()) {
            needsPermission = true
            runCatching { context.startActivity(updater.unknownSourcesIntent()) }
        } else {
            needsPermission = false
            runCatching { context.startActivity(updater.installIntent(st.file)) }
        }
    }
    LaunchedEffect(state, autoInstall) {
        val st = state
        if (autoInstall && st is UpdateState.ReadyToInstall) { autoInstall = false; install(st) }
    }
    Surface(color = com.verdroid.app.ui.components.Zebra.color(index), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("App update", style = MaterialTheme.typography.bodyLarge)
                        if (available) { Spacer(Modifier.width(6.dp)); UpdateDot() }
                    }
                    Text("Installed: v${updater.currentVersion}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                    onClick = { updater.startCheck() }) { Text("Check for updates") }
            }
            when (val st = state) {
                UpdateState.Idle -> {}
                UpdateState.Checking -> { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is UpdateState.UpToDate -> Status("Up to date (latest: v${st.latest})")
                is UpdateState.Available -> {
                    Status("v${st.release.version} available")
                    Notes(st.release.notes)
                    if (st.release.asset == null) Status("This release has no Verdroid APK to download.")
                    else Button(onClick = { autoInstall = true; updater.startDownload(st.release) },
                        modifier = Modifier.padding(top = 8.dp)) { Text("Update") }
                }
                is UpdateState.Downloading -> {
                    Status("Downloading v${st.release.version}… ${st.done / 1024} / ${st.total / 1024} KB")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.ReadyToInstall -> {
                    Status("v${st.release.version} downloaded" + if (needsPermission) " — allow \"Install unknown apps\" for Verdroid, then tap Install" else "")
                    Button(onClick = { install(st) }, modifier = Modifier.padding(top = 8.dp)) { Text("Install") }
                }
                is UpdateState.Error -> {
                    Status(st.message, error = true)
                    st.release?.let { r -> if (r.asset != null) TextButton(onClick = { autoInstall = true; updater.startDownload(r) }) { Text("Retry download") } }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun Notes(md: String) {
    if (md.isBlank()) return
    Text(md.take(3_000), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).heightIn(max = 240.dp))
}
