package com.verdroid.app.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP GET/HEAD via OkHttp — no browser. Use this for public pages,
 * APIs and HTML that don't need JavaScript or a login.
 */
class WebFetchTool(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : AgentTool {
    override val name = "web_fetch"
    override val description = "Fetch a known URL with a plain HTTP GET (or HEAD) — no browser, no JavaScript. format=markdown " +
        "(use it for reading web pages, e.g. web_search results) returns the page's main content as clean Markdown, paginated " +
        "(page, total_pages, has_more) and fenced as untrusted data. format=raw (default) returns status, final URL, content type and " +
        "body (truncated) for APIs and files. To find pages, use web_search first."
    override val parameters = schema(listOf("url"),
        "url" to prop("string", "Absolute http(s) URL"),
        "method" to prop("string", "GET (default) or HEAD"),
        "max_bytes" to prop("integer", "raw: max body bytes to return (default 100000, max 500000)"),
        "format" to prop("string", "raw (default) | markdown (readable page text, paginated, fenced)"),
        "page" to prop("integer", "markdown: page number (default 1); later pages come from cache"),
        "page_size_tokens" to prop("integer", "markdown: tokens per page (default 4000, 0 = whole document)"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@withContext errorJson("url must be an absolute http(s) URL")
        val method = (args.str("method") ?: "GET").uppercase()
        if (method !in setOf("GET", "HEAD")) return@withContext errorJson("method must be GET or HEAD")
        val max = (args.int("max_bytes") ?: 100_000).coerceIn(1, 500_000)
        val format = (args.str("format") ?: "raw").lowercase()
        if (format !in setOf("raw", "markdown")) return@withContext errorJson("format must be raw or markdown")
        if (format == "markdown") return@withContext markdown(url, (args.int("page") ?: 1).coerceAtLeast(1),
            (args.int("page_size_tokens") ?: 4_000).coerceIn(0, 50_000))
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Verdroid/1.0 (web_fetch)")
                .header("Accept", "*/*")
                .method(method, null)
                .build()
            http.newCall(req).execute().use { resp ->
                val bytes = if (method == "HEAD") ByteArray(0) else resp.body?.bytes() ?: ByteArray(0)
                val truncated = bytes.size > max
                val slice = if (truncated) bytes.copyOf(max) else bytes
                val text = runCatching { String(slice, Charsets.UTF_8) }.getOrElse { "" }
                buildJsonObject {
                    put("ok", resp.isSuccessful)
                    put("status", resp.code)
                    put("url", resp.request.url.toString())
                    put("content_type", resp.header("Content-Type") ?: "")
                    put("bytes", bytes.size)
                    put("truncated", truncated)
                    if (method != "HEAD") put("body", text)
                }.toString()
            }
        } catch (e: Exception) {
            errorJson("web_fetch failed: ${e.message}")
        }
    }

    /** Extracted Markdown per URL (final URL + title), so later pages of a long document need no network. */
    private val cache = object : LinkedHashMap<String, Triple<String, String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<String, String, String>>?) = size > 20
    }

    private fun markdown(url: String, page: Int, pageTokens: Int): String {
        var source = "cache"
        var status = 200
        var blockReason: String? = null
        val entry = synchronized(cache) { cache[url] } ?: run {
            source = "live"
            val req = Request.Builder().url(url)
                .header("User-Agent", com.verdroid.app.data.websearch.WebSearcher.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val fetched = try {
                http.newCall(req).execute().use { resp ->
                    status = resp.code
                    val body = resp.body?.bytes()?.let { b -> String(if (b.size > MAX_HTML) b.copyOf(MAX_HTML) else b, Charsets.UTF_8) }.orEmpty()
                    val type = resp.header("Content-Type").orEmpty()
                    blockReason = com.verdroid.app.data.websearch.BlockDetector.reason(resp.code, body)
                    val finalUrl = resp.request.url.toString()
                    val (title, md) = if ("html" in type || body.trimStart().startsWith("<")) {
                        com.verdroid.app.data.websearch.MarkdownExtractor.extract(body, finalUrl).let { it.title to it.markdown }
                    } else "" to body
                    Triple(finalUrl, title, md)
                }
            } catch (e: Exception) {
                return errorJson("web_fetch failed: ${e.message}")
            }
            if (status in 200..299 && blockReason == null) synchronized(cache) { cache[url] = fetched }
            fetched
        }
        val (finalUrl, title, md) = entry
        val pages = com.verdroid.app.data.websearch.Paginator.pages(md, pageTokens)
        if (page > pages.size) return errorJson("page $page is past the end (total_pages ${pages.size})")
        val text = pages[page - 1]
        return buildJsonObject {
            put("ok", status in 200..299 && blockReason == null)
            put("status", status)
            put("url", finalUrl)
            put("title", title)
            put("page", page); put("total_pages", pages.size); put("has_more", page < pages.size)
            put("page_tokens", com.verdroid.app.data.websearch.Paginator.estimateTokens(text))
            put("total_tokens", com.verdroid.app.data.websearch.Paginator.estimateTokens(md))
            put("source", source)
            put("untrusted", true)
            put("blocked", blockReason != null); blockReason?.let { put("block_reason", it) }
            put("content", com.verdroid.app.data.websearch.UntrustedFence.fence(text, finalUrl))
        }.toString()
    }

    private companion object { const val MAX_HTML = 3_000_000 }
}
