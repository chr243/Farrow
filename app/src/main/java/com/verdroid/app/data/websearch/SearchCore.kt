package com.verdroid.app.data.websearch

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Port of hec-ovi/websearch-skill (MIT) layer 1: keyless multi-engine search, canonical-URL dedup with provenance and
 * de-correlated weighted Reciprocal Rank Fusion. Engines that share an index (DuckDuckGo/Yahoo are Bing-backed) are
 * one correlation group, so their agreement counts as ONE vote.
 */

/** One engine's raw hit (rank is 1-based within that engine). */
data class RawResult(val url: String, val title: String, val snippet: String, val rank: Int)

data class EngineOutput(val engine: String, val results: List<RawResult> = emptyList(), val error: String? = null, val blocked: Boolean = false)

data class DocSource(val engine: String, val group: String, val rank: Int)

data class DedupedDoc(
    val url: String,
    val displayUrl: String,
    var title: String,
    var snippet: String,
    val sources: MutableList<DocSource> = mutableListOf(),
)

data class FusedResult(val rank: Int, val title: String, val url: String, val snippet: String, val engines: List<String>, val score: Double)

object Canonical {
    private val TRACKING = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name", "utm_reader",
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "fbclid", "msclkid", "yclid", "mc_eid", "mc_cid", "_hsenc", "_hsmi",
        "igshid", "ref", "ref_src", "ref_url", "spm", "vero_id", "oly_anon_id", "oly_enc_id",
    )

    /**
     * Lowercase scheme/host, strip `www.`, drop the fragment, default ports and tracking params, sort the remaining
     * query params, drop a trailing slash (except the root). Falls back to the trimmed input when it doesn't parse.
     */
    fun canonicalize(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        return try {
            val u = URI(raw)
            val scheme = u.scheme?.lowercase() ?: return raw
            var host = u.host?.lowercase() ?: return raw
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            val port = u.port
            val netloc = if (port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) host else "$host:$port"
            var path = u.rawPath.orEmpty()
            path = when {
                path.isEmpty() -> "/"
                path.length > 1 && path.endsWith("/") -> path.trimEnd('/').ifEmpty { "/" }
                else -> path
            }
            val kept = u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to it.substringAfter('=', "") }
                .filter { (k, _) -> decode(k) !in TRACKING }
                .sortedWith(compareBy({ it.first }, { it.second }))
            val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
            "$scheme://$netloc$path$query"
        } catch (_: Exception) { raw }
    }

    internal fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    internal fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Query parameter [name] of [url] (decoded), or null. */
    fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.let(::decode)

    /**
     * Unwraps search-engine redirectors to the real target: DuckDuckGo `duckduckgo.com/l/?uddg=<url>`, Bing
     * `bing.com/ck/a?…&u=a1<base64url>`, Yahoo `…/RU=<url>/RK=…`. Protocol-relative `//host` becomes https.
     */
    fun unwrapRedirect(href: String): String {
        var h = href.trim()
        if (h.startsWith("//")) h = "https:$h"
        if (Regex("^https?://(html\\.|lite\\.)?duckduckgo\\.com/l/").containsMatchIn(h)) {
            queryParam(h, "uddg")?.takeIf { it.startsWith("http") }?.let { return it }
        }
        if (h.startsWith("https://www.bing.com/ck/a?")) {
            val u = queryParam(h, "u")
            if (u != null && u.length > 2) runCatching {
                val b64 = u.substring(2).let { it + "=".repeat((4 - it.length % 4) % 4) }
                String(java.util.Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
        }
        if ("/RU=" in h) {
            val t = h.substringAfter("/RU=").substringBefore("/RK=").substringBefore("/RS=")
            decode(t).takeIf { it.startsWith("http") }?.let { return it }
        }
        return h
    }
}

object Fusion {
    const val K = 60
    private const val CONSENSUS_STEP = 0.10

    /** Merge raw results by canonical URL, keeping one source per engine occurrence (first-seen order). */
    fun dedupe(tagged: List<Triple<String, String, RawResult>>): List<DedupedDoc> {
        val docs = LinkedHashMap<String, DedupedDoc>()
        for ((engine, group, r) in tagged) {
            val canonical = Canonical.canonicalize(r.url)
            if (canonical.isEmpty()) continue
            val src = DocSource(engine, group, r.rank)
            val existing = docs[canonical]
            if (existing == null) {
                docs[canonical] = DedupedDoc(canonical, r.url, r.title, r.snippet, mutableListOf(src))
                continue
            }
            val bestRank = existing.sources.minOf { it.rank }
            existing.sources += src
            if (r.snippet.length > existing.snippet.length) existing.snippet = r.snippet
            if (r.title.isNotBlank() && (existing.title.isBlank() || r.rank < bestRank)) existing.title = r.title
        }
        return docs.values.toList()
    }

    /** De-correlated RRF: each correlation group votes once with its best rank; +10 % per extra distinct group. */
    fun score(doc: DedupedDoc, k: Int = K): Double {
        val best = HashMap<String, Int>()
        for (s in doc.sources) best[s.group] = minOf(best[s.group] ?: Int.MAX_VALUE, s.rank)
        var base = best.values.sumOf { 1.0 / (k + it) }
        if (best.size > 1) base *= 1.0 + CONSENSUS_STEP * (best.size - 1)
        return base
    }

    /** Sorted by score desc, then best rank asc, then canonical URL asc (deterministic). */
    fun fuse(docs: List<DedupedDoc>): List<FusedResult> =
        docs.map { it to score(it) }
            .sortedWith(compareBy<Pair<DedupedDoc, Double>>({ -it.second }, { p -> p.first.sources.minOf { it.rank } }, { it.first.url }))
            .mapIndexed { i, (d, s) -> FusedResult(i + 1, d.title, d.url, d.snippet, d.sources.map { it.engine }.distinct(), s) }
}
