package com.verdroid.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.verdroid.app.domain.repository.NotificationRepository
import com.verdroid.app.ui.chat.ChatDetailScreen
import com.verdroid.app.ui.device.DeviceControlScreen
import com.verdroid.app.ui.device.KeepAliveScreen
import com.verdroid.app.ui.chats.ChatsScreen
import com.verdroid.app.ui.menu.*
import com.verdroid.app.ui.notifications.NotificationsScreen
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
    const val SKILLS = "settings/skills"
    const val AGENTS_MD = "settings/agents-md"
    const val PERMISSIONS = "settings/permissions"
    const val THEME = "settings/theme"
    const val ARCHIVE = "settings/archive"
    const val MEMORY = "settings/memory?chat={chat}"
    fun memory(chatId: Long? = null) = "settings/memory?chat=${chatId ?: -1}"
    fun chat(taskId: Long) = "chat/$taskId"
}

/**
 * Subtle Material "shared axis X"-style motion for every screen (chats, Settings and its sub-pages):
 * the new screen fades in while sliding a short distance from the right; the old one fades out and
 * drifts slightly left. Back (pop) runs the same motion in reverse. Short and small on purpose.
 */
private object NavMotion {
    private const val DURATION_MS = 260
    private const val FADE_OUT_MS = 120
    private const val FADE_IN_DELAY_MS = 60

    // Fractions of the screen width: a nudge, not a full page slide.
    private fun near(width: Int) = width / 10
    private fun far(width: Int) = width / 20

    val enter: EnterTransition =
        fadeIn(tween(DURATION_MS - FADE_IN_DELAY_MS, delayMillis = FADE_IN_DELAY_MS, easing = LinearOutSlowInEasing)) +
            slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { near(it) }
    val exit: ExitTransition =
        fadeOut(tween(FADE_OUT_MS, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { -far(it) }
    val popEnter: EnterTransition =
        fadeIn(tween(DURATION_MS - FADE_IN_DELAY_MS, delayMillis = FADE_IN_DELAY_MS, easing = LinearOutSlowInEasing)) +
            slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { -far(it) }
    val popExit: ExitTransition =
        fadeOut(tween(FADE_OUT_MS, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { near(it) }
}

@HiltViewModel
class RootViewModel @Inject constructor(notifications: NotificationRepository) : ViewModel() {
    val unread = notifications.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

@Composable
fun VerdroidRoot(
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
        // Subtle fade + short horizontal slide on open/back (see NavMotion).
        NavHost(
            nav,
            startDestination = Routes.CHATS,
            enterTransition = { NavMotion.enter },
            exitTransition = { NavMotion.exit },
            popEnterTransition = { NavMotion.popEnter },
            popExitTransition = { NavMotion.popExit },
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
                    com.verdroid.app.chathead.VisibleChat.taskId = chatId
                    onDispose { if (com.verdroid.app.chathead.VisibleChat.taskId == chatId) com.verdroid.app.chathead.VisibleChat.taskId = null }
                }
                ChatDetailScreen(onBack = { nav.popBackStack() },
                    onChatMemory = { id -> nav.navigate(Routes.memory(id)) })
            }
            composable(Routes.KEYS) { ApiKeysScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MODELS) { ModelPriorityScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LIMITS) { RateLimitSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SHIZUKU) { DeviceControlScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.KEEP_ALIVE) { KeepAliveScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MCP) { com.verdroid.app.ui.tools.McpServersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TOOLS) { com.verdroid.app.ui.tools.ToolsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PERMISSIONS) {
                com.verdroid.app.ui.permissions.PermissionsScreen(onBack = { nav.popBackStack() }, onTermuxSetup = { nav.navigate(Routes.TOOLS) })
            }
            composable(Routes.AGENTS_MD) { com.verdroid.app.ui.instructions.AgentsMdScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SKILLS) { com.verdroid.app.ui.skills.SkillsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MEMORY, arguments = listOf(navArgument("chat") { type = NavType.LongType; defaultValue = -1L })) {
                com.verdroid.app.ui.memory.MemoryScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ARCHIVE) {
                com.verdroid.app.ui.archive.ArchiveScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(Routes.chat(it)) })
            }
            composable(Routes.THEME) { com.verdroid.app.ui.theme.ThemeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHAT_HEADS) { ChatHeadSettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
