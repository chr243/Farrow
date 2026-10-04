package com.farrow.app.chathead

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.content.ContextCompat
import android.view.KeyEvent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import android.app.Service
import android.os.IBinder
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.farrow.app.MainActivity
import com.farrow.app.R
import com.farrow.app.domain.model.ChatMessage
import com.farrow.app.domain.model.Conversation
import com.farrow.app.domain.model.ToolCallRecord
import com.farrow.app.domain.repository.AgentController
import com.farrow.app.domain.repository.TaskRepository
import com.farrow.app.domain.usecase.SendMessageUseCase
import com.farrow.app.ui.chathead.ChatHeadAvatar
import com.farrow.app.ui.chathead.CompactChatPanel
import com.farrow.app.ui.chathead.DismissTarget
import com.farrow.app.ui.theme.FarrowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

/**
 * SYSTEM_ALERT_WINDOW fallback (HyperOS/MIUI): a draggable chat head that snaps to the screen edge,
 * can be dragged onto a ✕ target to dismiss, and expands into a compact Compose chat panel.
 * Runs as a specialUse foreground service so the overlay survives while other apps are in front.
 */
@AndroidEntryPoint
class ChatHeadService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    @Inject lateinit var tasks: TaskRepository
    @Inject lateinit var agent: AgentController
    @Inject lateinit var sendMessage: SendMessageUseCase
    @Inject lateinit var notifier: BubbleNotifier

    // Own lifecycle (LifecycleService never goes past STARTED); driven to RESUMED so Compose input/frames run normally.
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var windowManager: WindowManager
    private var headView: View? = null
    private var panelView: View? = null
    private var dismissView: ComposeView? = null
    private var headParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private val prefs by lazy { getSharedPreferences("chat_head", Context.MODE_PRIVATE) }

    private val taskIdState = mutableLongStateOf(0L)
    private val expandedState = mutableStateOf(false)
    private val nearDismissState = mutableStateOf(false)

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private val screenWidth: Int get() = windowManager.currentWindowMetrics.bounds.width()
    private val screenHeight: Int get() = windowManager.currentWindowMetrics.bounds.height()

    /** Status bar / nav bar / cutout insets, so the head is never parked under the clock or the gesture bar. */
    private fun safeInsets(): android.graphics.Insets = windowManager.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())

    override fun onCreate() {
        super.onCreate()
        // Must attach/restore while the lifecycle is still INITIALIZED.
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WindowManager::class.java)
        Log.d(TAG, "onCreate lifecycle=${lifecycleRegistry.currentState}")
    }

    /**
     * Panel root: sees ACTION_OUTSIDE (FLAG_WATCH_OUTSIDE_TOUCH) before Compose, and Back (the panel window is
     * focusable) before any child; everything else goes to Compose.
     */
    @SuppressLint("ViewConstructor")
    private class OutsideTouchLayout(context: Context, private val onOutside: (MotionEvent) -> Unit,
                                     private val onBack: () -> Unit) : FrameLayout(context) {
        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(ev); return true }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            when (ChatHeadPanelPolicy.onKey(event.action, event.keyCode, event.isCanceled)) {
                ChatHeadPanelPolicy.KeyDecision.COLLAPSE -> { onBack(); true }
                ChatHeadPanelPolicy.KeyDecision.CONSUME -> true
                ChatHeadPanelPolicy.KeyDecision.IGNORE -> super.dispatchKeyEvent(event)
            }
    }

    /** Home / Recents while the panel is open → collapse (overlays never receive KEYCODE_HOME). */
    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getStringExtra("reason")
            Log.d(TAG, "CLOSE_SYSTEM_DIALOGS reason=$reason")
            if (panelView != null && ChatHeadPanelPolicy.collapseOnSystemDialogs(reason)) collapse()
        }
    }
    private var homeReceiverRegistered = false

    @Suppress("DEPRECATION")
    private fun registerHomeReceiver() {
        if (homeReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(this, homeReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED)
            homeReceiverRegistered = true
        }.onFailure { Log.w(TAG, "home receiver failed", it) }
    }

    private fun unregisterHomeReceiver() {
        if (!homeReceiverRegistered) return
        homeReceiverRegistered = false
        runCatching { unregisterReceiver(homeReceiver) }
    }

    private val positions by lazy {
        HeadPositionStore({ k -> if (prefs.contains(k)) prefs.getInt(k, 0) else null }, { k, v -> prefs.edit().putInt(k, v).apply() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra(EXTRA_TASK_ID, 0L) ?: 0L
        if (id != 0L) taskIdState.longValue = id
        // startForegroundService() requires startForeground() promptly, even if we bail out below.
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        showHead()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        notifier.ensureChannels()
        val open = PendingIntent.getActivity(
            this, 0, MainActivity.openTaskIntent(this, taskIdState.longValue),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, BubbleNotifier.CHANNEL_CHAT_HEAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Farrow chat head")
            .setContentText("Tap the head to chat · drag it onto ✕ to close")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Close", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FGS_NOTIFICATION_ID, notification)
        }
    }

    private fun View.attachOwners() {
        setViewTreeLifecycleOwner(this@ChatHeadService)
        setViewTreeSavedStateRegistryOwner(this@ChatHeadService)
        setViewTreeViewModelStoreOwner(this@ChatHeadService)
    }

    /** Owners are set BEFORE addView so the composition starts with a RESUMED lifecycle. */
    private fun composeView(content: @Composable () -> Unit): ComposeView = ComposeView(this).apply {
        attachOwners()
        setContent { FarrowTheme { content() } }
    }

    /**
     * Root of the head window. It takes every touch BEFORE the Compose child sees it: Material3 `Surface` installs an
     * empty `pointerInput` (to block click-through), so the inner AndroidComposeView claimed every DOWN and a touch
     * listener on the ComposeView (a ViewGroup) never fired — the v0.9.0 "can't tap / can't drag" bug.
     */
    @SuppressLint("ViewConstructor")
    private class TouchInterceptLayout(context: Context, private val handler: View.OnTouchListener) : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = handler.onTouch(this, event)
        override fun performClick(): Boolean = super.performClick()
    }

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    )

    /**
     * The head window's flags never change: params.x/y are screen coordinates (LAYOUT_IN_SCREEN + cutout ALWAYS), the
     * same frame as MotionEvent.rawX/rawY, and the window is never NOT_TOUCHABLE (it is removed while expanded).
     */
    private fun headParamsFor(x: Int, y: Int) = overlayParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, HEAD_FLAGS,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        this.x = x
        this.y = y
    }

    private fun clampX(x: Int): Int {
        val i = safeInsets()
        return x.coerceIn(i.left, (screenWidth - i.right - headWidth()).coerceAtLeast(i.left))
    }

    private fun clampY(y: Int): Int {
        val i = safeInsets()
        return y.coerceIn(i.top + dp(8), (screenHeight - i.bottom - headHeight() - dp(8)).coerceAtLeast(i.top + dp(8)))
    }

    private fun savePosition(p: WindowManager.LayoutParams) = positions.save(p.x, p.y)

    private fun logHeadPosition(where: String) {
        val v = headView ?: return
        val p = headParams ?: return
        v.post {
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            Log.d(TAG, "$where: params=(${p.x}, ${p.y}) onScreen=(${loc[0]}, ${loc[1]}) size=${v.width}x${v.height} " +
                "attached=${v.isAttachedToWindow} visible=${v.visibility == View.VISIBLE} flags=0x${Integer.toHexString(p.flags)}")
        }
    }

    // ---------------------------------------------------------------- chat head

    @SuppressLint("ClickableViewAccessibility")
    private fun showHead() {
        if (headView != null) return
        val i = safeInsets()
        val defX = screenWidth - i.right - dp(HEAD_SIZE_DP) - dp(EDGE_MARGIN_DP)
        val params = headParamsFor(prefs.getInt(KEY_X, defX), prefs.getInt(KEY_Y, screenHeight / 4))
        params.x = clampX(params.x); params.y = clampY(params.y)
        val content = composeView {
            val conversations by remember { tasks.observeConversations() }.collectAsState(initial = emptyList<Conversation>())
            val current = conversations.firstOrNull { it.task.id == taskIdState.longValue }
            ChatHeadAvatar(task = current?.task, unread = if (expandedState.value) 0 else current?.unreadCount ?: 0)
        }
        val root = TouchInterceptLayout(this, HeadTouchListener()).apply {
            attachOwners()
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        headParams = params
        headView = root
        windowManager.addView(root, params)
        logHeadPosition("head added")
    }

    private inner class HeadTouchListener : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@ChatHeadService).scaledTouchSlop
        private var offsetX = 0f
        private var offsetY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = headParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A running snap would otherwise keep writing old coordinates under the finger.
                    snapAnimator?.cancel(); snapAnimator = null
                    downRawX = event.rawX
                    downRawY = event.rawY
                    offsetX = event.rawX - params.x
                    offsetY = event.rawY - params.y
                    dragging = false
                    Log.d(TAG, "head DOWN raw=(${event.rawX}, ${event.rawY}) pos=(${params.x}, ${params.y})")
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) {
                        dragging = true
                        Log.d(TAG, "drag start")
                        showDismissTarget()
                    }
                    if (dragging) {
                        // Follow the finger exactly: window origin = finger − grab offset.
                        params.x = (event.rawX - offsetX).toInt().coerceIn(-headWidth() / 2, screenWidth - headWidth() / 2)
                        params.y = (event.rawY - offsetY).toInt().coerceIn(0, screenHeight - headHeight() / 2)
                        updateHead()
                        nearDismissState.value = isNearDismiss()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "head UP dragging=$dragging pos=(${params.x}, ${params.y})")
                    if (!dragging) {
                        v.performClick()
                        expand()
                    } else {
                        val dismiss = isNearDismiss()
                        hideDismissTarget()
                        if (dismiss) stopSelf() else snapToEdge()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        hideDismissTarget()
                        snapToEdge()
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    private fun updateHead() {
        val v = headView ?: return
        val p = headParams ?: return
        if (v.isAttachedToWindow) runCatching { windowManager.updateViewLayout(v, p) }.onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    private fun headWidth(): Int = headView?.width?.takeIf { it > 0 } ?: dp(HEAD_SIZE_DP)
    private fun headHeight(): Int = headView?.height?.takeIf { it > 0 } ?: dp(HEAD_SIZE_DP)

    private fun snapToEdge() {
        val p = headParams ?: return
        val w = headWidth()
        val i = safeInsets()
        val targetX = if (p.x + w / 2 < screenWidth / 2) i.left + dp(EDGE_MARGIN_DP) else screenWidth - i.right - w - dp(EDGE_MARGIN_DP)
        p.y = clampY(p.y)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 220
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                updateHead()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { savePosition(p); logHeadPosition("snapped") }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- drag-to-dismiss target

    private fun showDismissTarget() {
        if (dismissView != null) return
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            y = dismissBottom()
        }
        val view = composeView { DismissTarget(near = nearDismissState.value) }
        dismissView = view
        windowManager.addView(view, params)
    }

    private fun hideDismissTarget() {
        nearDismissState.value = false
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
    }

    private fun dismissBottom(): Int = dp(DISMISS_BOTTOM_DP) + safeInsets().bottom

    private fun isNearDismiss(): Boolean {
        val p = headParams ?: return false
        val headCx = p.x + headWidth() / 2f
        val headCy = p.y + headHeight() / 2f
        val targetCx = screenWidth / 2f
        val targetCy = screenHeight - dismissBottom() - dp(DISMISS_SIZE_DP) / 2f
        return hypot(headCx - targetCx, headCy - targetCy) < dp(DISMISS_SIZE_DP) * 1.3f
    }

    // ---------------------------------------------------------------- expanded panel

    /**
     * The head window is REMOVED while the panel is open and re-added afterwards with the SAME params object at the
     * saved x/y. v0.9.2 instead moved the head window to the top corner while expanded and moved it back with
     * updateViewLayout on collapse; on HyperOS the move-back was applied to the window's input frame but its surface
     * kept the old position until the next redraw, so the head was drawn over the clock while touches there went
     * through to the app below (and the first touch made it "teleport").
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun expand() {
        val p = headParams ?: return
        if (panelView != null) return
        snapAnimator?.cancel(); snapAnimator = null
        savePosition(p)
        headView?.let { v -> if (v.isAttachedToWindow) runCatching { windowManager.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove head failed", it) } }

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, (screenHeight * 0.62f).toInt(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val content = composeView { PanelContent() }
        val view = OutsideTouchLayout(this, onOutside = { _ ->
            Log.d(TAG, "panel ACTION_OUTSIDE")
            collapse()
        }, onBack = {
            Log.d(TAG, "panel Back")
            collapse()
        }).apply {
            attachOwners()
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        panelView = view
        expandedState.value = true
        windowManager.addView(view, params)
        view.post { view.requestFocus() }
        registerHomeReceiver()
        Log.d(TAG, "panel expanded; head removed at saved (${p.x}, ${p.y})")
    }

    private fun collapse() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        unregisterHomeReceiver()
        if (!expandedState.value) return
        expandedState.value = false
        val v = headView ?: return
        val p = headParams ?: return
        val i = safeInsets()
        val (x, y) = positions.restore(p.x, p.y, HeadPositionStore.Bounds(i.left, screenWidth - i.right - headWidth(),
            i.top + dp(8), screenHeight - i.bottom - headHeight() - dp(8)))
        p.x = x; p.y = y
        p.flags = HEAD_FLAGS // never left NOT_TOUCHABLE or with different layout flags
        if (!v.isAttachedToWindow) runCatching { windowManager.addView(v, p) }.onFailure { Log.w(TAG, "re-add head failed", it) }
        else updateHead()
        v.visibility = View.VISIBLE
        v.requestLayout(); v.invalidate()
        logHeadPosition("after collapse")
    }

    @Composable
    private fun PanelContent() {
        val id = taskIdState.longValue
        val task by remember(id) { tasks.observeTask(id) }.collectAsState(initial = null)
        val messages by remember(id) { tasks.observeMessages(id) }.collectAsState(initial = emptyList<ChatMessage>())
        val toolCalls by remember(id) { tasks.observeToolCalls(id) }.collectAsState(initial = emptyList<ToolCallRecord>())
        val running by agent.runningTaskIds.collectAsState()
        CompactChatPanel(
            task = task,
            messages = messages,
            toolCalls = toolCalls,
            generating = id in running,
            onSend = { text -> lifecycleScope.launch { sendMessage(id, text) } },
            onStop = { agent.stop(id) },
            onResume = { agent.start(id) },
            onOpenFull = {
                collapse()
                startActivity(MainActivity.openTaskIntent(this, id))
            },
            onCollapse = { collapse() },
            onSeen = { lifecycleScope.launch { tasks.markOpened(id) } },
        )
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        unregisterHomeReceiver()
        listOfNotNull(panelView, dismissView, headView).forEach { v -> runCatching { windowManager.removeView(v) } }
        panelView = null
        dismissView = null
        headView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val ACTION_START = "com.farrow.app.chathead.START"
        const val ACTION_STOP = "com.farrow.app.chathead.STOP"
        private const val TAG = "ChatHead"
        private const val FGS_NOTIFICATION_ID = 4242
        private const val HEAD_SIZE_DP = 72
        private const val EDGE_MARGIN_DP = 4
        private const val DISMISS_SIZE_DP = 76
        private const val DISMISS_BOTTOM_DP = 48
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val HEAD_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        fun startIntent(context: Context, taskId: Long): Intent =
            Intent(context, ChatHeadService::class.java).setAction(ACTION_START).putExtra(EXTRA_TASK_ID, taskId)

        fun stopIntent(context: Context): Intent =
            Intent(context, ChatHeadService::class.java).setAction(ACTION_STOP)
    }
}
