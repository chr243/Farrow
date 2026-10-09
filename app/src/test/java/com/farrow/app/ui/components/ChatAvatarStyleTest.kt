package com.farrow.app.ui.components

import com.farrow.app.domain.model.TaskType
import org.junit.Assert.*
import org.junit.Test

class ChatAvatarStyleTest {
    @Test fun `color is stable per id and spreads over the palette`() {
        val n = ChatAvatarStyle.PALETTE.size
        assertTrue(n in 8..12)
        (1L..50L).forEach { assertEquals(ChatAvatarStyle.colorIndex(it), ChatAvatarStyle.colorIndex(it)) }
        // fixed values: must never change between versions/launches
        assertEquals(listOf(4, 1, 4, 10, 1, 7, 2, 9, 8, 0, 9, 6), (1L..12L).map { ChatAvatarStyle.colorIndex(it) })
        val used = (1L..40L).map { ChatAvatarStyle.colorIndex(it) }.toSet()
        assertTrue("used $used", used.size >= n - 2)
        assertTrue((1L..40L).zipWithNext().count { (a, b) -> ChatAvatarStyle.colorIndex(a) == ChatAvatarStyle.colorIndex(b) } <= 6)
        assertTrue(ChatAvatarStyle.colorIndex(-5) in 0 until n && ChatAvatarStyle.colorIndex(Long.MAX_VALUE) in 0 until n)
    }

    @Test fun `palette contrast is readable in light and dark theme`() {
        fun lum(c: Long): Double {
            fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        fun contrast(a: Long, b: Long) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
        ChatAvatarStyle.PALETTE.forEach { t ->
            assertTrue("light $t", contrast(t.light, t.onLight) >= 4.5)
            assertTrue("dark $t", contrast(t.dark, t.onDark) >= 4.5)
            // v1.0.23: circles stand out from the theme surface (light #FFFBFE / dark #141218).
            assertTrue("light circle vs surface $t", contrast(t.light, 0xFFFFFBFE) >= 2.0)
            assertTrue("dark circle vs surface $t", contrast(t.dark, 0xFF141218) >= 2.6)
        }
    }

    @Test fun `glyph by topic, then type, then first letter or emoji`() {
        val g = ChatAvatarStyle::glyph
        assertEquals("✍️", g("Post on X", "write a tweet about Farrow", TaskType.SOCIAL))
        assertEquals("✈️", g("Weekend in Lisbon", "plan a trip with flights and a hotel", TaskType.CHAT))
        assertEquals("✈️", g("Vacances", "", TaskType.CHAT))
        assertEquals("🔎", g("Latest Pixel", "search the web for the latest Pixel phone", TaskType.WEB))
        assertEquals("⚙️", g("Disk", "list the files in the workspace", TaskType.SYSTEM))
        assertEquals("🛒", g("Cheapest headphones", "", TaskType.CHAT))
        assertEquals("📊", g("Compare GPUs", "", TaskType.CHAT))
        assertEquals("🔎", g("Something", "", TaskType.WEB))           // type fallback
        assertEquals("H", g("hello there", "how are you", TaskType.CHAT)) // first letter
        assertEquals("🎉", g("🎉 party ideas", "", TaskType.CHAT))      // title emoji
        assertEquals("💬", g("", "", TaskType.CHAT))
        assertEquals("A", g("«ami»", "", TaskType.CHAT))
        assertFalse("'status' is not a stats topic", g("status", "", TaskType.CHAT) == "📊")
        assertFalse("no false X match", g("Box ideas", "", TaskType.CHAT) == "✍️")
        assertTrue(ChatAvatarStyle.isLetter("H")); assertFalse(ChatAvatarStyle.isLetter("✈️"))
    }
}
