package com.verdroid.app.agent.tools

import com.verdroid.app.data.websearch.SearchRequest
import com.verdroid.app.data.websearch.WebSearcher
import kotlinx.serialization.json.*

/**
 * web_search: the agent's default way to search the web (port of hec-ovi/websearch-skill's `web-search`). Keyless
 * multi-engine fan-out (DuckDuckGo HTML with `duckduckgo.com/l/?uddg=` unwrapping + Lite fallback, Brave, Bing,
 * Mojeek, Yahoo, Wikipedia) with canonical-URL dedup and de-correlated rank fusion. Plain HTTP, no browser: fast, and
 * a captcha on one engine only drops that engine.
 */
class WebSearchTool(private val searcher: WebSearcher = WebSearcher()) : AgentTool {
    override val name = "web_search"
    override val description = "Search the web (default for finding current or external information). Keyless multi-engine " +
        "search (DuckDuckGo, Brave, Bing, Mojeek, Yahoo, Wikipedia) with deduplicated, rank-fused results: title, url, snippet, " +
        "engines. Then read the 2–3 most relevant URLs with web_fetch format=markdown. Use site=reddit.com or site=x.com for those sites."
    override val parameters = schema(listOf("query"),
        "query" to prop("string", "Search query (refine it rather than paging)"),
        "max_results" to prop("integer", "Results to return (default 8, max 30; 0 = all)"),
        "site" to prop("string", "Restrict to one host, e.g. reddit.com"),
        "freshness" to prop("string", "any (default) | day | week | month | year"),
        "region" to prop("string", "country-language, default us-en (English sources)"),
        "safesearch" to prop("string", "off | moderate (default) | strict"),
        "detail" to prop("string", "concise (default) | detailed (adds engines and fused score)"))

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("query")?.trim()?.takeIf { it.isNotEmpty() } ?: return errorJson("query is required")
        val site = args.str("site")?.trim()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val freshness = (args.str("freshness") ?: "any").lowercase().takeIf { it in setOf("any", "day", "week", "month", "year") }
            ?: return errorJson("freshness must be any, day, week, month or year")
        val safe = (args.str("safesearch") ?: "moderate").lowercase().takeIf { it in setOf("off", "moderate", "strict") }
            ?: return errorJson("safesearch must be off, moderate or strict")
        val region = (args.str("region") ?: "us-en").lowercase().takeIf { Regex("^[a-z]{2}-[a-z]{2}$").matches(it) }
            ?: return errorJson("region must look like us-en")
        val max = (args.int("max_results") ?: 8).coerceIn(0, 30)
        val detailed = args.str("detail") == "detailed"
        val query = if (site != null) "$q site:$site" else q
        val out = searcher.search(SearchRequest(query, max, freshness, region, safe))
        return buildJsonObject {
            put("ok", out.results.isNotEmpty())
            put("query", query)
            putJsonArray("results") {
                out.results.forEach { r ->
                    addJsonObject {
                        put("rank", r.rank); put("title", r.title); put("url", r.url); put("snippet", r.snippet.take(400))
                        if (detailed) { putJsonArray("engines") { r.engines.forEach { add(it) } }; put("score", "%.5f".format(java.util.Locale.US, r.score).toDouble()) }
                    }
                }
            }
            putJsonObject("engines") {
                out.engines.forEach { e -> put(e.engine, e.error?.let { "error: $it" } ?: "ok (${e.results.size})") }
            }
            if (out.warnings.isNotEmpty()) putJsonArray("warnings") { out.warnings.forEach { add(it) } }
        }.toString()
    }
}
