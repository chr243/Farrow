package com.farrow.app.data.browser

import kotlin.math.pow
import kotlin.random.Random

/** Pure helpers for human-like input, sent as parameters to the Termux bridge. */
data class Point(val x: Double, val y: Double)

object HumanInput {
    /**
     * Cubic Bézier mouse path from [from] to [to] with two randomised control points, sampled into
     * [steps] points. Keeps the curve near the straight line but with a natural arc + overshoot.
     */
    fun bezierPath(from: Point, to: Point, steps: Int = 24, random: Random = Random.Default): List<Point> {
        val n = steps.coerceAtLeast(2)
        val dx = to.x - from.x
        val dy = to.y - from.y
        fun ctrl(t: Double): Point {
            val jitterX = (random.nextDouble() - 0.5) * (dy) * 0.3
            val jitterY = (random.nextDouble() - 0.5) * (dx) * 0.3
            return Point(from.x + dx * t + jitterX, from.y + dy * t + jitterY)
        }
        val c1 = ctrl(0.3)
        val c2 = ctrl(0.7)
        return (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            val u = 1 - t
            val x = u.pow(3) * from.x + 3 * u.pow(2) * t * c1.x + 3 * u * t * t * c2.x + t.pow(3) * to.x
            val y = u.pow(3) * from.y + 3 * u.pow(2) * t * c1.y + 3 * u * t * t * c2.y + t.pow(3) * to.y
            Point(x, y)
        }
    }

    /** Per-character typing delays (ms): a base speed with jitter and longer pauses after spaces/punctuation. */
    fun typingDelays(text: String, wpm: Int = 220, random: Random = Random.Default): List<Int> {
        val base = (60_000.0 / (wpm * 5)).coerceAtLeast(12.0)
        return text.map { ch ->
            val jitter = base * (0.5 + random.nextDouble())
            val extra = when {
                ch == ' ' -> base * 1.5
                ch in ".,;:!?\n" -> base * 3
                else -> 0.0
            }
            (jitter + extra).toInt().coerceIn(8, 600)
        }
    }
}
