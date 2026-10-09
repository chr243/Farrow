package com.farrow.app.agent.tools

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
    override val description = "Fetch a public URL with a plain HTTP GET (or HEAD) — no browser, no JavaScript. Use it for " +
        "APIs, static HTML, and pages that don't need a login or JS. Returns status, final URL, content type and body (truncated)."
    override val parameters = schema(listOf("url"),
        "url" to prop("string", "Absolute http(s) URL"),
        "method" to prop("string", "GET (default) or HEAD"),
        "max_bytes" to prop("integer", "Max body bytes to return (default 100000, max 500000)"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@withContext errorJson("url must be an absolute http(s) URL")
        val method = (args.str("method") ?: "GET").uppercase()
        if (method !in setOf("GET", "HEAD")) return@withContext errorJson("method must be GET or HEAD")
        val max = (args.int("max_bytes") ?: 100_000).coerceIn(1, 500_000)
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Farrow/1.0 (web_fetch)")
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
}
