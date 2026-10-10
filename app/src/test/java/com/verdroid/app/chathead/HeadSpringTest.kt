package com.verdroid.app.chathead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadSpringTest {
    private fun run(s: HeadSpring, frames: Int, dt: Float = 1f / 60f): Int {
        repeat(frames) { i -> if (!s.step(dt)) return i + 1 }
        return frames
    }

    @Test fun settlesExactlyOnTarget() {
        val s = HeadSpring(HeadSpring.SNAP_STIFFNESS, HeadSpring.SNAP_DAMPING)
        s.snapTo(0f, 0f); s.targetX = 500f; s.targetY = -200f
        val frames = run(s, 600)
        assertTrue("should settle within 2s, took $frames frames", frames < 120)
        assertEquals(500f, s.x, 0f); assertEquals(-200f, s.y, 0f)
        assertTrue(s.isAtRest)
    }

    @Test fun dragSpringLagsButCatchesUpQuickly() {
        val s = HeadSpring(HeadSpring.DRAG_STIFFNESS, HeadSpring.DRAG_DAMPING)
        s.snapTo(0f, 0f); s.targetX = 300f
        s.step(1f / 60f)
        assertTrue("first frame should lag behind the finger", s.x in 1f..299f)
        val frames = run(s, 600)
        assertTrue("drag follow should settle fast, took $frames frames", frames < 40)
    }

    @Test fun snapOvershootIsSubtle() {
        val s = HeadSpring(HeadSpring.SNAP_STIFFNESS, HeadSpring.SNAP_DAMPING)
        s.snapTo(0f, 0f); s.targetX = 400f
        var maxX = 0f
        repeat(300) { s.step(1f / 60f); maxX = maxOf(maxX, s.x) }
        assertTrue("some overshoot expected", maxX > 400f)
        assertTrue("overshoot must stay small, was ${maxX - 400f}", maxX - 400f < 400f * 0.08f)
    }

    @Test fun hugeFrameGapStaysStable() {
        val s = HeadSpring(HeadSpring.DRAG_STIFFNESS, HeadSpring.DRAG_DAMPING)
        s.snapTo(0f, 0f); s.targetX = 1000f
        s.step(2f) // e.g. app was paused
        assertTrue(s.x.isFinite() && s.x < 1200f)
    }

    @Test fun snapToClearsMotion() {
        val s = HeadSpring(HeadSpring.SNAP_STIFFNESS, HeadSpring.SNAP_DAMPING)
        s.vx = 1000f; s.targetX = 50f
        s.snapTo(10f, 20f)
        assertFalse(s.step(1f / 60f))
        assertEquals(10f, s.x, 0f); assertEquals(20f, s.y, 0f)
    }
}
