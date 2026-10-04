package com.farrow.app.data.social

import kotlinx.serialization.json.*

/**
 * Parses pasted cookies in any common format into name → value:
 *  - `auth_token=…; ct0=…` (Cookie header / document.cookie style, also one per line)
 *  - JSON export (Cookie-Editor / EditThisCookie array of {name, value, domain}) or a plain {"name": "value"} object
 *  - Netscape cookies.txt (7 tab-separated fields; `#HttpOnly_` prefixes allowed)
 *  - two bare values on separate lines, mapped to [bareNames] in order (e.g. auth_token, ct0)
 * Cookies whose domain doesn't belong to [domains] are dropped (domain-less entries are kept).
 */
object CookieParser {
    fun parse(raw: String, domains: List<String> = emptyList(), bareNames: List<String> = emptyList()): Map<String, String> {
        val text = raw.trim().removePrefix("Cookie:").trim()
        if (text.isEmpty()) return emptyMap()
        fun domainOk(d: String?): Boolean {
            if (d.isNullOrBlank() || domains.isEmpty()) return true
            val host = d.trim().trimStart('.').lowercase()
            return domains.any { want -> val w = want.trimStart('.').lowercase(); host == w || host.endsWith(".$w") }
        }
        val out = LinkedHashMap<String, String>()
        if (text.startsWith("[") || text.startsWith("{")) {
            val el = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            when (el) {
                is JsonArray -> el.forEach { item ->
                    val o = item as? JsonObject ?: return@forEach
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (domainOk(o["domain"]?.jsonPrimitive?.contentOrNull)) out[name] = value
                }
                is JsonObject -> {
                    val cookies = el["cookies"] as? JsonArray
                    if (cookies != null) return parse(cookies.toString(), domains, bareNames)
                    el.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it } }
                }
                else -> Unit
            }
            if (out.isNotEmpty()) return out
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Netscape cookies.txt
        val netscape = lines.filter { !it.startsWith("#") || it.startsWith("#HttpOnly_") }.map { it.split('\t') }.filter { it.size >= 7 }
        if (netscape.isNotEmpty()) {
            netscape.forEach { f -> if (domainOk(f[0].removePrefix("#HttpOnly_"))) out[f[5]] = f[6] }
            return out
        }
        // name=value pairs separated by ; or newlines
        if (text.contains('=')) {
            text.split(';', '\n').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim().trim('"')
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
            if (out.isNotEmpty()) return out
        }
        // Bare values, e.g. "<auth_token>\n<ct0>"
        lines.filter { !it.contains(' ') }.zip(bareNames).forEach { (v, n) -> out[n] = v }
        return out
    }
}
