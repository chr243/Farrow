package com.farrow.app.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Small UI/behaviour toggles (v0.9.8). */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _notifyOnFinish = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_FINISH, false))
    /** "Notify when a task finishes" — default OFF (only attention-needed events notify). */
    val notifyOnFinish: StateFlow<Boolean> = _notifyOnFinish.asStateFlow()
    fun setNotifyOnFinish(v: Boolean) { prefs.edit().putBoolean(KEY_NOTIFY_FINISH, v).apply(); _notifyOnFinish.value = v }

    private val _autoChatHead = MutableStateFlow(prefs.getBoolean(KEY_AUTO_HEAD, true))
    /** "Auto chat head on Home" — default ON. */
    val autoChatHeadOnHome: StateFlow<Boolean> = _autoChatHead.asStateFlow()
    fun setAutoChatHeadOnHome(v: Boolean) { prefs.edit().putBoolean(KEY_AUTO_HEAD, v).apply(); _autoChatHead.value = v }

    private val _clearWebView = MutableStateFlow(prefs.getBoolean(KEY_CLEAR_WEBVIEW, true))
    /** Clear the in-app WebView's cookies after a login was imported into the internal browser (default ON). */
    val clearWebViewCookies: StateFlow<Boolean> = _clearWebView.asStateFlow()
    fun setClearWebViewCookies(v: Boolean) { prefs.edit().putBoolean(KEY_CLEAR_WEBVIEW, v).apply(); _clearWebView.value = v }

    private val _showTermux = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TERMUX, false))
    /** Run setup steps in a visible Termux session (default OFF: background RUN_COMMAND, no Termux window). */
    val showTermuxDuringSetup: StateFlow<Boolean> = _showTermux.asStateFlow()
    fun setShowTermuxDuringSetup(v: Boolean) { prefs.edit().putBoolean(KEY_SHOW_TERMUX, v).apply(); _showTermux.value = v }

    private val _browserLang = MutableStateFlow(prefs.getString(KEY_BROWSER_LANG, null) ?: com.farrow.app.data.browser.BrowserLanguage.DEFAULT)
    /** Language of the internal browser, the HTTP fallback and search URLs — default English (even on a French phone). */
    val browserLanguage: StateFlow<String> = _browserLang.asStateFlow()
    fun setBrowserLanguage(v: String) { prefs.edit().putString(KEY_BROWSER_LANG, v).apply(); _browserLang.value = v }

    /** v1.0.7 "Load images" in the internal browser (default off = images/video blocked). */
    private val _loadImages = MutableStateFlow(prefs.getBoolean(KEY_LOAD_IMAGES, false))
    val loadImages: StateFlow<Boolean> = _loadImages.asStateFlow()
    fun setLoadImages(v: Boolean) { prefs.edit().putBoolean(KEY_LOAD_IMAGES, v).apply(); _loadImages.value = v }

    private val _memKilo = MutableStateFlow(prefs.getBoolean(KEY_MEM_KILO, true))
    /** "Send memories to Kilo models" — default ON (Kilo Auto Free may log prompts; the user is informed once). */
    val sendMemoriesToKilo: StateFlow<Boolean> = _memKilo.asStateFlow()
    fun setSendMemoriesToKilo(v: Boolean) { prefs.edit().putBoolean(KEY_MEM_KILO, v).apply(); _memKilo.value = v }

    private val _kiloNotice = MutableStateFlow(prefs.getBoolean(KEY_KILO_NOTICE, false))
    /** One-time Settings notice that Kilo Auto Free may log prompts. */
    val kiloNoticeSeen: StateFlow<Boolean> = _kiloNotice.asStateFlow()
    fun dismissKiloNotice() { prefs.edit().putBoolean(KEY_KILO_NOTICE, true).apply(); _kiloNotice.value = true }

    private companion object {
        const val KEY_MEM_KILO = "send_memories_to_kilo"
        const val KEY_KILO_NOTICE = "kilo_notice_seen"
        const val KEY_BROWSER_LANG = "browser_language"
        const val KEY_LOAD_IMAGES = "load_images"
        const val KEY_SHOW_TERMUX = "show_termux_during_setup"
        const val KEY_CLEAR_WEBVIEW = "clear_webview_cookies"
        const val KEY_NOTIFY_FINISH = "notify_on_finish"
        const val KEY_AUTO_HEAD = "auto_chat_head_on_home"
    }
}
