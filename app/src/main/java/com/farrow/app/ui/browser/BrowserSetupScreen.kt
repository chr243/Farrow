package com.farrow.app.ui.browser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.data.browser.SetupStep
import com.farrow.app.data.browser.StepScripts
import com.farrow.app.data.browser.StepState
import com.farrow.app.data.browser.TermuxManager
import com.farrow.app.ui.components.BackScaffold

@Composable
fun BrowserSetupScreen(onBack: () -> Unit, vm: BrowserSetupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult(it) }
    val steps = remember { vm.steps }
    var advanced by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume(); vm.checkBridgeVersion() }
    val bridgeUpdate by vm.bridgeUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    BackScaffold("Internal browser setup", onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Farrow drives a real Firefox running inside Termux (Xvfb + Termux Browser Pilot) through a small " +
                        "localhost bridge. One button checks what is installed, installs only the missing parts in a single " +
                        "Termux session, then starts the bridge and connects.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { BridgeUpdateBanner(bridgeUpdate, onUpdate = vm::updateBridge) }
                item { OpsCard(state.ops, onCancel = vm::cancelOp, onDismiss = vm::dismissLastResult) }
                item { StatusCard(state, vm, onOpenTermux = {
                    context.packageManager.getLaunchIntentForPackage(TermuxManager.TERMUX_PACKAGE)?.let { context.startActivity(it) }
                }, onInstallTermux = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TermuxManager.FDROID_URL)))
                }, onGrant = { permissionLauncher.launch(TermuxManager.PERMISSION_RUN_COMMAND) }) }
                item {
                    SetupAllCard(state, vm, onGrant = { permissionLauncher.launch(TermuxManager.PERMISSION_RUN_COMMAND) },
                        onInstallTermux = {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TermuxManager.FDROID_URL)))
                        })
                }
                item {
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "▾ Advanced (individual steps)" else "▸ Advanced (individual steps)") }
                }
                if (advanced) state.runningStep?.let { n ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Step $n is running in Termux… other steps are locked.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::stopWaiting) { Text("Stop waiting") }
                        }
                    }
                }
                if (advanced) items(steps, key = { it.id }) { step ->
                    StepCard(
                        step = step,
                        ui = state.steps[step.number] ?: StepUi(),
                        canRun = step.runnable && state.termuxInstalled && state.runCommandGranted &&
                            !state.ops.busy,
                        onRun = { vm.run(step) },
                        onCopy = { clipboard.setText(AnnotatedString(step.command)) },
                        onCheck = { vm.checkStep(step.number) },
                        onViewLog = { vm.showLog(step.number) },
                        statusAvailable = state.runCommandGranted,
                    )
                }
                item { FingerprintCard(state, vm) }
                item { TextButton(onClick = vm::regenerateToken) { Text("Regenerate bridge token") } }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    state.logDialogStep?.let { n ->
        val ui = state.steps[n] ?: StepUi()
        AlertDialog(
            onDismissRequest = vm::dismissLog,
            confirmButton = { TextButton(onClick = vm::dismissLog) { Text("Close") } },
            dismissButton = { TextButton(onClick = { vm.checkStep(n) }) { Text("Refresh") } },
            title = { Text("Step $n log (last 50 lines)") },
            text = { LogBox(ui.logTail.ifBlank { "No log yet (or Termux hasn't answered). ~/.farrow/logs/step-$n.log" }) },
        )
    }
}

@Composable
private fun StatusCard(state: BrowserSetupState, vm: BrowserSetupViewModel, onOpenTermux: () -> Unit, onInstallTermux: () -> Unit, onGrant: () -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            StatusRow("Termux installed", state.termuxInstalled)
            StatusRow("RUN_COMMAND permission", state.runCommandGranted)
            val h = state.health
            StatusRow("Bridge reachable (127.0.0.1:${vm.bridgePort})", h?.reachable == true && h.error == null)
            StatusRow("tbp installed", h?.tbpInstalled == true)
            StatusRow("TBP daemon running", h?.daemonRunning == true)
            if (!state.checking && h?.bridgeOk == true && !h.daemonRunning) {
                state.daemonLog?.let { LogBox(it) }
                OutlinedButton(onClick = vm::startDaemon, enabled = !state.ops.busy) { Text("Start TBP daemon") }
            }
            if (h?.bridgeOk == true || state.resetMessage != null) {
                OutlinedButton(onClick = vm::resetBrowser, enabled = !state.ops.busy) { Text("Reset browser") }
                Text("Stops TBP, Firefox and Xvfb, clears stale locks (daemon.pid, .tbp_browser.lock, X99) and starts fresh.",
                    style = MaterialTheme.typography.bodySmall)
                if (state.resetting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.resetMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.resetLog?.let { LogBox(it) }
            }
            if (state.checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.connectProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else {
                h?.error?.let { com.farrow.app.ui.components.CopyableText(it, color = MaterialTheme.colorScheme.error, monospace = false) }
            }
            if (!state.checking && h?.error != null) {
                if (state.bridgeLogTail != null) {
                    Text("Port listening inside Termux: ${state.bridgeListening ?: "?"} · last bridge.log lines:", style = MaterialTheme.typography.labelMedium)
                    LogBox(state.bridgeLogTail)
                } else if (state.runCommandGranted) {
                    Text("Fetching ~/.farrow/bridge.log from Termux…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Grant RUN_COMMAND to see ~/.farrow/bridge.log here, or run `tail ~/.farrow/bridge.log` in Termux.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::connect, enabled = !state.checking) { Text("Connect / Re-check") }
                if (!state.termuxInstalled) {
                    OutlinedButton(onClick = onInstallTermux) { Text("Get Termux") }
                } else {
                    OutlinedButton(onClick = onOpenTermux) { Text("Open Termux") }
                    if (!state.runCommandGranted) OutlinedButton(onClick = onGrant) { Text("Grant RUN_COMMAND") }
                }
            }
        }
    }
}

/** v1.0.7: the app-scoped browser action — running (step + Cancel) or its last result, also after leaving the screen. */
@Composable
private fun OpsCard(ops: com.farrow.app.data.browser.BrowserOpsState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (!ops.busy && ops.lastResult == null) return
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ops.busy) {
                Text(com.farrow.app.data.browser.BrowserOpsNotifText.title(ops), style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(com.farrow.app.data.browser.BrowserOpsNotifText.text(ops), style = MaterialTheme.typography.bodySmall)
                Text("Keeps running if you leave this screen (see the notification).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                val at = ops.finishedAt?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }
                Text("Last browser action" + (at?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text(ops.lastResult.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = if (ops.lastOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun SetupAllCard(state: BrowserSetupState, vm: BrowserSetupViewModel, onGrant: () -> Unit, onInstallTermux: () -> Unit) {
    val all = state.setupAll
    val installed = state.install?.allInstalled == true
    val connected = state.health?.fullyUp == true
    val bridgeOnly = state.health?.let { it.bridgeOk && !it.daemonRunning } == true
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (installed) "Browser is installed" else "One-tap setup", style = MaterialTheme.typography.titleMedium)
            Text(when {
                connected -> "✅ Bridge connected and TBP daemon running. Nothing to do."
                bridgeOnly -> "Bridge connected, but the TBP daemon is not running — tap Start browser to start it."
                installed -> "Everything is installed — this only starts the bridge and daemon (nothing is reinstalled)."
                state.install?.termuxAnswered == true -> "Missing: ${state.install.missing.joinToString { "step $it" }}. Only these are installed; the rest is skipped."
                else -> "Checks Termux, the permission and allow-external-apps, installs what is missing in one Termux session, then starts the bridge and connects."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val running = all?.running == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (!vm.setupEverything()) onGrant() }, enabled = !state.ops.busy) {
                    Text(if (installed) "Start browser" else "Set up everything")
                }
                if (running) TextButton(onClick = vm::cancelSetupAll) { Text("Stop waiting") }
                if (!state.termuxInstalled) OutlinedButton(onClick = onInstallTermux) { Text("Install Termux") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.autoStart, onCheckedChange = vm::setAutoStart)
                Spacer(Modifier.width(8.dp))
                Text("Auto-start bridge when needed (app launch, boot with keep-alive, web tools; waits ≤ 15 s)", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.showTermux, onCheckedChange = vm::setShowTermux)
                Spacer(Modifier.width(8.dp))
                Text("Show the Termux window during setup (off: runs in the background; when on, Termux closes itself and " +
                    "Farrow comes back once setup succeeds)", style = MaterialTheme.typography.bodySmall)
            }
            Text("Browser language", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.farrow.app.data.browser.BrowserLanguage.ALL.forEach { l ->
                    FilterChip(selected = state.browserLanguage == l.code, onClick = { vm.setBrowserLanguage(l.code) }, label = { Text(l.code.uppercase()) })
                }
            }
            Text("${com.farrow.app.data.browser.BrowserLanguage.of(state.browserLanguage).label}: pages, Google (hl/gl, google.com) and " +
                "DuckDuckGo results in this language. Default English.", style = MaterialTheme.typography.bodySmall)
            state.languageNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.loadImages, onCheckedChange = vm::setLoadImages)
                Spacer(Modifier.width(8.dp))
                Text("Load images and video in the internal browser (off: blocked — faster pages, the agent reads text). " +
                    "Applies the next time the internal browser starts (no restart is forced).", style = MaterialTheme.typography.bodySmall)
            }
            state.mediaNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (all != null) {
                if (all.phase.isNotBlank()) Text(all.phase, style = MaterialTheme.typography.bodyMedium)
                if (running || all.statuses.isNotEmpty()) {
                    val done = (2..5).count { all.statuses[it] == StepState.SUCCEEDED || all.statuses[it] == StepState.SKIPPED }
                    if (running && all.statuses.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { done / 4f }, modifier = Modifier.fillMaxWidth())
                    (2..5).forEach { n ->
                        val st = all.statuses[n] ?: StepState.NOT_RUN
                        val title = vm.steps.firstOrNull { it.number == n }?.title ?: "Step $n"
                        Text("$title — ${when (st) {
                            StepState.SUCCEEDED -> "done"; StepState.SKIPPED -> "already done"; StepState.FAILED -> "failed"
                            StepState.RUNNING -> "running"; else -> if (all.currentStep == n && running) "running" else "pending"
                        }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                all.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.farrow.app.ui.components.CopyButton(err + (if (all.logTail.isNotBlank()) "\n\n" + all.logTail else ""), label = "Copy error")
                        Button(onClick = { if (!vm.setupEverything(all.failedStep ?: 2)) onGrant() }) {
                            Text(all.failedStep?.let { "Retry from step $it" } ?: "Retry")
                        }
                    }
                }
                all.manualCommand?.let { _ ->
                    Text("Paste once into Termux:", style = MaterialTheme.typography.labelMedium)
                    Text(StepScripts.ALLOW_ONE_LINER,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    com.farrow.app.ui.components.CopyButton(StepScripts.ALLOW_ONE_LINER, label = "Copy command")
                }
                if (all.logTail.isNotBlank()) LogBox(all.logTail)
                if (!running && (all.done || all.error != null)) TextButton(onClick = vm::dismissSetupAll) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: SetupStep,
    ui: StepUi,
    canRun: Boolean,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onCheck: () -> Unit,
    onViewLog: () -> Unit,
    statusAvailable: Boolean,
) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(when (ui.state) {
                    StepState.NOT_RUN -> "Not run"
                    StepState.RUNNING -> "⏳ Running…"
                    StepState.SUCCEEDED -> "✅ Succeeded"
                    StepState.SKIPPED -> "⏭️ Already done"
                    StepState.FAILED -> "❌ Failed (exit ${ui.exitCode})"
                    StepState.UNKNOWN -> "❔ Unknown"
                }, style = MaterialTheme.typography.labelMedium)
            }
            Text(step.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.logTail.isNotBlank()) {
                Text(ui.logTail.lines().takeLast(3).joinToString("\n"), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.runnable) Button(onClick = onRun, enabled = canRun) { Text("Run in Termux") }
                OutlinedButton(onClick = onCopy) { Text("Copy") }
                if (statusAvailable) {
                    OutlinedButton(onClick = onViewLog) { Text("View log") }
                    if (ui.logTail.isNotBlank()) com.farrow.app.ui.components.CopyButton(ui.logTail, label = "Copy log")
                    TextButton(onClick = onCheck) { Text("Check") }
                }
            }
        }
    }
}

@Composable
private fun LogBox(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            com.farrow.app.ui.components.CopyButton(text)
        }
    }
}

@Composable
private fun FingerprintCard(state: BrowserSetupState, vm: BrowserSetupViewModel) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device fingerprint", style = MaterialTheme.typography.titleSmall)
            val fp = state.fingerprint
            if (fp == null) Text("Collecting…") else {
                Text("${fp.manufacturer} ${fp.model} · Android ${fp.androidRelease} (SDK ${fp.sdkInt})", style = MaterialTheme.typography.bodySmall)
                Text("Screen ${fp.screenWidthPx}×${fp.screenHeightPx} @ ${fp.densityDpi}dpi, ${fp.refreshRate.toInt()} Hz", style = MaterialTheme.typography.bodySmall)
                Text("CPU ${fp.cpuHardware ?: "?"} · ${fp.cpuCores} cores · ${fp.abis.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("GPU ${fp.glVendor ?: "?"} ${fp.glRenderer ?: ""}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::pushFingerprint, enabled = state.health?.reachable == true) { Text("Send to bridge") }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (ok) "yes" else "no", style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
