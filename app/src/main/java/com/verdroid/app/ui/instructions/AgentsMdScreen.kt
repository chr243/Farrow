package com.verdroid.app.ui.instructions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.verdroid.app.data.instructions.AgentsMdStore
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AgentsMdViewModel @Inject constructor(val store: AgentsMdStore) : ViewModel()

/** Settings → AGENTS.md: the user's standing instructions, added to every chat's system prompt. */
@Composable
fun AgentsMdScreen(onBack: () -> Unit, vm: AgentsMdViewModel = hiltViewModel()) {
    val saved = vm.store.text.collectAsState().value
    var text by rememberSaveable { mutableStateOf(saved) }
    var confirmClear by remember { mutableStateOf(false) }
    val dirty = AgentsMdStore.clean(text) != saved
    BackScaffold("AGENTS.md", onBack, actions = {
        TextButton(onClick = { text = vm.store.save(text) }, enabled = dirty) { Text("Save") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding()) {
            Text("Standing instructions for the agent, added to every chat (how you like answers, rules, context about you). " +
                "Leave empty to send nothing. Max ${AgentsMdStore.MAX_CHARS} characters.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(AgentsMdStore.MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("# My agent rules\n- Reply briefly\n- Save outputs to Output/") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${text.length} / ${AgentsMdStore.MAX_CHARS}" + if (dirty) " · unsaved" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmClear = true }, enabled = text.isNotEmpty() || saved.isNotEmpty()) { Text("Clear") }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear AGENTS.md?") },
        text = { Text("The agent will no longer get these instructions.") },
        confirmButton = { TextButton(onClick = { text = vm.store.save(""); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
