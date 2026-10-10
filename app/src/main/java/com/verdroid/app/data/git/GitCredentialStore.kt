package com.verdroid.app.data.git

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Git token + author identity in EncryptedSharedPreferences (same scheme as the OpenRouter keys). */
@Singleton
class GitCredentialStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try { create() } catch (e: Exception) { context.deleteSharedPreferences(FILE); create() }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value.trim()) }.apply() }

    /** Username used with the token for HTTPS auth (GitHub accepts any non-empty user with a PAT). */
    var username: String
        get() = prefs.getString(KEY_USER, null) ?: "x-access-token"
        set(value) { prefs.edit().putString(KEY_USER, value.trim()).apply() }

    var authorName: String
        get() = prefs.getString(KEY_NAME, null) ?: "Verdroid"
        set(value) { prefs.edit().putString(KEY_NAME, value.trim()).apply() }

    var authorEmail: String
        get() = prefs.getString(KEY_EMAIL, null) ?: "verdroid@localhost"
        set(value) { prefs.edit().putString(KEY_EMAIL, value.trim()).apply() }

    val maskedToken: String?
        get() = token?.let { if (it.length <= 8) "••••" else it.take(4) + "…" + it.takeLast(4) }

    companion object {
        private const val FILE = "git_secure"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_NAME = "author_name"
        private const val KEY_EMAIL = "author_email"
    }
}
