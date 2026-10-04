package com.farrow.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.data.mcp.McpManager
import com.farrow.app.data.mcp.McpNaming
import com.farrow.app.data.mcp.McpServerConfig
import com.farrow.app.data.mcp.McpState

/** Settings > MCP servers: remote servers (Streamable HTTP / legacy SSE), on/off, status, per-tool switches. */
@Composable
fun McpServersSection(mcp: McpManager, disabled: Set<String>, setToolEnabled: (String, Boolean) -> Unit) {
    val servers by mcp.servers.collectAsStateWithLifecycle()
    val status by mcp.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 20.dp))
            TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 20.dp)) { Text("Add server") }
        }
        Text("Remote MCP servers (Streamable HTTP or legacy SSE URL). Their tools reach the model as mcp__<server>__<tool>. " +
            "The examples are public and need no key; they are off until you turn them on.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        servers.forEachIndexed { idx, s ->
            val st = status[s.id]
            Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = com.farrow.app.ui.components.Zebra.color(idx)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(s.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { mcp.setEnabled(s.id, it) })
                    }
                    val tools = st?.tools.orEmpty()
                    val line = when (st?.state ?: McpState.OFF) {
                        McpState.OFF -> "Off" + if (tools.isNotEmpty()) " (${tools.size} tools last time)" else ""
                        McpState.CONNECTING -> "Connecting…"
                        McpState.CONNECTED -> "Connected: ${tools.size} tools" + (st?.transport?.let { " ($it)" } ?: "")
                        McpState.ERROR -> "Error"
                    }
                    Text(line, style = MaterialTheme.typography.labelMedium,
                        color = when (st?.state) { McpState.CONNECTED -> MaterialTheme.colorScheme.primary; McpState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    st?.error?.takeIf { st.state == McpState.ERROR }?.let { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6)
                        com.farrow.app.ui.components.CopyButton(err, label = "Copy error")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tools.isNotEmpty()) TextButton(onClick = { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id }) {
                            Text(if (s.id in expanded) "Hide tools" else "Tools (${tools.size})")
                        }
                        if (s.enabled) TextButton(onClick = { mcp.refresh(s.id) }) { Text("Reconnect") }
                        TextButton(onClick = { editing = s }) { Text("Edit") }
                    }
                    if (s.id in expanded) tools.forEach { t ->
                        val exposed = McpNaming.toolName(s.name, t.name)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            Switch(checked = exposed !in disabled, onCheckedChange = { setToolEnabled(exposed, it) })
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        McpServerDialog(initial = editing, hasSecret = editing?.let { mcp.hasSecret(it.id) } == true,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { e -> { mcp.delete(e.id); editing = null } },
            onSave = { name, url, header, secret ->
                mcp.upsert(editing?.id, name, url, header, secret, editing?.enabled ?: true)
                adding = false; editing = null
            })
    }
}

@Composable
private fun McpServerDialog(initial: McpServerConfig?, hasSecret: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?,
                            onSave: (name: String, url: String, header: String, secret: String?) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url ?: "https://") }
    var header by remember { mutableStateOf(initial?.authHeaderName ?: "Authorization") }
    var secret by remember { mutableStateOf("") }
    var clearSecret by remember { mutableStateOf(false) }
    val urlOk = url.trim().let { it.startsWith("https://") || it.startsWith("http://") } && url.trim().length > 10
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add MCP server" else "Edit ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL (…/mcp or legacy …/sse)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = !urlOk)
                OutlinedTextField(header, { header = it }, label = { Text("Auth header (optional)") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (hasSecret) "Token: saved (type to replace)" else "Token or header value (optional)") },
                    supportingText = { Text("Stored encrypted. A bare token is sent as \"Bearer <token>\".") })
                if (hasSecret) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { clearSecret = !clearSecret }) {
                    Checkbox(clearSecret, { clearSecret = it }); Text("Remove the saved token")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && urlOk, onClick = {
                onSave(name, url, header, when { clearSecret -> ""; secret.isNotBlank() -> secret; else -> null })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        })
}
