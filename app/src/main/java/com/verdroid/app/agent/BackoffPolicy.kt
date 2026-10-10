package com.verdroid.app.agent

import kotlin.math.min
import kotlin.random.Random

/** Pure, unit-testable resume-time rules for Phase 3 rate-limit recovery. */
object BackoffPolicy {
    const val BASE_MS = 5_000L
    const val CAP_MS = 5 * 60_000L
    const val MAX_TRANSIENT_ATTEMPTS = 8

    /**
     * Exponential backoff with jitter ("equal jitter": half fixed, half random), capped at 5 min.
     * attempt 0 -> 2.5–5 s, 1 -> 5–10 s, 2 -> 10–20 s … capped at 5 min.
     */
    fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
        val exp = BASE_MS shl attempt.coerceIn(0, 16)
        val capped = min(exp, CAP_MS)
        val half = capped / 2
        return half + random.nextLong(half + 1)
    }

    /**
     * When to resume:
     *  - daily quota exhausted -> [dailyResetAt] (00:00 UTC)
     *  - an X-RateLimit-Reset was given -> honour it even if longer than the cap
     *  - otherwise backoff(attempt), but not before [earliestAvailable] (cooldown pool), capped at 5 min
     */
    fun resumeAt(
        now: Long,
        attempt: Int,
        dailyQuota: Boolean,
        dailyResetAt: Long,
        explicitResetAt: Long?,
        earliestAvailable: Long?,
        random: Random = Random.Default,
    ): Long = when {
        dailyQuota -> dailyResetAt
        explicitResetAt != null && explicitResetAt > now -> explicitResetAt
        else -> {
            val backoff = now + backoffMs(attempt, random)
            val floor = earliestAvailable?.coerceAtMost(now + CAP_MS) ?: 0L
            maxOf(backoff, floor).coerceAtMost(now + CAP_MS)
        }
    }
}
