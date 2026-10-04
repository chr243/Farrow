package com.farrow.app.data.browser

import java.net.URLEncoder

/**
 * Language of the internal browser (TBP's Firefox), the HTTP fallback fetch and search URLs. Default English, even
 * on a French phone: Firefox's user.js gets intl.accept_languages / intl.locale.requested /
 * javascript.use_us_english_locale (the bridge writes them before every daemon start), Google gets hl/gl/pws=0 on
 * google.com, DuckDuckGo gets kl.
 */
object BrowserLanguage {
    data class Lang(val code: String, val label: String, val acceptLanguage: String, val locale: String, val region: String, val ddg: String)

    val ALL = listOf(
        Lang("en", "English", "en-US, en", "en-US", "us", "us-en"),
        Lang("fr", "Français", "fr-FR, fr, en-US, en", "fr-FR", "fr", "fr-fr"),
        Lang("de", "Deutsch", "de-DE, de, en-US, en", "de-DE", "de", "de-de"),
        Lang("es", "Español", "es-ES, es, en-US, en", "es-ES", "es", "es-es"),
        Lang("it", "Italiano", "it-IT, it, en-US, en", "it-IT", "it", "it-it"),
    )
    const val DEFAULT = "en"

    fun of(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: ALL.first()

    /** HTTP header form ("en-US,en;q=0.9"). */
    fun acceptHeader(code: String?): String = of(code).acceptLanguage.split(",").map { it.trim() }
        .mapIndexed { i, t -> if (i == 0) t else "$t;q=${"%.1f".format(java.util.Locale.US, 1.0 - i * 0.1)}" }.joinToString(",")

    /**
     * Google search → https://www.google.com/search?…&hl=en&gl=us&pws=0 (google.com instead of google.fr etc.);
     * DuckDuckGo → kl=us-en. Other URLs unchanged. Existing hl/gl/kl values are replaced.
     */
    fun localizeSearchUrl(url: String, code: String? = DEFAULT): String {
        val l = of(code)
        val m = Regex("""^https?://(?:www\.)?google\.[a-z.]+(/search|/webhp|/)?(\?.*)?$""", RegexOption.IGNORE_CASE).find(url)
        if (m != null) {
            val path = m.groupValues[1].ifBlank { "/" }
            val params = query(m.groupValues[2]).filterKeys { it !in setOf("hl", "gl", "pws") } +
                mapOf("hl" to l.code, "gl" to l.region, "pws" to "0")
            return "https://www.google.com$path?" + encode(params)
        }
        val d = Regex("""^https?://(html\.|lite\.|www\.)?duckduckgo\.com(/[^?]*)?(\?.*)?$""", RegexOption.IGNORE_CASE).find(url)
        if (d != null) {
            val params = query(d.groupValues[3]).filterKeys { it != "kl" } + mapOf("kl" to l.ddg)
            return "https://${d.groupValues[1]}duckduckgo.com${d.groupValues[2].ifBlank { "/" }}?" + encode(params)
        }
        return url
    }

    private fun query(q: String): Map<String, String> = q.removePrefix("?").split("&").filter { it.isNotBlank() }.associate {
        val k = it.substringBefore("="); val v = it.substringAfter("=", "")
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    private fun encode(p: Map<String, String>) = p.entries.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }
}
