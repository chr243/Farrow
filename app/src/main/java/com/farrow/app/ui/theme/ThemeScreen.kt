package com.farrow.app.ui.theme

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Settings > Theme: mode (System/Light/Dark) + palette. Text rows; the choice is saved and applies instantly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val store = ThemeStore.get(LocalContext.current)
    val mode by store.mode.collectAsState()
    val palette by store.palette.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Theme") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header("Mode")
            ThemeMode.entries.forEach { m ->
                Row(m.label, when (m) { ThemeMode.SYSTEM -> "Follow the phone's dark mode"; ThemeMode.LIGHT -> "Always light"; ThemeMode.DARK -> "Always dark" },
                    selected = mode == m) { store.setMode(m) }
            }
            Header("Colors")
            Palette.entries.forEach { p ->
                val unavailable = p == Palette.DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                Row(p.label, if (unavailable) "${p.description} — not available on this Android version, uses Messenger Blue" else p.description,
                    selected = palette == p) { store.setPalette(p) }
            }
            Text("Chat bubbles and the chat head follow the theme.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun Row(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
