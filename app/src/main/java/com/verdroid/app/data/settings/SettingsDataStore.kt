package com.verdroid.app.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.verdroid.app.domain.model.ChatHeadMode
import com.verdroid.app.domain.model.DefaultModels
import com.verdroid.app.domain.model.LimitSettings
import com.verdroid.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "verdroid_settings",
    produceMigrations = { listOf(ModelDefaultsMigration) },
)

/**
 * One-time migration (runs before the first read): if the saved model list equals the old shipped defaults, i.e. the
 * user never customised it, drop it so the new [DefaultModels.list] applies. A customised list is left untouched.
 */
object ModelDefaultsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsKeys.MODEL_DEFAULTS_VERSION] ?: 1) < DefaultModels.VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val from = prefs[SettingsKeys.MODEL_DEFAULTS_VERSION] ?: 1
        if (DefaultModels.isUncustomisedLegacy(prefs[SettingsKeys.MODELS])) prefs.remove(SettingsKeys.MODELS)
        // v3 (app 0.9.19): put Kilo Auto Free on top of a saved (customised) list, once.
        if (from < 3) DefaultModels.parse(prefs[SettingsKeys.MODELS])?.takeIf { it.isNotEmpty() }?.let {
            prefs[SettingsKeys.MODELS] = com.verdroid.app.data.network.ModelIds.withKiloFirst(it).joinToString("\n")
        }
        prefs[SettingsKeys.MODEL_DEFAULTS_VERSION] = DefaultModels.VERSION
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

object SettingsKeys {
    val MODELS = stringPreferencesKey("model_priority")
    val MODEL_DEFAULTS_VERSION = intPreferencesKey("model_defaults_version")
    val RPM = intPreferencesKey("requests_per_minute")
    val RPD = intPreferencesKey("requests_per_day")
    val COOLDOWN = intPreferencesKey("model_cooldown_seconds")
    val LOW_QUOTA = intPreferencesKey("low_quota_threshold")
    val CONTEXT_BUDGET = intPreferencesKey("context_budget_tokens")
    val MAX_STEPS = intPreferencesKey("max_steps")
    val QUOTA_JSON = stringPreferencesKey("last_quota_json")
    val LAST_LOW_QUOTA_WARN_DAY = stringPreferencesKey("last_low_quota_warn_day")
    val CHAT_HEAD_MODE = stringPreferencesKey("chat_head_mode")
}

@Singleton
class SettingsDataStoreRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val modelPriority: Flow<List<String>> = store.data.map { p ->
        DefaultModels.parse(p[SettingsKeys.MODELS])?.ifEmpty { null } ?: DefaultModels.list
    }

    override val limits: Flow<LimitSettings> = store.data.map { p ->
        val d = LimitSettings()
        LimitSettings(
            requestsPerMinute = p[SettingsKeys.RPM] ?: d.requestsPerMinute,
            requestsPerDay = p[SettingsKeys.RPD] ?: d.requestsPerDay,
            modelCooldownSeconds = p[SettingsKeys.COOLDOWN] ?: d.modelCooldownSeconds,
            lowQuotaThreshold = p[SettingsKeys.LOW_QUOTA] ?: d.lowQuotaThreshold,
            contextBudgetTokens = p[SettingsKeys.CONTEXT_BUDGET] ?: d.contextBudgetTokens,
            maxSteps = p[SettingsKeys.MAX_STEPS] ?: d.maxSteps,
        )
    }

    override val chatHeadMode: Flow<ChatHeadMode> = store.data.map { p ->
        p[SettingsKeys.CHAT_HEAD_MODE]?.let { name -> ChatHeadMode.entries.firstOrNull { it.name == name } } ?: ChatHeadMode.AUTO
    }

    override suspend fun setChatHeadMode(mode: ChatHeadMode) {
        store.edit { it[SettingsKeys.CHAT_HEAD_MODE] = mode.name }
    }

    override suspend fun currentModels(): List<String> = modelPriority.first()
    override suspend fun currentLimits(): LimitSettings = limits.first()

    override suspend fun setModelPriority(models: List<String>) {
        store.edit { it[SettingsKeys.MODELS] = models.map { m -> m.trim() }.filter { m -> m.isNotEmpty() }.distinct().joinToString("\n") }
    }

    override suspend fun resetModelPriority() { store.edit { it.remove(SettingsKeys.MODELS) } }

    override suspend fun updateLimits(transform: (LimitSettings) -> LimitSettings) {
        val n = transform(currentLimits())
        store.edit {
            it[SettingsKeys.RPM] = n.requestsPerMinute.coerceIn(1, 10_000)
            it[SettingsKeys.RPD] = n.requestsPerDay.coerceIn(1, 1_000_000)
            it[SettingsKeys.COOLDOWN] = n.modelCooldownSeconds.coerceIn(1, 86_400)
            it[SettingsKeys.LOW_QUOTA] = n.lowQuotaThreshold.coerceIn(0, 1_000_000)
            it[SettingsKeys.CONTEXT_BUDGET] = n.contextBudgetTokens.coerceIn(2_000, 1_000_000)
            it[SettingsKeys.MAX_STEPS] = n.maxSteps.coerceIn(1, 200)
        }
    }
}
