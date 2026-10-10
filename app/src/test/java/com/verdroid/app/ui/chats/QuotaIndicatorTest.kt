package com.verdroid.app.ui.chats

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class QuotaIndicatorTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-03T18:00:00Z").toEpochMilli()
    private val list = listOf("kilo:kilo-auto/free", "openrouter/free", "qwen/qwen3-coder:free")

    private fun dot(active: String?, key: Boolean = true, rem: Int? = 880, limit: Int = 1000, rl: Boolean = false) =
        QuotaIndicator.compute(active, key, rem, limit, false, rl, now, paris)

    @Test fun `thresholds`() {
        assertEquals(QuotaLevel.GREEN, QuotaIndicator.level(501, 1000, false))
        assertEquals(QuotaLevel.YELLOW, QuotaIndicator.level(500, 1000, false))
        assertEquals(QuotaLevel.YELLOW, QuotaIndicator.level(150, 1000, false))
        assertEquals(QuotaLevel.RED, QuotaIndicator.level(149, 1000, false))
        assertEquals(QuotaLevel.RED, QuotaIndicator.level(900, 1000, true))
    }

    @Test fun `tooltip shows OpenRouter quota with local reset time`() {
        val d = dot("openrouter/free")!!
        assertEquals(QuotaLevel.GREEN, d.level)
        assertEquals("OpenRouter free: 88/1000 left today, resets 02:00".replace("88/", "880/"), d.line)
        assertEquals(QuotaLevel.RED, dot("openrouter/free", rem = 88)!!.level)
        assertEquals(QuotaLevel.RED, dot("openrouter/free", rl = true)!!.level)
    }

    @Test fun `hidden when Kilo or a paid model is active or there is no key`() {
        assertNull(dot("kilo:kilo-auto/free"))
        assertNull(dot("openai/gpt-5"))
        assertNull(dot("openrouter/free", key = false))
        assertNull(dot(null))
    }

    @Test fun `active model follows fallback live`() {
        // Kilo available -> Kilo active -> dot hidden
        assertEquals("kilo:kilo-auto/free", QuotaIndicator.activeModel(list, { true }, null))
        // Kilo cooling down -> next OpenRouter model is active -> dot shown
        val active = QuotaIndicator.activeModel(list, { it != "kilo:kilo-auto/free" }, "kilo:kilo-auto/free")
        assertEquals("openrouter/free", active)
        assertNotNull(dot(active))
        // nothing available -> the model of the latest request
        assertEquals("qwen/qwen3-coder:free", QuotaIndicator.activeModel(list, { false }, "qwen/qwen3-coder:free"))
    }

    @Test fun `free model detection`() {
        assertTrue(QuotaIndicator.isOpenRouterFree("openrouter/free"))
        assertTrue(QuotaIndicator.isOpenRouterFree("google/gemma-3-27b-it:free"))
        assertFalse(QuotaIndicator.isOpenRouterFree("openai/gpt-5"))
        assertFalse(QuotaIndicator.isOpenRouterFree("kilo:kilo-auto/free"))
    }
}
