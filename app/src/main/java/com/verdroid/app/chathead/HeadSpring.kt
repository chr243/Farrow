package com.verdroid.app.chathead

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tiny 2D damped spring for the chat head window (Messenger-style "follow the finger" and edge snap).
 *
 * Pure Kotlin, no Android types, so it can be unit-tested. The service steps it once per display frame
 * (Choreographer) and writes the rounded position to the overlay's LayoutParams.
 *
 * @param stiffness spring constant k (unit mass); higher = snappier.
 * @param dampingRatio 1 = critically damped (no overshoot), < 1 = slight bounce.
 */
class HeadSpring(stiffness: Float, dampingRatio: Float) {
    var stiffness: Float = stiffness
        private set
    var dampingRatio: Float = dampingRatio
        private set

    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var targetX = 0f; var targetY = 0f

    fun configure(stiffness: Float, dampingRatio: Float) {
        this.stiffness = stiffness
        this.dampingRatio = dampingRatio
    }

    /** Jump to [px],[py] with no velocity and no pending motion. */
    fun snapTo(px: Float, py: Float) {
        x = px; y = py; targetX = px; targetY = py; vx = 0f; vy = 0f
    }

    val isAtRest: Boolean
        get() = abs(targetX - x) < REST_DISTANCE && abs(targetY - y) < REST_DISTANCE &&
            abs(vx) < REST_VELOCITY && abs(vy) < REST_VELOCITY

    /**
     * Advance by [dtSeconds] (clamped, sub-stepped for stability on dropped frames).
     * @return true while still moving; false once settled (position is then exactly the target).
     */
    fun step(dtSeconds: Float): Boolean {
        var remaining = dtSeconds.coerceIn(0f, MAX_FRAME_DT)
        val c = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = minOf(remaining, MAX_SUBSTEP)
            // Semi-implicit Euler: update velocity first, then position.
            vx += (stiffness * (targetX - x) - c * vx) * h
            vy += (stiffness * (targetY - y) - c * vy) * h
            x += vx * h
            y += vy * h
            remaining -= h
        }
        if (isAtRest) {
            x = targetX; y = targetY; vx = 0f; vy = 0f
            return false
        }
        return true
    }

    companion object {
        private const val REST_DISTANCE = 0.5f   // px
        private const val REST_VELOCITY = 15f    // px/s
        private const val MAX_FRAME_DT = 1f / 20f
        private const val MAX_SUBSTEP = 1f / 240f

        /** While dragging: stiff and nearly critically damped → a short, soft lag behind the finger. */
        const val DRAG_STIFFNESS = 1400f
        const val DRAG_DAMPING = 0.9f

        /** Release / edge snap: softer with a little overshoot, like Messenger's bubble settling on the edge. */
        const val SNAP_STIFFNESS = 380f
        const val SNAP_DAMPING = 0.68f

        /** Pulled into the ✕ target. */
        const val MAGNET_STIFFNESS = 900f
        const val MAGNET_DAMPING = 0.75f

        /** Seconds of fling velocity added to the release point when choosing the edge / resting y. */
        const val FLING_PROJECTION_S = 0.12f
        const val MAX_FLING_PX_PER_S = 9000f
    }
}
