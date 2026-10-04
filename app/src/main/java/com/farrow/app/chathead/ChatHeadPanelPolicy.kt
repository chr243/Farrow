package com.farrow.app.chathead

import android.view.KeyEvent

/** Pure rules for the expanded chat-head panel (unit-tested; the service only wires them to Android). */
object ChatHeadPanelPolicy {
    /** Reasons ACTION_CLOSE_SYSTEM_DIALOGS carries for Home / Recents (overlays never get KEYCODE_HOME). */
    val HOME_REASONS = setOf("homekey", "recentapps", "gestureNav", "fs_gesture")

    enum class KeyDecision { IGNORE, CONSUME, COLLAPSE }

    /**
     * Back collapses on ACTION_UP (not cancelled); the DOWN is consumed so nothing else reacts to it. While the
     * keyboard is open the IME consumes the first Back itself (closes the keyboard), so this only sees the second.
     */
    fun onKey(action: Int, keyCode: Int, canceled: Boolean): KeyDecision = when {
        keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE -> KeyDecision.IGNORE
        action == KeyEvent.ACTION_UP && !canceled -> KeyDecision.COLLAPSE
        else -> KeyDecision.CONSUME
    }

    fun collapseOnSystemDialogs(reason: String?): Boolean = reason != null && reason in HOME_REASONS
}

/** Where the head goes back to when the panel collapses: the position saved on expand, clamped to the screen. */
class HeadPositionStore(private val read: (String) -> Int?, private val write: (String, Int) -> Unit) {
    data class Bounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    fun save(x: Int, y: Int) { write(KEY_X, x); write(KEY_Y, y) }

    fun restore(fallbackX: Int, fallbackY: Int, b: Bounds): Pair<Int, Int> =
        (read(KEY_X) ?: fallbackX).coerceIn(b.minX, maxOf(b.minX, b.maxX)) to (read(KEY_Y) ?: fallbackY).coerceIn(b.minY, maxOf(b.minY, b.maxY))

    companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
