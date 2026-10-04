package com.farrow.app.data.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AccessibilityService that performs taps/swipes (dispatchGesture), text input and reads the on-screen node tree.
 * It is a system-bound service with no Hilt injection; tools reach it through the [instance] singleton.
 */
class FarrowAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polled on demand, no event handling */ }
    override fun onInterrupt() {}

    suspend fun tap(x: Float, y: Float): Boolean = gesture(buildPath(x, y), 0, 60)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceIn(50, 10_000))

    private fun buildPath(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x, y) }

    private suspend fun gesture(path: Path, startTime: Long, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        if (!dispatched) return false
        return withTimeoutOrNull(durationMs + 5_000) { done.await() } ?: false
    }

    /** Finds the first editable (or focused) node and sets its text. */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findEditable(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    /** Snapshot of the active window's node tree (bounded depth/breadth) as a readable structure. */
    fun screenTree(maxDepth: Int = 40): A11yNode? {
        val root = rootInActiveWindow ?: return null
        return capture(root, 0, maxDepth)
    }

    private fun capture(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): A11yNode {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val children = if (depth >= maxDepth) emptyList() else
            (0 until node.childCount).mapNotNull { node.getChild(it) }.take(60).map { capture(it, depth + 1, maxDepth) }
        return A11yNode(
            cls = node.className?.toString(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.viewIdResourceName else null,
            clickable = node.isClickable,
            editable = node.isEditable,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    companion object {
        @Volatile var instance: FarrowAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }
}

/** Serializable-friendly node snapshot. */
data class A11yNode(
    val cls: String?,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: IntArray,
    val children: List<A11yNode>,
)
