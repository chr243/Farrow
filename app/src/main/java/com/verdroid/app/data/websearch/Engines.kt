package com.verdroid.app.data.websearch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** What the agent asked for (freshness: any|day|week|month|year; region like "us-en"). */
data class SearchRequest(
    val query: String,
    val maxResults: Int = 8,
    val freshness: String = "any",
    val region: String = "us-en",
    val safesearch: String = "moderate",
) {
    val country: String get() = region.substringBefore('-').lowercase()
    val lang: String get() = region.substringAfter('-', "en").lowercase()
    val freshCode: String? get() = mapOf("day" to "d", "week" to "w", "month" to "m", "year" to "y")[freshness]
}

/** A plain HTTP request an engine wants made (GET unless [form] is set, then POST form-encoded). */
data class HttpCall(val url: String, val form: Map<String, String>? = null, val cookies: Map<String, String> = emptyMap())

data class HttpReply(val status: Int, val body: String)

/** One keyless engine. [group] = correlation group (shared underlying index). Parsing is pure and fixture-tested. */
interface SearchEngine {
    val name: String
    val group: String
    fun call(req: SearchRequest): HttpCall
    /** Throws [EngineBlocked] for captcha / anomaly / rate-limit pages. */
    fun parse(reply: HttpReply, req: SearchRequest): List<RawResult>
    /** Optional second request (Wikipedia fetches the intro of its single hit). */
    suspend fun enrich(results: List<RawResult>, req: SearchRequest, fetch: suspend (HttpCall) -> HttpReply): List<RawResult> = results
}

class EngineBlocked(message: String) : Exception(message)

private fun Element?.txt(): String = this?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private fun ranked(items: List<Triple<String, String, String>>): List<RawResult> =
    items.filter { it.first.startsWith("http") && it.second.isNotBlank() }
        .mapIndexed { i, (u, t, s) -> RawResult(u, t, s, i + 1) }

private fun checkStatus(engine: String, r: HttpReply) {
    if (r.status == 429) throw EngineBlocked("$engine: rate limited (HTTP 429)")
    if (r.status == 403) throw EngineBlocked("$engine: refused (HTTP 403, likely bot protection)")
    if (r.status !in 200..299) throw IllegalStateException("$engine: HTTP ${r.status}")
}

/** DuckDuckGo's no-JS HTML endpoint (Bing-backed). Results link through `duckduckgo.com/l/?uddg=`; ads go via y.js. */
object DuckDuckGoHtml : SearchEngine {
    override val name = "duckduckgo"
    override val group = "bing"
    override fun call(req: SearchRequest) = HttpCall("https://html.duckduckgo.com/html/",
        form = buildMap { put("q", req.query); put("b", ""); put("kl", req.region); req.freshCode?.let { put("df", it) }
            if (req.safesearch == "off") put("kp", "-2") else if (req.safesearch == "strict") put("kp", "1") })

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        if (isAnomaly(reply)) throw EngineBlocked("duckduckgo: bot check (anomaly page)")
        checkStatus(name, reply)
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("div.result").filterNot { it.hasClass("result--ad") }.mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            val href = a.attr("href")
            if ("duckduckgo.com/y.js" in href) return@mapNotNull null
            Triple(Canonical.unwrapRedirect(href), a.txt(), r.selectFirst(".result__snippet").txt())
        })
    }

    /** DDG answers bots with HTTP 202 and an "anomaly" challenge page instead of results. */
    fun isAnomaly(r: HttpReply) = r.status == 202 || "anomaly-modal" in r.body || "challenge-form" in r.body
}

/** DuckDuckGo Lite (same index as [DuckDuckGoHtml], different markup; used when the HTML endpoint is blocked). */
object DuckDuckGoLite : SearchEngine {
    override val name = "duckduckgo_lite"
    override val group = "bing"
    override fun call(req: SearchRequest) = HttpCall("https://lite.duckduckgo.com/lite/",
        form = buildMap { put("q", req.query); put("kl", req.region); req.freshCode?.let { put("df", it) } })

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        if (DuckDuckGoHtml.isAnomaly(reply)) throw EngineBlocked("duckduckgo_lite: bot check (anomaly page)")
        checkStatus(name, reply)
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("a.result-link").mapNotNull { a ->
            val href = a.attr("href")
            if ("duckduckgo.com/y.js" in href) return@mapNotNull null
            val snippet = a.closest("tr")?.nextElementSibling()?.selectFirst("td.result-snippet").txt()
            Triple(Canonical.unwrapRedirect(href), a.txt(), snippet)
        })
    }
}

/** Brave Search (own index). */
object Brave : SearchEngine {
    override val name = "brave"
    override val group = "brave"
    override fun call(req: SearchRequest) = HttpCall(
        "https://search.brave.com/search?q=${Canonical.encode(req.query)}&source=web" +
            (mapOf("day" to "pd", "week" to "pw", "month" to "pm", "year" to "py")[req.freshness]?.let { "&tf=$it" } ?: ""),
        cookies = buildMap { put("country", req.country); put("useLocation", "0")
            if (req.safesearch != "moderate") put("safesearch", if (req.safesearch == "strict") "strict" else "off") })

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        checkStatus(name, reply)
        if ("captcha" in reply.body.take(20_000).lowercase() && "data-type=\"web\"" !in reply.body) throw EngineBlocked("brave: captcha page")
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("div[data-type=web]").mapNotNull { r ->
            val title = r.selectFirst("div.title")
            val a = title?.closest("a") ?: r.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val t = title?.attr("title")?.ifBlank { null } ?: title.txt()
            val body = r.selectFirst("div.generic-snippet div.content, .snippet-description, div.snippet-content").txt()
            Triple(a.attr("href"), t, body)
        })
    }
}

/** Bing. Result links are `bing.com/ck/a?…&u=a1<base64url>` redirectors; ads (`aclick`) are dropped. */
object Bing : SearchEngine {
    override val name = "bing"
    override val group = "bing"
    override fun call(req: SearchRequest) = HttpCall(
        "https://www.bing.com/search?q=${Canonical.encode(req.query)}&pq=${Canonical.encode(req.query)}&cc=${req.country}&setlang=${req.lang}",
        cookies = mapOf("_EDGE_CD" to "m=${req.lang}-${req.country}&u=${req.lang}-${req.country}",
            "_EDGE_S" to "mkt=${req.lang}-${req.country}&ui=${req.lang}-${req.country}"))

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        checkStatus(name, reply)
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("li.b_algo").mapNotNull { r ->
            val a = r.selectFirst("h2 a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.startsWith("https://www.bing.com/aclick?")) return@mapNotNull null
            Triple(Canonical.unwrapRedirect(href), a.txt(), r.select("p").joinToString(" ") { it.txt() }.trim())
        })
    }
}

/** Mojeek (own independent index). */
object Mojeek : SearchEngine {
    override val name = "mojeek"
    override val group = "mojeek"
    override fun call(req: SearchRequest) = HttpCall(
        "https://www.mojeek.com/search?q=${Canonical.encode(req.query)}" + (if (req.safesearch == "strict") "&safe=1" else ""),
        cookies = mapOf("arc" to req.country, "lb" to req.lang))

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        checkStatus(name, reply)
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("ul.results-standard > li, ul.results > li").mapNotNull { r ->
            val a = r.selectFirst("h2 a") ?: return@mapNotNull null
            Triple(a.attr("href"), a.txt(), r.selectFirst("p.s").txt())
        })
    }
}

/** Yahoo (Bing-backed). Links are `…/RU=<url>/RK=…` redirectors. */
object Yahoo : SearchEngine {
    override val name = "yahoo"
    override val group = "bing"
    override fun call(req: SearchRequest) = HttpCall(
        "https://search.yahoo.com/search?p=${Canonical.encode(req.query)}" + (req.freshCode?.let { "&btf=$it" } ?: ""))

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        checkStatus(name, reply)
        val doc = Jsoup.parse(reply.body)
        return ranked(doc.select("div.relsrch, div.algo").mapNotNull { r ->
            val a = r.selectFirst("div.compTitle a, h3 a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.startsWith("https://www.bing.com/aclick?")) return@mapNotNull null
            Triple(Canonical.unwrapRedirect(href), (a.selectFirst("h3") ?: r.selectFirst("h3")).txt().ifBlank { a.txt() },
                r.selectFirst("div.compText").txt())
        })
    }
}

/** Wikipedia opensearch: at most one article, with its intro as the snippet (disambiguation pages dropped). */
object Wikipedia : SearchEngine {
    override val name = "wikipedia"
    override val group = "wikipedia"
    override fun call(req: SearchRequest) = HttpCall(
        "https://${req.lang}.wikipedia.org/w/api.php?action=opensearch&profile=fuzzy&limit=1&search=${Canonical.encode(req.query)}")

    override fun parse(reply: HttpReply, req: SearchRequest): List<RawResult> {
        checkStatus(name, reply)
        val arr = Json.parseToJsonElement(reply.body).jsonArray
        val titles = (arr.getOrNull(1) as? JsonArray).orEmpty()
        val urls = (arr.getOrNull(3) as? JsonArray).orEmpty()
        if (titles.isEmpty() || urls.isEmpty()) return emptyList()
        return listOf(RawResult(urls[0].jsonPrimitive.content, titles[0].jsonPrimitive.content, "", 1))
    }

    override suspend fun enrich(results: List<RawResult>, req: SearchRequest, fetch: suspend (HttpCall) -> HttpReply): List<RawResult> {
        val r = results.firstOrNull() ?: return results
        val reply = runCatching {
            fetch(HttpCall("https://${req.lang}.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=${Canonical.encode(r.title)}"))
        }.getOrNull() ?: return results
        val extract = runCatching {
            val pages = Json.parseToJsonElement(reply.body).jsonObject["query"]!!.jsonObject["pages"]!!.jsonObject
            (pages.values.first() as JsonObject)["extract"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if ("may refer to:" in extract) return emptyList()
        return listOf(r.copy(snippet = extract.take(500)))
    }
}
