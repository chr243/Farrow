package com.farrow.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.farrow.app.domain.repository.NotificationRepository
import com.farrow.app.ui.chat.ChatDetailScreen
import com.farrow.app.ui.device.DeviceControlScreen
import com.farrow.app.ui.device.KeepAliveScreen
import com.farrow.app.ui.chats.ChatsScreen
import com.farrow.app.ui.menu.*
import com.farrow.app.ui.notifications.NotificationsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val CHATS = "chats"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val MCP = "settings/mcp"
    const val CHAT = "chat/{taskId}"
    const val KEYS = "settings/keys"
    const val MODELS = "settings/models"
    const val LIMITS = "settings/limits"
    const val SHIZUKU = "shizuku"
    const val CHAT_HEADS = "settings/chatheads"
    const val KEEP_ALIVE = "settings/keepalive"
    const val TOOLS = "settings/tools"
    const val THEME = "settings/theme"
    const val ARCHIVE = "settings/archive"
    const val MEMORY = "settings/memory?chat={chat}"
    fun memory(chatId: Long? = null) = "settings/memory?chat=${chatId ?: -1}"
    fun chat(taskId: Long) = "chat/$taskId"
}

@HiltViewModel
class RootViewModel @Inject constructor(notifications: NotificationRepository) : ViewModel() {
    val unread = notifications.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

@Composable
fun FarrowRoot(
    openTaskId: Long? = null,
    onOpenTaskConsumed: () -> Unit = {},
    vm: RootViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val unread by vm.unread.collectAsStateWithLifecycle()

    LaunchedEffect(openTaskId) {
        if (openTaskId != null && openTaskId != 0L) {
            nav.navigate(Routes.chat(openTaskId)) { launchSingleTop = true }
            onOpenTaskConsumed()
        }
    }

    // No bottom bar (v0.9.18): the chat list is home; Settings opens from its gear.
    run {
        // Transitions disabled everywhere so tab switches and navigation are instant (no crossfade).
        NavHost(
            nav,
            startDestination = Routes.CHATS,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.CHATS) {
                ChatsScreen(
                    onOpenChat = { nav.navigate(Routes.chat(it)) },
                    onNewChat = { nav.navigate(Routes.chat(0)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onOpenKeys = { nav.navigate(Routes.KEYS) },
                    onOpenModels = { nav.navigate(Routes.MODELS) },
                )
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(onOpenChat = { nav.navigate(Routes.chat(it)) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onNavigate = { nav.navigate(it) }, unreadNotifications = unread,
                    onBack = { if (!nav.popBackStack(Routes.CHATS, inclusive = false)) nav.navigate(Routes.CHATS) })
            }
            composable(Routes.CHAT, arguments = listOf(navArgument("taskId") { type = NavType.LongType })) {
                val chatId = it.arguments?.getLong("taskId")
                androidx.compose.runtime.DisposableEffect(chatId) {
                    com.farrow.app.chathead.VisibleChat.taskId = chatId
                    onDispose { if (com.farrow.app.chathead.VisibleChat.taskId == chatId) com.farrow.app.chathead.VisibleChat.taskId = null }
                }
                ChatDetailScreen(onBack = { nav.popBackStack() },
                    onChatMemory = { id -> nav.navigate(Routes.memory(id)) })
            }
            composable(Routes.KEYS) { ApiKeysScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MODELS) { ModelPriorityScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LIMITS) { RateLimitSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SHIZUKU) { DeviceControlScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.KEEP_ALIVE) { KeepAliveScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MCP) { com.farrow.app.ui.tools.McpServersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TOOLS) { com.farrow.app.ui.tools.ToolsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MEMORY, arguments = listOf(navArgument("chat") { type = NavType.LongType; defaultValue = -1L })) {
                com.farrow.app.ui.memory.MemoryScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ARCHIVE) {
                com.farrow.app.ui.archive.ArchiveScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(Routes.chat(it)) })
            }
            composable(Routes.THEME) { com.farrow.app.ui.theme.ThemeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHAT_HEADS) { ChatHeadSettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
