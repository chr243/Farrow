package com.verdroid.app.data.instructions

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentsMdStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `save, reload, cap and clear`() {
        val f = File(tmp.root, "AGENTS.md")
        val s = AgentsMdStore(f)
        assertEquals("", s.promptBlock())
        s.save("  # Rules\r\n- be brief  ")
        assertEquals("# Rules\n- be brief", AgentsMdStore(f).text.value)
        assertTrue(s.promptBlock().startsWith("User instructions (AGENTS.md"))
        assertTrue(s.promptBlock().endsWith("- be brief"))
        assertEquals(AgentsMdStore.MAX_CHARS, s.save("x".repeat(AgentsMdStore.MAX_CHARS + 50)).length)
        s.save("   ")
        assertFalse(f.exists()); assertEquals("", s.promptBlock())
    }
}
