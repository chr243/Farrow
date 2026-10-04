package com.farrow.app.ui.chats

import com.farrow.app.data.network.ModelIds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Quota dot in the chat-list top bar (v0.9.20), replacing the big "Free tier" banner. OpenRouter ONLY: shown only when
 * the ACTIVE model (the one the next request uses) is a quota-limited OpenRouter free model. Hidden for Kilo or paid models.
 */
enum class QuotaLevel { GREEN, YELLOW, RED }

data class QuotaDot(val level: QuotaLevel, val activeModel: String, val line: String)

object QuotaIndicator {
    /** OpenRouter models with a free daily quota: `…:free` and the `openrouter/free` router. */
    fun isOpenRouterFree(id: String): Boolean = !ModelIds.isKilo(id) && id.trim().let { it.endsWith(":free") || it == "openrouter/free" }

    /** Green > 50 % left, yellow 15–50 %, red < 15 % or rate-limited right now. */
    fun level(remaining: Int, limit: Int, rateLimited: Boolean): QuotaLevel {
        if (rateLimited || limit <= 0) return QuotaLevel.RED
        val f = remaining.coerceAtLeast(0).toDouble() / limit
        return when { f < 0.15 -> QuotaLevel.RED; f <= 0.5 -> QuotaLevel.YELLOW; else -> QuotaLevel.GREEN }
    }

    /** Local time of the next 00:00 UTC, e.g. "02:00" in Paris summer time. */
    fun utcMidnightLocal(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val next = (now / 86_400_000L + 1) * 86_400_000L
        return DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(next))
    }

    /**
     * The active model: the first model in the priority list that is available now (not cooling down / over budget);
     * if none is available, the model of the latest request; else the first in the list.
     */
    fun activeModel(models: List<String>, available: (String) -> Boolean, lastUsed: String?): String? =
        models.firstOrNull(available) ?: lastUsed ?: models.firstOrNull()

    fun compute(
        activeModel: String?, hasOpenRouterKey: Boolean,
        orRemaining: Int?, orLimit: Int, orEstimated: Boolean, orRateLimited: Boolean,
        now: Long, zone: ZoneId = ZoneId.systemDefault(),
    ): QuotaDot? {
        if (activeModel == null || !hasOpenRouterKey || !isOpenRouterFree(activeModel)) return null
        val remaining = (orRemaining ?: orLimit).coerceAtLeast(0)
        val line = "OpenRouter free: $remaining/$orLimit left today, resets ${utcMidnightLocal(now, zone)}" +
            (if (orEstimated || orRemaining == null) " (estimated)" else "") + (if (orRateLimited) " · rate-limited now" else "")
        return QuotaDot(level(remaining, orLimit, orRateLimited), activeModel, line)
    }
}
