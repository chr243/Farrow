package com.verdroid.app.chathead

import android.view.KeyEvent
import com.verdroid.app.chathead.ChatHeadPanelPolicy.KeyDecision
import org.junit.Assert.*
import org.junit.Test

class ChatHeadPanelPolicyTest {
    @Test fun `back collapses on key up, consumes key down, ignores other keys and cancelled backs`() {
        assertEquals(KeyDecision.CONSUME, ChatHeadPanelPolicy.onKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, false))
        assertEquals(KeyDecision.COLLAPSE, ChatHeadPanelPolicy.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, false))
        assertEquals(KeyDecision.CONSUME, ChatHeadPanelPolicy.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, true))
        assertEquals(KeyDecision.IGNORE, ChatHeadPanelPolicy.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, false))
        assertEquals(KeyDecision.IGNORE, ChatHeadPanelPolicy.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, false))
    }

    @Test fun `home and recents collapse, other system dialog closes do not`() {
        assertTrue(ChatHeadPanelPolicy.collapseOnSystemDialogs("homekey"))
        assertTrue(ChatHeadPanelPolicy.collapseOnSystemDialogs("recentapps"))
        assertFalse(ChatHeadPanelPolicy.collapseOnSystemDialogs("globalactions"))
        assertFalse(ChatHeadPanelPolicy.collapseOnSystemDialogs(null))
    }

    @Test fun `collapsing restores the head at its saved position`() {
        val prefs = HashMap<String, Int>()
        val store = HeadPositionStore({ prefs[it] }, { k, v -> prefs[k] = v })
        val screen = HeadPositionStore.Bounds(minX = 0, maxX = 1008, minY = 120, maxY = 2200)
        // Nothing saved yet: the current window position is kept.
        assertEquals(500 to 600, store.restore(500, 600, screen))
        // Expand saves where the head was; collapse (Back / Home / outside tap) brings it back exactly there,
        // even though the head params were moved meanwhile.
        store.save(1008, 1650)
        assertEquals(1008 to 1650, store.restore(0, 0, screen))
        // After a rotation / smaller screen the saved spot is clamped onto the screen instead of off-screen.
        assertEquals(700 to 1400, store.restore(0, 0, HeadPositionStore.Bounds(0, 700, 100, 1400)))
        store.save(-50, 10)
        assertEquals(0 to 120, store.restore(0, 0, screen))
    }
}
