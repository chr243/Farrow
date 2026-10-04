package com.farrow.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAlivePolicyTest {
    @Test fun `service only while a task runs`() {
        assertEquals(KeepAlivePolicy.Action.START, KeepAlivePolicy.decide(false, true, true))
        assertEquals(KeepAlivePolicy.Action.NONE, KeepAlivePolicy.decide(false, true, false))
        assertEquals(KeepAlivePolicy.Action.NONE, KeepAlivePolicy.decide(true, true, true))
        assertEquals(KeepAlivePolicy.Action.STOP, KeepAlivePolicy.decide(true, false, true))
        assertEquals(KeepAlivePolicy.Action.NONE, KeepAlivePolicy.decide(false, false, true))
    }

    @Test fun `working notification texts`() {
        assertEquals("Working on: Post on X", WorkingText.title("Post on X"))
        assertEquals("Running x_post…", WorkingText.text("Running x_post…", 0))
        assertEquals("Thinking... (+2 more tasks)", WorkingText.text("Thinking...", 2))
        assertEquals("Working…", WorkingText.text("", 0))
    }
}
