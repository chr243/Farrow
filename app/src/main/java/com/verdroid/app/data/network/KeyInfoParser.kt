package com.verdroid.app.data.network

import com.verdroid.app.domain.model.QuotaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Defensive parser for GET /api/v1/key. Every field may be missing. */
object KeyInfoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, keyLabel: String, configuredDailyLimit: Int, localUsedToday: Int, now: Long = System.currentTimeMillis()): QuotaInfo? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"]?.asObjectOrNull() ?: root
        val free = data["free_model_daily_requests"]?.asObjectOrNull()
        val used = free?.get("used")?.primitiveContent()?.toDoubleOrNull()?.toInt()
        val limit = free?.get("limit")?.primitiveContent()?.toDoubleOrNull()?.toInt()
        var remaining = free?.get("remaining")?.primitiveContent()?.toDoubleOrNull()?.toInt()
        if (remaining == null && used != null && limit != null) remaining = (limit - used).coerceAtLeast(0)
        val rl = data["rate_limit"]?.asObjectOrNull()
        val estimated = remaining == null
        return QuotaInfo(
            keyLabel = keyLabel,
            used = used ?: if (estimated) localUsedToday else null,
            limit = limit ?: if (estimated) configuredDailyLimit else null,
            remaining = remaining ?: (configuredDailyLimit - localUsedToday).coerceAtLeast(0),
            usage = data["usage"]?.primitiveContent()?.toDoubleOrNull(),
            creditLimit = data["limit"]?.primitiveContent()?.toDoubleOrNull(),
            rateLimitRequests = rl?.get("requests")?.primitiveContent()?.toDoubleOrNull()?.toInt(),
            rateLimitInterval = rl?.get("interval")?.primitiveContent(),
            isFreeTier = data["is_free_tier"]?.primitiveContent()?.toBooleanStrictOrNull(),
            estimated = estimated,
            fetchedAt = now,
        )
    }
}
