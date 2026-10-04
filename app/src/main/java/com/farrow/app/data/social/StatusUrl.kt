package com.farrow.app.data.social

import java.net.URI

/**
 * Canonical post URLs (v1.0.5): [canonical] normalizes a post link or bare id to `https://x.com/<user>/status/<id>`
 * (the `status_url` of every x_scrape item).
 */
object StatusUrl {
    data class Canon(val url: String, val user: String, val id: String)

    private val HOSTS = setOf("x.com", "twitter.com")
    private val BARE_ID = Regex("^\\d{5,25}$")
    /** /<user>/status/<id>[/photo/1|/video/1|/analytics|/quotes…] or /i/status/<id>, /i/web/status/<id>. */
    private val PATH = Regex("^/(?:(i)(?:/web)?|([A-Za-z0-9_]{1,30}))/status(?:es)?/(\\d{5,25})(/.*)?$")

    private fun host(h: String?) = (h ?: "").lowercase().removePrefix("www.").removePrefix("mobile.")

    /** Null = not a post URL. A bare id becomes /i/status/<id>; query, fragment and media/analytics suffixes go. */
    fun canonical(input: String?): Canon? {
        val s = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (BARE_ID.matches(s)) return Canon("https://x.com/i/status/$s", "i", s)
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s else "https://" + s.removePrefix("//")
        val u = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() !in setOf("http", "https") || host(u.host) !in HOSTS) return null
        val m = PATH.find(u.path ?: return null) ?: return null
        val user = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val id = m.groupValues[3]
        return Canon("https://x.com/$user/status/$id", user, id)
    }
}
