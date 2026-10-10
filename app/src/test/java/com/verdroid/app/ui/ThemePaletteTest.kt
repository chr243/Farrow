package com.verdroid.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.verdroid.app.ui.theme.Palette
import com.verdroid.app.ui.theme.Palettes
import com.materialkolor.hct.Hct
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class ThemePaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance() + 0.05; val l2 = b.luminance() + 0.05
        return maxOf(l1, l2).toDouble() / minOf(l1, l2)
    }

    private fun hueDist(a: Double, b: Double) = min(abs(a - b), 360 - abs(a - b))

    @Test fun `every palette has readable light and dark schemes`() {
        for (p in Palette.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(p, dark)   // no context: Dynamic falls back to its seed
            val tag = "$p dark=$dark"
            assertEquals("$tag background", dark, s.background.luminance() < 0.5f)
            assertTrue("$tag onPrimary", contrast(s.primary, s.onPrimary) >= 4.5)
            assertTrue("$tag text", contrast(s.background, s.onBackground) >= 7.0)
            assertTrue("$tag onSurfaceVariant", contrast(s.surface, s.onSurfaceVariant) >= 4.5)
            assertTrue("$tag onPrimaryContainer", contrast(s.primaryContainer, s.onPrimaryContainer) >= 4.5)
            assertTrue("$tag onErrorContainer", contrast(s.errorContainer, s.onErrorContainer) >= 4.5)
            assertTrue("$tag onTertiaryContainer", contrast(s.tertiaryContainer, s.onTertiaryContainer) >= 4.5)
            val b = Palettes.bubbles(s)
            assertEquals(s.primary, b.user); assertEquals(s.surfaceContainerHigh, b.agent)
            assertTrue("$tag user bubble", contrast(b.user, b.onUser) >= 4.5)
            assertTrue("$tag agent bubble", contrast(b.agent, b.onAgent) >= 4.5)
            val st = Palettes.status(s, dark)
            for (c in listOf(st.ok, st.error)) assertTrue("$tag status on surface", contrast(c, s.surface) >= 3.0)
        }
    }

    @Test fun `palettes come from their seed and surfaces form a ladder`() {
        for (p in Palette.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(p, dark)
            val seedHue = Hct.fromInt(p.seed.toInt()).hue
            assertTrue("$p dark=$dark primary hue", hueDist(Hct.fromInt(s.primary.toArgb()).hue, seedHue) < 25)
            // Neutral surfaces stay close to the seed hue too (tinted greys), except Midnight's pure black.
            val ladder = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
                .map { it.luminance() }
            if (dark) assertEquals("$p ladder", ladder.sorted(), ladder) else assertEquals("$p ladder", ladder.sortedDescending(), ladder)
        }
    }

    @Test fun `seeds and midnight`() {
        assertEquals(0xFF0084FF, Palette.MESSENGER.seed)
        assertEquals(listOf(0xFF3D5A3A, 0xFFF4511E, 0xFF7E57C2, 0xFFD81B60, 0xFF00897B),
            listOf(Palette.FOREST, Palette.SUNSET, Palette.PURPLE, Palette.ROSE, Palette.OCEAN).map { it.seed })
        val m = Palettes.scheme(Palette.MIDNIGHT, true)
        assertEquals(Color.Black, m.background); assertEquals(Color.Black, m.surface)
        assertNotEquals(Color.Black, m.surfaceContainerHigh)   // cards and received bubbles stay visible
        assertEquals(Palette.MESSENGER, Palette.entries.first())
        // Light and dark of a palette share the primary hue.
        val l = Hct.fromInt(Palettes.scheme(Palette.ROSE, false).primary.toArgb()).hue
        val d = Hct.fromInt(Palettes.scheme(Palette.ROSE, true).primary.toArgb()).hue
        assertTrue(hueDist(l, d) < 15)
    }
}
