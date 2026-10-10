package com.verdroid.app.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.domain.model.NotificationType
import com.verdroid.app.domain.repository.NotificationRepository
import com.verdroid.app.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repo: NotificationRepository,
    val prefs: com.verdroid.app.data.prefs.AppPrefs,
) : ViewModel() {
    val items = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun markAllRead() = viewModelScope.launch { repo.markAllRead() }
    fun clear() = viewModelScope.launch { repo.clear() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onOpenChat: (Long) -> Unit, onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    LaunchedEffect(items.count { !it.read }) { delay(1500); vm.markAllRead() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Notifications") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = { vm.clear() }) { Icon(Icons.Filled.Delete, "Clear all") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val notifyFinish by vm.prefs.notifyOnFinish.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text("Notify when a task finishes") },
                    supportingContent = { Text("Off: only rate limits, quota warnings, and failures notify.") },
                    trailingContent = { Switch(checked = notifyFinish, onCheckedChange = vm.prefs::setNotifyOnFinish) },
                )
                HorizontalDivider()
            }
            if (items.isEmpty()) item {
                Text("Rate-limit alerts, quota warnings and failures will appear here.", textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
            items(items, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text(n.title, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold) },
                    supportingContent = { Text(n.body, maxLines = 3) },
                    trailingContent = { Text(formatTimestamp(n.createdAt), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.clickable(enabled = n.taskId != null) { n.taskId?.let(onOpenChat) },
                )
                HorizontalDivider()
            }
        }
    }
}
