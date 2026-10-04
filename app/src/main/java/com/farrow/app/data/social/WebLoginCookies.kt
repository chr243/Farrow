package com.farrow.app.data.social

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One cookie captured from the in-app WebView login, ready for the TBP import (Firefox). */
data class CapturedCookie(val name: String, val value: String, val domain: String, val path: String = "/",
                          val secure: Boolean = true, val httpOnly: Boolean = false,
                          /** Epoch seconds. CookieManager gives none → +1 year (a session cookie is lost when Firefox restarts). */
                          val expires: Long = System.currentTimeMillis() / 1000 + WebLoginCookies.ONE_YEAR_S,
                          val sameSite: String = "None")

/** Per-site settings for the WebView login ("Log in to X" / "Log in to Facebook"). */
data class WebLoginSpec(
    val site: String,
    val displayName: String,
    val loginUrl: String,
    /** URLs whose cookies are read with CookieManager.getCookie. */
    val cookieUrls: List<String>,
    /** All must be present to count as logged in. */
    val required: List<String>,
    /** Captured too when present (not required). */
    val optional: List<String>,
    /** Domain the cookies are imported on in TBP. */
    val importDomain: String,
    val httpOnly: Set<String>,
    /** Page TBP's Firefox must be on while the cookies are set (document.cookie only works for the current origin). */
    val origin: String,
)

object WebLoginCookies {
    const val ONE_YEAR_S = 365L * 24 * 3600

    val X = WebLoginSpec(SelectorStore.X, "X", "https://x.com/i/flow/login", listOf("https://x.com", "https://x.com/home"),
        required = listOf("auth_token", "ct0"), optional = listOf("twid", "kdt", "att", "guest_id"), importDomain = ".x.com",
        httpOnly = setOf("auth_token", "kdt", "_twitter_sess", "att"), origin = "https://x.com/")
    val FACEBOOK = WebLoginSpec(SelectorStore.FACEBOOK, "Facebook", "https://m.facebook.com/login",
        listOf("https://m.facebook.com", "https://www.facebook.com", "https://facebook.com"),
        required = listOf("c_user", "xs"), optional = listOf("datr", "fr", "sb"), importDomain = ".facebook.com",
        httpOnly = setOf("xs", "fr", "datr", "sb"), origin = "https://www.facebook.com/")

    fun spec(site: String): WebLoginSpec? = when (site) { SelectorStore.X -> X; SelectorStore.FACEBOOK -> FACEBOOK; else -> null }

    /** CookieManager.getCookie output: `a=b; c=d` (values may contain '='). First occurrence of a name wins. */
    fun parseHeader(header: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        header.orEmpty().split(';').forEach { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) return@forEach
            val name = p.substring(0, i).trim()
            val value = p.substring(i + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty() && name !in out) out[name] = value
        }
        return out
    }

    /** Merges the headers of several cookie URLs (earlier URLs win). */
    fun merge(headers: List<String?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { h -> parseHeader(h).forEach { (k, v) -> out.putIfAbsent(k, v) } }
        return out
    }

    fun missing(cookies: Map<String, String>, spec: WebLoginSpec): List<String> = spec.required.filter { cookies[it].isNullOrBlank() }

    fun isLoggedIn(cookies: Map<String, String>, spec: WebLoginSpec): Boolean = missing(cookies, spec).isEmpty()

    /** ALL captured cookies on [WebLoginSpec.importDomain], path=/, secure, httpOnly for the session cookies. */
    fun toImport(cookies: Map<String, String>, spec: WebLoginSpec): List<CapturedCookie> =
        cookies.map { (k, v) -> CapturedCookie(k, v, spec.importDomain, "/", secure = true, httpOnly = k in spec.httpOnly) }

    /**
     * TBP cookies JSON (src/cookies.py load/save schema): name, value, domain ('.host' = include subdomains), path,
     * secure, httpOnly, sameSite, expires (epoch s, > 0). SameSite=None is only accepted with secure=true.
     */
    fun toTbpJson(list: List<CapturedCookie>): JsonArray = buildJsonArray {
        list.filter { it.name.isNotBlank() }.forEach { c ->
            val same = c.sameSite.lowercase().replaceFirstChar { it.uppercase() }.takeIf { it in setOf("None", "Lax", "Strict") } ?: "None"
            val domain = c.domain.trim().let { if (it.isNotEmpty() && !it.startsWith(".") && it.contains('.')) ".$it" else it }
            val now = System.currentTimeMillis() / 1000
            add(buildJsonObject {
                put("name", c.name); put("value", c.value); put("domain", domain); put("path", c.path.ifBlank { "/" })
                put("secure", c.secure || same == "None"); put("httpOnly", c.httpOnly); put("sameSite", same)
                put("expires", if (c.expires > now) c.expires else now + ONE_YEAR_S)
            })
        }
    }

    /** X twid cookie is `u%3D<numeric id>` (or `u=<id>`). */
    fun xUserId(twid: String?): String? = twid?.let { java.net.URLDecoder.decode(it.trim('"'), "UTF-8") }
        ?.substringAfter("u=", "")?.takeWhile { it.isDigit() }?.ifEmpty { null }

    /** Strip the WebView markers from the default UA so it looks like mobile Chrome. */
    fun chromeUserAgent(webViewUa: String): String = webViewUa
        .replace("; wv)", ")")
        .replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}
