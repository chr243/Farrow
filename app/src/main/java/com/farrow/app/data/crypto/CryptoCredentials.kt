package com.farrow.app.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Read view of crypto API credentials (production store or a test fake). */
interface CryptoCredentialSource {
    val apiKey: String?
    val apiSecret: String?
    val passphrase: String?
    val configured: Boolean
    val maskedKey: String?
}

/**
 * Coinbase Exchange API credentials (key + secret + passphrase) in EncryptedSharedPreferences.
 * Revolut has no public crypto trading API (Business API is fiat accounts/FX only); market data and
 * optional live trading use Coinbase Exchange instead. Never log or put these in the repo.
 */
@Singleton
class CryptoCredentials @Inject constructor(@ApplicationContext private val context: Context) : CryptoCredentialSource {

    private val prefs: SharedPreferences by lazy {
        try { create() } catch (_: Exception) { context.deleteSharedPreferences(FILE); create() }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value.trim()) }.apply() }

    override var apiSecret: String?
        get() = prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY_SECRET) else putString(KEY_SECRET, value.trim()) }.apply() }

    override var passphrase: String?
        get() = prefs.getString(KEY_PASS, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY_PASS) else putString(KEY_PASS, value.trim()) }.apply() }

    override val configured: Boolean get() = apiKey != null && apiSecret != null && passphrase != null

    override val maskedKey: String?
        get() = apiKey?.let { if (it.length <= 8) "••••" else it.take(4) + "…" + it.takeLast(4) }

    fun clear() {
        prefs.edit().remove(KEY_API).remove(KEY_SECRET).remove(KEY_PASS).apply()
    }

    companion object {
        private const val FILE = "crypto_secure"
        private const val KEY_API = "cb_key"
        private const val KEY_SECRET = "cb_secret"
        private const val KEY_PASS = "cb_passphrase"

        /** Unit-test fake — no Android. */
        fun fake(key: String? = "k", secret: String? = "s", pass: String? = "p") = object : CryptoCredentialSource {
            override val apiKey = key
            override val apiSecret = secret
            override val passphrase = pass
            override val configured = key != null && secret != null && pass != null
            override val maskedKey = key?.let { "k…" }
        }
    }
}
