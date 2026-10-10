package com.verdroid.app.data.websearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SearchOutcome(val results: List<FusedResult>, val engines: List<EngineOutput>, val warnings: List<String>)

/**
 * Fans one query out to every keyless engine in parallel (each with its own timeout), dedupes by canonical URL and
 * fuses with de-correlated RRF. A captcha or block on one engine (DuckDuckGo's anomaly page, Brave's captcha, HTTP
 * 403/429) only removes that engine; when DuckDuckGo HTML is blocked, DuckDuckGo Lite is tried once.
 */
class WebSearcher(
    private val engines: List<SearchEngine> = DEFAULT_ENGINES,
    private val fallbacks: Map<String, SearchEngine> = mapOf(DuckDuckGoHtml.name to DuckDuckGoLite),
    private val engineTimeoutMs: Long = 8_000,
    private val fetch: suspend (HttpCall) -> HttpReply = OkHttpFetcher()::fetch,
) {
    suspend fun search(req: SearchRequest): SearchOutcome = coroutineScope {
        val outputs = engines.map { e -> async { runWithFallback(e, req) } }.awaitAll()
        val tagged = outputs.flatMap { (engine, out) -> out.results.map { Triple(out.engine, engine.group, it) } }
        val fused = Fusion.fuse(Fusion.dedupe(tagged))
        val warnings = buildList {
            if (outputs.all { it.second.results.isEmpty() }) add("No engine returned results (all blocked, failed or empty). Refine the query or try again later.")
            if (req.freshness != "any") add("freshness is honoured by duckduckgo, brave and yahoo only")
        }
        SearchOutcome(if (req.maxResults <= 0) fused else fused.take(req.maxResults), outputs.map { it.second }, warnings)
    }

    private suspend fun runWithFallback(e: SearchEngine, req: SearchRequest): Pair<SearchEngine, EngineOutput> {
        val first = runOne(e, req)
        val fb = fallbacks[e.name]
        if (fb != null && first.results.isEmpty() && (first.blocked || first.error != null)) {
            val second = runOne(fb, req)
            if (second.results.isNotEmpty()) return fb to second
            return e to first.copy(error = listOfNotNull(first.error, second.error).joinToString("; "))
        }
        return e to first
    }

    private suspend fun runOne(e: SearchEngine, req: SearchRequest): EngineOutput =
        withTimeoutOrNull(engineTimeoutMs) {
            try {
                val reply = fetch(e.call(req))
                val parsed = e.parse(reply, req)
                EngineOutput(e.name, e.enrich(parsed, req, fetch))
            } catch (b: EngineBlocked) {
                EngineOutput(e.name, error = b.message, blocked = true)
            } catch (x: kotlinx.coroutines.CancellationException) {
                throw x
            } catch (x: Exception) {
                EngineOutput(e.name, error = "${x.javaClass.simpleName}: ${x.message}")
            }
        } ?: EngineOutput(e.name, error = "timed out after ${engineTimeoutMs} ms")

    companion object {
        val DEFAULT_ENGINES: List<SearchEngine> = listOf(DuckDuckGoHtml, Brave, Bing, Mojeek, Yahoo, Wikipedia)
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}

/** Plain OkHttp with a desktop-browser UA, English Accept-Language and per-request cookies. */
class OkHttpFetcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true).build(),
) {
    suspend fun fetch(c: HttpCall): HttpReply = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(c.url)
            .header("User-Agent", WebSearcher.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (c.cookies.isNotEmpty()) b.header("Cookie", c.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        c.form?.let { f -> b.post(FormBody.Builder().apply { f.forEach { (k, v) -> add(k, v) } }.build()) }
        http.newCall(b.build()).execute().use { r -> HttpReply(r.code, r.body?.string().orEmpty()) }
    }
}
