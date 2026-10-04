package com.farrow.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** Scaffold with a back arrow, shared by setup screens outside the menu package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, actions = actions,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }, content = content)
}

/** A card showing a shell command with Copy (and optionally Run) buttons. */
@Composable
fun CommandCard(
    title: String,
    description: String,
    command: String,
    onCopy: () -> Unit,
    onRun: (() -> Unit)? = null,
    runEnabled: Boolean = true,
    maxPreviewLines: Int = 6,
) {
    androidx.compose.material3.ElevatedCard {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Text(description, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.material3.MaterialTheme.shapes.small,
            ) {
                Text(command, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    maxLines = maxPreviewLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = androidx.compose.ui.Modifier.padding(8.dp))
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onCopy) { Text("Copy") }
                if (onRun != null) androidx.compose.material3.Button(onClick = onRun, enabled = runEnabled) { Text("Run in Termux") }
            }
        }
    }
}
