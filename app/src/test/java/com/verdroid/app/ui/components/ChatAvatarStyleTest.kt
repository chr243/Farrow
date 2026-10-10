package com.verdroid.app.ui.components

import com.verdroid.app.domain.model.TaskType
import org.junit.Assert.*
import org.junit.Test

class ChatAvatarStyleTest {
    @Test fun `color is stable per id and spreads over the shades`() {
        val n = ChatAvatarStyle.SHADE_COUNT
        assertEquals(8, n)
        (1L..50L).forEach { assertEquals(ChatAvatarStyle.colorIndex(it), ChatAvatarStyle.colorIndex(it)) }
        val used = (1L..40L).map { ChatAvatarStyle.colorIndex(it) }.toSet()
        assertTrue("used $used", used.size >= n - 1)
        assertTrue((1L..40L).zipWithNext().count { (a, b) -> ChatAvatarStyle.colorIndex(a) == ChatAvatarStyle.colorIndex(b) } <= 8)
        assertTrue(ChatAvatarStyle.colorIndex(-5) in 0 until n && ChatAvatarStyle.colorIndex(Long.MAX_VALUE) in 0 until n)
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun contrast(a: Int, b: Int) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
    private fun hue(c: Int) = com.materialkolor.hct.Hct.fromInt(c).hue

    @Test fun `shades are only theme green, wide light-dark range, readable letters`() {
        val primary = 0xFF2E7D32.toInt()   // green
        val tertiary = 0xFF00796B.toInt()  // teal-green, within 30 degrees? either way must stay near primary
        for (dark in listOf(false, true)) {
            val sh = ChatAvatarStyle.shades(primary, tertiary, dark)
            assertEquals(ChatAvatarStyle.SHADE_COUNT, sh.size)
            sh.forEach { s ->
                val d = Math.abs(((hue(s.bg) - hue(primary)) % 360 + 540) % 360 - 180)
                assertTrue("hue drift $d dark=$dark", d <= 32)
                assertTrue("letters dark=$dark ${contrast(s.bg, s.fg)}", contrast(s.bg, s.fg) >= 4.5)
            }
            val lums = sh.map { lum(it.bg) }
            assertTrue("range dark=$dark", (lums.max() + 0.05) / (lums.min() + 0.05) >= 6.0)
        }
        // a far-off tertiary (e.g. purple) is ignored: everything stays in the primary's hue family
        ChatAvatarStyle.shades(primary, 0xFF7B1FA2.toInt(), false).forEach {
            assertTrue(Math.abs(((hue(it.bg) - hue(primary)) % 360 + 540) % 360 - 180) <= 15)
        }
    }

    @Test fun `glyph by topic, then type, then first letter or emoji`() {
        val g = ChatAvatarStyle::glyph
        assertEquals("✍️", g("Post on X", "write a tweet about Verdroid", TaskType.SOCIAL))
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
