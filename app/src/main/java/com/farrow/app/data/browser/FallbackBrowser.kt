package com.farrow.app.data.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedPage(val url: String, val title: String, val text: String, val links: List<Pair<String, String>>)

/**
 * Fallback when the Termux bridge is not available.
 *
 * LIMITATION: Chrome Custom Tabs cannot hand the rendered DOM back to the app (no JS injection / no content access),
 * so Custom Tabs are only used to *show* a page to the user (e.g. for manual login). Scraping instead uses a plain
 * OkHttp GET + Jsoup parse: no JavaScript is executed, there is no click/type support, and sites that require JS or
 * bot checks (X.com, Facebook, Cloudflare) will return little or nothing. Use the bridge for those.
 */
@Singleton
class FallbackBrowser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fingerprint: DeviceFingerprint,
    private val prefs: com.farrow.app.data.prefs.AppPrefs,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun scrape(rawUrl: String, selector: String? = null, maxChars: Int = 20_000): ScrapedPage = withContext(Dispatchers.IO) {
        val lang = prefs.browserLanguage.value
        val url = BrowserLanguage.localizeSearchUrl(rawUrl, lang)
        val ua = runCatching { fingerprint.get().webViewUserAgent }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", BrowserLanguage.acceptHeader(lang)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val finalUrl = resp.request.url.toString()
            val doc = Jsoup.parse(resp.body?.string().orEmpty(), finalUrl)
            doc.select("script, style, noscript, svg").remove()
            val root = if (selector.isNullOrBlank()) doc.body() else doc.selectFirst(selector)
            val text = root?.let { el -> if (selector.isNullOrBlank()) el.text() else el.wholeText().ifBlank { el.text() } }.orEmpty()
            val links = doc.select("a[href]").take(60).map { it.text().take(80) to it.absUrl("href") }
            ScrapedPage(finalUrl, doc.title(), text.take(maxChars), links)
        }
    }

    /** Opens [url] in a Custom Tab for the user to look at or log in manually. */
    fun openForUser(url: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(url))
    }
}
