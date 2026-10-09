package com.farrow.app.ui.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.shizuku.ShizukuManager
import com.farrow.app.shizuku.ShizukuState
import com.farrow.app.ui.components.BackScaffold
import com.farrow.app.ui.components.CopyButton
import com.farrow.app.ui.components.CopyableText

/** Settings > Shizuku, accessibility & Git: setup for run_shell, screen_* and git_* tools. */
@Composable
fun DeviceControlScreen(onBack: () -> Unit, vm: DeviceControlViewModel = hiltViewModel()) {
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val backend by vm.backendStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var token by remember { mutableStateOf("") }
    var cryptoKey by remember { mutableStateOf("") }
    var cryptoSecret by remember { mutableStateOf("") }
    var cryptoPass by remember { mutableStateOf("") }
    var user by remember(ui.gitUser) { mutableStateOf(ui.gitUser) }
    var name by remember(ui.authorName) { mutableStateOf(ui.authorName) }
    var email by remember(ui.authorEmail) { mutableStateOf(ui.authorEmail) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val rishPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { vm.installRish(it) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    BackScaffold("Shell, accessibility & Git", onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("run_shell (Shizuku)") {
                    Text(when (shizuku) {
                        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed."
                        ShizukuState.NOT_RUNNING -> "Shizuku is installed but not running. Open it and start it via Wireless debugging (or root)."
                        ShizukuState.PRE_V11 -> "Shizuku is too old — update to v11+."
                        ShizukuState.NO_PERMISSION -> "Shizuku is running. Farrow needs permission."
                        ShizukuState.READY -> "✅ Ready (permission granted)."
                    })
                    if (shizuku == ShizukuState.READY) ui.shizukuInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Active backend: " + when (backend.active) {
                        ShizukuManager.BACKEND_NEW_PROCESS -> "Shizuku newProcess"
                        ShizukuManager.BACKEND_SERVICE -> "Shizuku UserService (fallback)"
                        else -> if (backend.errors.isEmpty()) "not tested yet" else "none available"
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text("Order: Shizuku newProcess (primary) → Shizuku UserService (fallback). Commands run as the shell user.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    backend.errors.forEach { (k, v) -> Text("• $k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (backend.errors.isNotEmpty()) CopyButton(backend.errors.entries.joinToString("\n") { "${it.key}: ${it.value}" }, label = "Copy errors")
                    ui.shizukuBindError?.let { CopyableText("Last bind error: $it", color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (shizuku) {
                            ShizukuState.NOT_INSTALLED -> Button(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            }) { Text("Get Shizuku") }
                            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> Button(onClick = {
                                context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)?.let { context.startActivity(it) }
                            }) { Text("Open Shizuku") }
                            ShizukuState.NO_PERMISSION -> Button(onClick = vm::requestShizukuPermission) { Text("Grant permission") }
                            ShizukuState.READY -> Button(onClick = vm::testShell, enabled = !ui.testing) { Text(if (ui.testing) "Testing…" else "Test (id)") }
                        }
                        OutlinedButton(onClick = vm::diagnoseShizuku, enabled = !ui.testing && shizuku == ShizukuState.READY) { Text("Diagnose") }
                        TextButton(onClick = vm::refresh) { Text("Re-check") }
                        if (ui.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    ui.testOutput?.let { CopyableText(it) }
                }

                Section("rish (rish_run)") {
                    Text("Shizuku's rish lets Farrow run privileged shell commands (rish_run). In Shizuku, open “Use Shizuku in " +
                        "terminal apps” → Export files into Download, Documents or Documents/Farrow/Input and tap Find rish (needs " +
                        "All files access), or pick the exported rish file (select rish_shizuku.dex too if asked). " +
                        "Farrow copies both into its internal folder.", style = MaterialTheme.typography.bodySmall)
                    Text(if (ui.rishInstalled) "✅ Installed: ${ui.rishInfo}" else "Not set up", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = vm::findRish) { Text("Find rish") }
                        OutlinedButton(onClick = { rishPicker.launch(arrayOf("*/*")) }) { Text(if (ui.rishInstalled) "Pick again" else "Pick rish file") }
                    }
                    if (ui.rishInstalled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::testRish, enabled = !ui.testing) { Text("Test (id)") }
                        TextButton(onClick = vm::removeRish) { Text("Remove") }
                    }
                    if (ui.rishNeedsAccess) TextButton(onClick = { runCatching { context.startActivity(vm.allFilesAccessIntent()) } }) {
                        Text("Grant All files access")
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku-API/tree/master/rish")))
                    }) { Text("About rish (GitHub)") }
                }

                Section("Accessibility (screen_read / tap / swipe / type)") {
                    Text(if (ui.accessibilityOn) "✅ Enabled" else "Off — enable “Farrow agent control” in Accessibility settings. " +
                        "On HyperOS: Settings → Additional settings → Accessibility → Downloaded apps.")
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open Accessibility settings") }
                }

                Section("Git (git_clone / git_status / git_commit / git_push)") {
                    Text("Token: ${ui.gitTokenMasked ?: "not set"} · stored with EncryptedSharedPreferences", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(token, { token = it }, label = { Text("Personal access token (repo scope)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text("HTTPS username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Author name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text("Author email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.saveGit(token, user, name, email); token = "" }) { Text("Save") }
                        if (ui.gitTokenMasked != null) OutlinedButton(onClick = vm::clearGitToken) { Text("Remove token") }
                    }
                }

                Section("Crypto exchange (Coinbase Exchange)") {
                    Text("Revolut has no public crypto trading API. Market data tools work without a key; balances and live " +
                        "orders need a Coinbase Exchange API key (View + Trade). Live place/cancel stay off under Tools until you enable them. " +
                        "Key: ${ui.cryptoKeyMasked ?: "not set"} · encrypted.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(cryptoKey, { cryptoKey = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cryptoSecret, { cryptoSecret = it }, label = { Text("API secret") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cryptoPass, { cryptoPass = it }, label = { Text("Passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            vm.saveCrypto(cryptoKey, cryptoSecret, cryptoPass)
                            cryptoKey = ""; cryptoSecret = ""; cryptoPass = ""
                        }) { Text("Save") }
                        if (ui.cryptoConfigured) OutlinedButton(onClick = vm::clearCrypto) { Text("Remove") }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
