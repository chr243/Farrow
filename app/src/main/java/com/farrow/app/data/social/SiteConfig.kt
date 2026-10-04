package com.farrow.app.data.social

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ScrapeSpec(
    val item: String,
    /** field name -> "css selector[@attribute]" ("" selector = the item itself; attribute defaults to innerText). */
    val fields: Map<String, String>,
    val dedupeField: String? = null,
)

@Serializable
data class AutomationStep(
    /** goto | waitFor | waitGone | waitAny | click | clickText | type | typeEditor | press | sleep | waitPosted */
    val action: String,
    val url: String? = null,
    val selector: String? = null,
    /** waitAny: selector keys, the first one present wins. */
    val selectors: List<String> = emptyList(),
    /** type: text to type ({placeholders}); clickText: visible button text to match (case-insensitive). */
    val text: String? = null,
    val key: String? = null,
    val submit: Boolean = false,
    val optional: Boolean = false,
    /** Per-step budget; the step fails with a clear message when it is exceeded. */
    val timeoutMs: Long = 15_000,
    val ms: Long = 0,
    /** click / clickText: key to press when the element can't be clicked (e.g. "Enter"). */
    val fallbackKey: String? = null,
    /** Shown to the user when this step fails (e.g. what X probably wants). */
    val hint: String? = null,
    /**
     * clickText: case-insensitive JS regex for the visible text (overrides [text]).
     * type: regex for the input's label/placeholder/aria-label; enables the deep, text-based finder.
     */
    val match: String? = null,
    /** clickText: regex of texts to skip (e.g. "^Continue with"). */
    val exclude: String? = null,
    /** type: fall back to any visible text/email/tel input inside a dialog. */
    val dialogFallback: Boolean = false,
    /** Human-readable step name for progress/error messages. */
    val label: String? = null,
    /** clickText: click like [click] does (TBP click → wait for idle → JS click → [fallbackKey]) instead of one TBP try. */
    val sturdy: Boolean = false,
    /** clickText: when nothing matches, open these URLs (keys or full URLs) one by one and look again. */
    val fallbackUrls: List<String> = emptyList(),
    /** clickText / waitFor: regex of popup buttons ("Not now", cookie consent) to click away while waiting. */
    val dismiss: String? = null,
    /** clickText: CSS of the elements whose text/aria-label is matched (default: buttons, role=button, links). */
    val scope: String? = null,
)

/**
 * Everything site-specific (URLs, DOM selectors, scrape fields, post/login step scripts) lives in ONE JSON file per
 * site: `assets/selectors/<site>.json`. Drop an updated copy into `filesDir/selectors/<site>.json` (or use
 * Settings → Social accounts → Update selectors) to override it without an app update.
 */
@Serializable
data class SiteConfig(
    val version: Int = 1,
    val site: String,
    val displayName: String,
    val domain: String,
    val sessionName: String = site,
    val loginCookies: List<String> = emptyList(),
    /** Non-HttpOnly cookies that `document.cookie` can see when logged in. */
    val readableLoginCookies: List<String> = emptyList(),
    val urls: Map<String, String> = emptyMap(),
    val sessionExpiredUrlPatterns: List<String> = emptyList(),
    val selectors: Map<String, String> = emptyMap(),
    val scrape: ScrapeSpec? = null,
    /** Replies under a post (x_scrape kind=replies); see [RepliesSpec]. */
    val replies: RepliesSpec? = null,
    /** web_scrape on this site without a selector returns only this part of the page (no nav/sidebar/account banner). */
    val textScope: TextScope? = null,
    val postSteps: List<AutomationStep> = emptyList(),
    val loginSteps: List<AutomationStep> = emptyList(),
) {
    /** Resolves a selector key (e.g. "composeText") to CSS; unknown keys are treated as raw CSS. */
    fun sel(keyOrCss: String): String = selectors[keyOrCss] ?: keyOrCss

    /** Resolves a URL key with `{placeholders}` (values are URL-encoded except for path-safe handles). */
    fun url(keyOrUrl: String, params: Map<String, String> = emptyMap()): String {
        var u = urls[keyOrUrl] ?: keyOrUrl
        params.forEach { (k, v) -> u = u.replace("{$k}", URLEncoder.encode(v, "UTF-8").replace("+", "%20")) }
        return u
    }
}

@Singleton
class SelectorStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, SiteConfig>()

    fun overrideFile(site: String) = File(File(context.filesDir, "selectors"), "$site.json")

    fun get(site: String): SiteConfig = cache.getOrPut(site) { load(site) }

    /** Validates and installs an updated selectors file; returns the parsed version. */
    fun update(site: String, rawJson: String): SiteConfig {
        val cfg = json.decodeFromString(SiteConfig.serializer(), rawJson)
        require(cfg.site == site) { "File is for site '${cfg.site}', expected '$site'" }
        overrideFile(site).apply { parentFile?.mkdirs(); writeText(rawJson) }
        cache[site] = cfg
        return cfg
    }

    fun resetToBundled(site: String) {
        overrideFile(site).delete(); cache.remove(site)
    }

    fun isOverridden(site: String) = overrideFile(site).exists()

    private fun load(site: String): SiteConfig {
        val raw = context.assets.open("selectors/$site.json").bufferedReader().use { it.readText() }
        val bundled = json.decodeFromString(SiteConfig.serializer(), raw)
        val override = overrideFile(site)
        if (override.exists()) {
            // An override older than the bundled file (e.g. v3 post steps without the v4 timeout fixes) is ignored.
            runCatching { json.decodeFromString(SiteConfig.serializer(), override.readText()) }.getOrNull()
                ?.takeIf { it.version >= bundled.version }?.let { return it }
        }
        return bundled
    }

    companion object {
        const val X = "x"
        const val FACEBOOK = "facebook"
    }
}
