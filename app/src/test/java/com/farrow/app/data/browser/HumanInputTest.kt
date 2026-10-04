package com.farrow.app.data.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HumanInputTest {
    @Test fun `bezier path starts and ends at the endpoints`() {
        val p = HumanInput.bezierPath(Point(0.0, 0.0), Point(100.0, 50.0), steps = 20, random = Random(1))
        assertEquals(20, p.size)
        assertEquals(0.0, p.first().x, 1e-9); assertEquals(0.0, p.first().y, 1e-9)
        assertEquals(100.0, p.last().x, 1e-9); assertEquals(50.0, p.last().y, 1e-9)
    }

    @Test fun `typing delays are bounded and one per char`() {
        val d = HumanInput.typingDelays("Hello, world.", random = Random(2))
        assertEquals(13, d.size)
        assertTrue(d.all { it in 8..600 })
        assertTrue(d[5] > 0) // comma
    }
}
