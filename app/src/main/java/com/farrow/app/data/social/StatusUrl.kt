package com.farrow.app.data.social

import java.net.URI

/**
 * Canonical post URLs for x_reply (v1.0.5). x_reply always works on `https://x.com/<user>/status/<id>`, never on the feed,
 * a profile or a search: [canonical] normalizes what the agent passes, [matches] decides whether the browser already
 * is exactly on that post (else x_reply hard-navigates there first).
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

    /**
     * True when [href] is exactly that post's page: same site, path `/<user>/status/<id>` (trailing slash and query
     * allowed, no /photo/… etc.). For `/i/status/<id>` X redirects to the author's handle, so any handle matches.
     */
    fun matches(href: String?, c: Canon): Boolean {
        val u = href?.let { runCatching { URI(it.trim().trim('"')) }.getOrNull() } ?: return false
        if (host(u.host) !in HOSTS) return false
        val m = Regex("^/([A-Za-z0-9_]{1,30})/status/(\\d{5,25})/?$").find(u.path ?: return false) ?: return false
        if (m.groupValues[2] != c.id) return false
        return c.user == "i" || m.groupValues[1].equals(c.user, ignoreCase = true)
    }

    /** x_reply's navigation decision: navigate unless the page already is exactly the post. */
    fun needsNavigation(href: String?, c: Canon): Boolean = !matches(href, c)

    /** Page probe: location + whether an article whose own permalink (with a <time>) is this status id is present. */
    fun probeJs(article: String, id: String): String {
        val a = kotlinx.serialization.json.JsonPrimitive(article).toString()
        return """(()=>{const re=/\/status\/$id(?:[\/?#]|$)/;const ok=[...document.querySelectorAll($a)].some(x=>
            [...x.querySelectorAll('a[href*="/status/"]')].some(l=>re.test(l.getAttribute('href')||'')&&l.querySelector('time')));
            return JSON.stringify({u:location.href,a:ok})})()"""
    }
}
