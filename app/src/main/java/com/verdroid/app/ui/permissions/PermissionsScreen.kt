package com.verdroid.app.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.a11y.VerdroidAccessibilityService
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxManager
import com.verdroid.app.shizuku.ShizukuManager
import com.verdroid.app.shizuku.ShizukuState
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** One permission row: whether it is granted and what granting does. */
data class PermissionRow(val id: String, val title: String, val why: String, val granted: Boolean, val action: String?)

/** Pure status → rows mapping (unit-tested); [PermissionsViewModel] gathers the status from Android. */
object PermissionCatalog {
    data class Status(
        val allFiles: Boolean, val notifications: Boolean, val termuxInstalled: Boolean, val termuxRunCommand: Boolean,
        val shizuku: ShizukuState, val accessibility: Boolean, val overlay: Boolean, val battery: Boolean, val installApps: Boolean,
    )

    fun rows(s: Status): List<PermissionRow> = listOf(
        PermissionRow("all_files", "All files access", "Documents/Farrow (Input/, Output/) and the workspace_* tools", s.allFiles, "Grant"),
        PermissionRow("notifications", "Notifications", "Task finished / rate-limit alerts, chat heads (Bubbles), keep-alive", s.notifications, "Grant"),
        PermissionRow("termux", "Run commands in Termux", "termux_run, Termux add-ons, selenium_* and termux_python",
            s.termuxRunCommand, if (!s.termuxInstalled) "Get Termux" else "Grant"),
        PermissionRow("shizuku", "Shizuku", "run_shell and rish_run (privileged shell)", s.shizuku == ShizukuState.READY, when (s.shizuku) {
            ShizukuState.NOT_INSTALLED -> "Get Shizuku"
            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> "Open Shizuku"
            ShizukuState.NO_PERMISSION -> "Grant"
            ShizukuState.READY -> null
        }),
        PermissionRow("accessibility", "Accessibility service", "screen_read / tap / swipe / type on other apps", s.accessibility, "Open settings"),
        PermissionRow("overlay", "Display over other apps", "Overlay chat heads", s.overlay, "Grant"),
        PermissionRow("battery", "Ignore battery optimisation", "Keeps long tasks running in the background", s.battery, "Grant"),
        PermissionRow("install", "Install unknown apps", "In-app updates (installing a new Farrow APK)", s.installApps, "Grant"),
    )
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val termux: TermuxManager,
    val shizuku: ShizukuManager,
    private val sharedFolder: SharedFolder,
    private val keepAlive: com.verdroid.app.keepalive.KeepAliveController,
    private val chatHeads: com.verdroid.app.chathead.ChatHeadController,
    private val updater: com.verdroid.app.data.update.AppUpdater,
) : ViewModel() {
    private val _rows = kotlinx.coroutines.flow.MutableStateFlow<List<PermissionRow>>(emptyList())
    val rows: kotlinx.coroutines.flow.StateFlow<List<PermissionRow>> = _rows

    init { refresh() }

    fun refresh() {
        val status = PermissionCatalog.Status(
            allFiles = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false),
            notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            termuxInstalled = termux.isInstalled(),
            termuxRunCommand = termux.isInstalled() && termux.hasRunCommandPermission(),
            shizuku = runCatching { shizuku.refresh() }.getOrDefault(ShizukuState.NOT_INSTALLED),
            accessibility = VerdroidAccessibilityService.isRunning,
            overlay = runCatching { chatHeads.canDrawOverlays() }.getOrDefault(false),
            battery = runCatching { keepAlive.isIgnoringBatteryOptimizations() }.getOrDefault(false),
            installApps = runCatching { updater.canInstall() }.getOrDefault(false),
        )
        if (status.allFiles) runCatching { sharedFolder.ensure() }
        _rows.value = PermissionCatalog.rows(status)
    }

    /** Settings screen (or app store page) for a row; null = handled by a runtime permission dialog in the UI. */
    fun intentFor(id: String): Intent? = when (id) {
        "all_files" -> SharedFolder.accessIntent(context)
        "termux" -> if (!termux.isInstalled()) termux.installIntent() else null
        "shizuku" -> when (shizuku.refresh()) {
            ShizukuState.NOT_INSTALLED -> Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
            else -> null
        }
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "overlay" -> chatHeads.overlayPermissionIntent()
        "battery" -> keepAlive.batteryOptimizationIntent()
        "install" -> updater.unknownSourcesIntent()
        "notifications_settings" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Settings > Permissions: every permission Farrow uses, its status and a Grant button, in one place. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, onTermuxSetup: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    fun grant(id: String) {
        when {
            id == "notifications" && Build.VERSION.SDK_INT >= 33 -> runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
            id == "termux" && vm.termux.isInstalled() -> runtime.launch(TermuxManager.PERMISSION_RUN_COMMAND)
            id == "shizuku" && vm.shizuku.state.value == ShizukuState.NO_PERMISSION -> vm.shizuku.requestPermission()
            else -> vm.intentFor(id)?.let { runCatching { context.startActivity(it) } }
        }
    }

    BackScaffold("Permissions", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Everything Farrow can ask for, in one place. Each is optional and only needed for the tools listed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { r ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.granted) "✅ " else "❌ ") + r.title, style = MaterialTheme.typography.bodyLarge)
                            Text(r.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.id == "notifications" && !r.granted) TextButton(onClick = {
                                vm.intentFor("notifications_settings")?.let { runCatching { context.startActivity(it) } }
                            }) { Text("Open notification settings") }
                        }
                        if (!r.granted && r.action != null) OutlinedButton(onClick = { grant(r.id) }) { Text(r.action) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Termux needs a few more steps inside Termux (allow-external-apps, storage).", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onTermuxSetup) { Text("Set up Termux…") }
        }
    }
}
