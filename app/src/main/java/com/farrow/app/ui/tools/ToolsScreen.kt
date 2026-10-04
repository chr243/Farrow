package com.farrow.app.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.farrow.app.ui.components.groupedRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings > Tools: built-in agent tools (status + persisted on/off) and optional Termux add-ons. MCP has its own screen. Text only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val disabled by vm.prefs.disabled.collectAsStateWithLifecycle()
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
                                if (p.state == PkgState.FAILED) com.farrow.app.ui.components.CopyButton(d)
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
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Settings > MCP servers: remote MCP servers, their status and per-tool switches (split out of Tools). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
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
