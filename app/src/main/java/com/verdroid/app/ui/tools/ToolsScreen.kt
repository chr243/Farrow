package com.verdroid.app.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.verdroid.app.ui.components.groupedRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings > Tools: built-in agent tools (status + persisted on/off), Termux setup and optional Termux add-ons. MCP has its own screen. Text only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val disabled by vm.prefs.disabled.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }
    val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                SetupEvent.OpenInstall -> runCatching { context.startActivity(vm.termux.installIntent()) }
                SetupEvent.RequestPermission -> permissionLauncher.launch(com.verdroid.app.data.termux.TermuxManager.PERMISSION_RUN_COMMAND)
                SetupEvent.PasteAllowCommand -> {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD))
                    android.widget.Toast.makeText(context, "Command copied — paste it in Termux and press Enter", android.widget.Toast.LENGTH_LONG).show()
                    vm.termux.openIntent()?.let { runCatching { context.startActivity(it) } }
                }
                SetupEvent.OpenTermux -> vm.termux.openIntent()?.let { runCatching { context.startActivity(it) } }
            }
        }
    }
    // Coming back from Termux / F-Droid / a permission screen continues the setup (or just re-checks).
    var resumedOnce by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) vm.onReturned() else resumedOnce = true
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Tools") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = vm::refresh, enabled = !state.checking) { Text(if (state.checking) "Checking…" else "Check again") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text("Turned-off tools are hidden from the model and refused if it calls them anyway.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            itemsIndexed(state.tools, key = { _, t -> "t-" + t.name }) { i, t ->
                val on = t.name !in disabled
                ListItem(
                    modifier = Modifier.groupedRow(i, state.tools.size),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(t.name, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Column {
                            Text(t.description, maxLines = 2)
                            Text(if (!on) "Off" else t.status.text, style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    !on -> MaterialTheme.colorScheme.onSurfaceVariant
                                    t.status.ready -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                })
                        }
                    },
                    trailingContent = { Switch(checked = on, onCheckedChange = { vm.setEnabled(t.name, it) }) },
                )
            }
            item {
                Text("Shared folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp))
                SharedFolderCard(state.storage,
                    onGrant = { runCatching { storageLauncher.launch(com.verdroid.app.data.storage.SharedFolder.accessIntent(context)) } })
            }
            item {
                Text("Termux", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp))
                TermuxSetupCard(state.termux, state,
                    onSetup = vm::setupTermux, onCancel = vm::cancelSetup,
                    onInstall = { runCatching { context.startActivity(vm.termux.installIntent()) } },
                    onGrant = { permissionLauncher.launch(com.verdroid.app.data.termux.TermuxManager.PERMISSION_RUN_COMMAND) })
            }
            item {
                Text("Available to install", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp))
                Text("Termux packages the agent can use with termux_run. Installed in the background with pkg (no Termux window).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                state.packagesNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            itemsIndexed(state.packages, key = { _, p -> "p-" + p.pkg.pkg }) { i, p ->
                ListItem(
                    modifier = Modifier.groupedRow(i, state.packages.size),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(p.pkg.pkg, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Column {
                            Text(p.pkg.description)
                            val s = when (p.state) {
                                PkgState.INSTALLED -> "Installed (${p.pkg.binary})"
                                PkgState.NOT_INSTALLED -> "Not installed"
                                PkgState.INSTALLING -> "Installing…"
                                PkgState.FAILED -> "Install failed"
                                PkgState.UNKNOWN -> "Unknown"
                            }
                            Text(s, style = MaterialTheme.typography.labelMedium,
                                color = if (p.state == PkgState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            p.detail?.let { d ->
                                Text(d, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6)
                                if (p.state == PkgState.FAILED) com.verdroid.app.ui.components.CopyButton(d)
                            }
                        }
                    },
                    trailingContent = {
                        when (p.state) {
                            PkgState.INSTALLED -> Text("Installed", style = MaterialTheme.typography.labelLarge)
                            PkgState.INSTALLING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else -> OutlinedButton(onClick = { vm.install(p.pkg) }, enabled = state.packagesNote == null) {
                                Text(if (p.state == PkgState.FAILED) "Retry" else "Install")
                            }
                        }
                    },
                )
            }
            item {
                Text("Coinbase Exchange key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp))
                CryptoKeyCard(state.cryptoKeyMasked, state.cryptoConfigured, vm::saveCrypto, vm::clearCrypto)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Optional API key for crypto_balance / crypto_order_status / live orders. Market data needs no key. */
@Composable
private fun CryptoKeyCard(masked: String?, configured: Boolean, onSave: (String, String, String) -> Unit, onClear: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Only for crypto balances and orders. Key: ${masked ?: "not set"} · encrypted.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = hidden, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("API secret") }, singleLine = true, visualTransformation = hidden, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = hidden, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(key, secret, pass); key = ""; secret = ""; pass = "" },
                enabled = key.isNotBlank() || secret.isNotBlank() || pass.isNotBlank()) { Text("Save") }
            if (configured) OutlinedButton(onClick = onClear) { Text("Remove") }
        }
    }
}

/** Lightweight ViewModel for the MCP screen (no Termux/Shizuku checks like [ToolsViewModel]). */
@dagger.hilt.android.lifecycle.HiltViewModel
class McpViewModel @javax.inject.Inject constructor(
    val prefs: com.verdroid.app.data.tools.ToolPrefs,
    val mcp: com.verdroid.app.data.mcp.McpManager,
) : androidx.lifecycle.ViewModel() {
    fun setEnabled(name: String, on: Boolean) = prefs.setEnabled(name, on)
}

/** Settings > MCP servers: remote MCP servers, their status and per-tool switches (split out of Tools). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(onBack: () -> Unit, vm: McpViewModel = hiltViewModel()) {
    val disabled by vm.prefs.disabled.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("MCP servers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { McpServersSection(vm.mcp, disabled, vm::setEnabled) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Documents/Farrow: what it is for, All files access and folder status. */
@Composable
private fun SharedFolderCard(s: StorageSetup, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium)
            Text("A folder you can open in any file manager. Put files for Farrow in Input/; Farrow saves its results in " +
                "Output/. The agent creates, edits and deletes files only inside this folder (workspace_list, workspace_read, " +
                "workspace_write, workspace_delete). Android 11+ needs All files access for this; the folders are created " +
                "automatically at launch once it is granted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupLine("1. All files access", s.access) {
                if (!s.access) OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
            SetupLine("2. Documents/Farrow with Input/ and Output/", s.exists) {}
        }
    }
}

/** Termux setup for termux_run and the package installer: install, RUN_COMMAND permission, allow-external-apps. */
@Composable
private fun TermuxSetupCard(t: TermuxSetup, s: ToolsState, onSetup: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("termux_run and the add-ons below run in the Termux app (in the background, no Termux window).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val allDone = com.verdroid.app.data.termux.TermuxSetupFlow.next(t.installed, t.permission, t.answering, t.storage) ==
                com.verdroid.app.data.termux.TermuxSetupStep.DONE
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetup, enabled = !s.checking) {
                    Text(when { allDone -> "Check setup"; s.setupActive -> "Continue setup"; else -> "Set up Termux" })
                }
                if (s.setupActive) TextButton(onClick = onCancel) { Text("Cancel") }
                if (s.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text("One tap walks through everything: install, permission, allow-external-apps (copied for one paste) and storage. " +
                "Come back to Farrow after each step and it continues.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.setupMessage?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
            }
            SetupLine("1. Termux installed", t.installed) {
                if (!t.installed) OutlinedButton(onClick = onInstall) { Text("Get Termux (F-Droid)") }
            }
            SetupLine("2. Run commands in Termux permission", t.permission) {
                if (t.installed && !t.permission) OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
            SetupLine("3. allow-external-apps in Termux", t.answering == true) {}
            if (t.answering != true) {
                Text("Paste this once into Termux:", style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                com.verdroid.app.ui.components.CopyButton(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD)
            }
            SetupLine("4. Storage access for Termux (optional)", t.storage == true) {}
            if (t.answering == true && t.storage != true) {
                Text("For selenium_* and your scrapers to save into Documents/Farrow/Output, run this once in Termux and allow storage:",
                    style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text("termux-setup-storage", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                com.verdroid.app.ui.components.CopyButton("termux-setup-storage")
            }
        }
    }
}

@Composable
private fun SetupLine(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "❌ ") + label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action()
    }
}
