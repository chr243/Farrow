package com.farrow.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.farrow.app.domain.model.ApiKey
import com.farrow.app.domain.repository.ApiKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredKey(val id: String, val label: String, val key: String, val primary: Boolean)

/**
 * OpenRouter API keys stored in EncryptedSharedPreferences (AES256-GCM values, master key in the
 * Android Keystore). Keys never leave the device except in the Authorization header.
 */
@Singleton
class SecureApiKeyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApiKeyRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences by lazy {
        try {
            create()
        } catch (e: Exception) {
            // Keyset can become unreadable (e.g. Keystore reset); start fresh rather than crash.
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _keys = MutableStateFlow(load())
    override val keys: StateFlow<List<ApiKey>> = _keys.asStateFlow()

    private fun load(): List<ApiKey> = runCatching {
        val raw = prefs.getString(PREF, null) ?: return emptyList()
        json.decodeFromString<List<StoredKey>>(raw).map { ApiKey(it.id, it.label, it.key, it.primary) }
    }.getOrDefault(emptyList())

    private suspend fun save(list: List<ApiKey>) = withContext(Dispatchers.IO) {
        val normalised = when {
            list.isEmpty() -> list
            list.none { it.isPrimary } -> list.mapIndexed { i, k -> k.copy(isPrimary = i == 0) }
            else -> list
        }
        prefs.edit().putString(PREF, json.encodeToString(normalised.map { StoredKey(it.id, it.label, it.key, it.isPrimary) })).apply()
        _keys.value = normalised
    }

    override suspend fun add(label: String, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        save(_keys.value + ApiKey(UUID.randomUUID().toString(), label.trim(), trimmed, isPrimary = _keys.value.isEmpty()))
    }

    override suspend fun remove(id: String) = save(_keys.value.filterNot { it.id == id })

    override suspend fun move(id: String, delta: Int) {
        val list = _keys.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    override suspend fun setPrimary(id: String) = save(_keys.value.map { it.copy(isPrimary = it.id == id) })

    override suspend fun rename(id: String, label: String) = save(_keys.value.map { if (it.id == id) it.copy(label = label.trim()) else it })

    override fun orderedForUse(): List<ApiKey> = _keys.value.sortedByDescending { it.isPrimary }

    private companion object {
        const val PREF = "api_keys_v1"
        const val FILE = "farrow_secure_keys"
    }
}
