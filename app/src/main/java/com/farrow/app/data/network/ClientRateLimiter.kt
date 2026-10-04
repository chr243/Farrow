package com.farrow.app.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side sliding-window limiter: enforces the configured requests/minute per API key and
 * counts requests per UTC day. The daily counters are persisted via [AgentStateStore] (Phase 3),
 * the per-minute window stays in memory (it is only 60 s long).
 */
@Singleton
class ClientRateLimiter @Inject constructor(
    private val store: AgentStateStore,
) {
    private val mutex = Mutex()
    private val windows = HashMap<String, ArrayDeque<Long>>()
    private val daily = HashMap<String, Pair<LocalDate, Int>>()
    var clock: () -> Long = { System.currentTimeMillis() }

    init {
        val s = store.load()
        s.dailyCount.forEach { (key, count) ->
            val day = s.dailyDay[key]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (day != null) daily[key] = day to count
        }
    }

    /** Suspends until a request slot is available for [keyId] under [rpm], then records it. */
    suspend fun acquire(keyId: String, rpm: Int) {
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                val q = windows.getOrPut(keyId) { ArrayDeque() }
                while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
                if (q.size < rpm.coerceAtLeast(1)) {
                    q.addLast(now)
                    val today = utcDay(now)
                    val (day, count) = daily[keyId] ?: (today to 0)
                    daily[keyId] = today to (if (day == today) count + 1 else 1)
                    persist()
                    0L
                } else {
                    (q.first() + 60_000 - now).coerceAtLeast(50)
                }
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    fun usedToday(keyId: String): Int {
        val (day, count) = daily[keyId] ?: return 0
        return if (day == utcDay(clock())) count else 0
    }

    fun usedTodayAllKeys(): Int = daily.keys.sumOf { usedToday(it) }

    private fun persist() {
        val days = daily.mapValues { it.value.first.toString() }
        val counts = daily.mapValues { it.value.second }
        store.update { it.copy(dailyDay = days, dailyCount = counts) }
    }

    private fun utcDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    companion object {
        fun nextUtcMidnight(now: Long = System.currentTimeMillis()): Long =
            Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}

/**
 * Persisted cooldowns: per (key, model) after failures, and per key when it is exhausted.
 * Expired entries are pruned on every write.
 */
@Singleton
class CooldownTracker @Inject constructor(
    private val store: AgentStateStore,
) {
    private val models = HashMap<String, Long>()
    private val keys = HashMap<String, Long>()

    init {
        val s = store.load()
        val now = System.currentTimeMillis()
        s.modelCooldowns.filterValues { it > now }.let { models.putAll(it) }
        s.keyBlocks.filterValues { it > now }.let { keys.putAll(it) }
    }

    @Synchronized fun coolModel(keyId: String, model: String, until: Long) { models["$keyId|$model"] = until; persist() }
    @Synchronized fun modelUntil(keyId: String, model: String): Long = models["$keyId|$model"] ?: 0
    @Synchronized fun blockKey(keyId: String, until: Long) { keys[keyId] = until; persist() }
    @Synchronized fun keyUntil(keyId: String): Long = keys[keyId] ?: 0
    @Synchronized fun clear() { models.clear(); keys.clear(); persist() }

    /** Snapshot for the UI (model cooldown pool). */
    @Synchronized fun snapshot(): Pair<Map<String, Long>, Map<String, Long>> {
        val now = System.currentTimeMillis()
        return models.filterValues { it > now } to keys.filterValues { it > now }
    }

    private fun persist() {
        val now = System.currentTimeMillis()
        models.entries.removeAll { it.value <= now }
        keys.entries.removeAll { it.value <= now }
        val m = HashMap(models)
        val k = HashMap(keys)
        store.update { it.copy(modelCooldowns = m, keyBlocks = k) }
    }
}
