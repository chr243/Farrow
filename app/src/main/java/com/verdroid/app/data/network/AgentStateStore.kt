package com.verdroid.app.data.network

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersistedLimiterState(
    /** "keyId|model" -> cooldown-until epoch ms */
    val modelCooldowns: Map<String, Long> = emptyMap(),
    /** keyId -> blocked-until epoch ms */
    val keyBlocks: Map<String, Long> = emptyMap(),
    /** keyId -> "yyyy-MM-dd" UTC day */
    val dailyDay: Map<String, String> = emptyMap(),
    /** keyId -> request count for [dailyDay] */
    val dailyCount: Map<String, Int> = emptyMap(),
)

/**
 * Synchronous (SharedPreferences) persistence for the model cooldown pool, key rotation blocks and
 * per-key daily request counters, so they survive process death (Phase 3).
 */
@Singleton
class AgentStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    fun load(): PersistedLimiterState = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString<PersistedLimiterState>(it) }
    }.getOrNull() ?: PersistedLimiterState()

    fun save(state: PersistedLimiterState) {
        prefs.edit().putString(KEY, json.encodeToString(state)).apply()
    }

    /** Atomic read-modify-write (the limiter and the cooldown tracker share one record). */
    @Synchronized
    fun update(transform: (PersistedLimiterState) -> PersistedLimiterState) {
        save(transform(load()))
    }

    private companion object {
        const val FILE = "verdroid_limiter_state"
        const val KEY = "state_v1"
    }
}
