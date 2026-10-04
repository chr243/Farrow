package com.farrow.app.data.social

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Last known login status per site (shown when the account screen opens — no automatic check or scan). */
data class KnownStatus(val status: LoginStatus, val checkedAt: Long)

@Singleton
class LastStatusStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("login_status", Context.MODE_PRIVATE)

    fun get(site: String): KnownStatus? {
        val state = prefs.getString("$site.state", null)?.let { runCatching { LoginState.valueOf(it) }.getOrNull() } ?: return null
        return KnownStatus(LoginStatus(state, prefs.getString("$site.url", null), emptyList(), prefs.getString("$site.reason", "").orEmpty()),
            prefs.getLong("$site.at", 0L))
    }

    fun put(site: String, st: LoginStatus, at: Long = System.currentTimeMillis()) {
        prefs.edit().putString("$site.state", st.state.name).putString("$site.url", st.url).putString("$site.reason", st.reason)
            .putLong("$site.at", at).apply()
    }
}
