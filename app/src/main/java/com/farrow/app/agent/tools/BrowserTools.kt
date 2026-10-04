package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeResult
import com.farrow.app.data.browser.FallbackBrowser
import kotlinx.serialization.json.*
import java.io.IOException

private const val MAX_TEXT = 20_000

internal fun BridgeResult.toToolJson(extra: JsonObjectBuilder.() -> Unit = {}): String =
    if (!ok) errorJson(errorMessage) else buildJsonObject {
        put("ok", true)
        put("result", payload ?: JsonPrimitive(stdout.take(MAX_TEXT)))
        extra()
    }.toString()

private const val BRIDGE_DOWN =
    "Browser bridge is not running. Open Settings > Internal browser setup to install/start Termux Browser Pilot."

/** web_scrape: real Firefox via the Termux bridge, else OkHttp + Jsoup (no JavaScript). */
class WebScrapeTool(private val bridge: BridgeClient, private val fallback: FallbackBrowser) : AgentTool {
    override val name = "web_scrape"
    override val description = "Internal browser (no accessibility permission needed): load a web page and return its readable text (real Firefox via Termux bridge; falls back to a plain HTTP fetch without JavaScript). " +
        "To search the web, load https://html.duckduckgo.com/html/?q=<url-encoded query> (no consent wall, no JS needed) rather than Google."
    override val parameters = schema(listOf("url"),
        "url" to prop("string", "Absolute URL"),
        "selector" to prop("string", "Optional CSS selector to extract"),
        "html" to prop("boolean", "Return HTML instead of text (bridge only)"),
        "cloudflare" to prop("boolean", "Use TBP's Cloudflare-bypass navigation"))

    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return errorJson("url must be an absolute http(s) URL")
        val selector = args.str("selector")?.takeIf { it.isNotBlank() }
        if (bridge.isAvailable()) {
            return try {
                val nav = bridge.goto(url, args.bool("cloudflare") == true)
                if (!nav.ok) return errorJson("navigation failed: ${nav.errorMessage}")
                val content = if (args.bool("html") == true) bridge.html(selector) else bridge.text(selector)
                content.toToolJson { put("url", url); put("engine", "tbp-firefox") }
            } catch (e: IOException) { errorJson("bridge error: ${e.message}") }
        }
        return try {
            val page = fallback.scrape(url, selector, MAX_TEXT)
            buildJsonObject {
                put("ok", true); put("engine", "http-jsoup"); put("url", page.url); put("title", page.title)
                put("text", page.text)
                put("links", JsonArray(page.links.map { (t, h) -> buildJsonObject { put("text", t); put("href", h) } }))
                put("note", "Fetched without JavaScript (bridge unavailable); dynamic sites may be incomplete.")
            }.toString()
        } catch (e: Exception) { errorJson("fetch failed: ${e.message}") }
    }
}

class WebClickTool(private val bridge: BridgeClient) : AgentTool {
    override val name = "web_click"
    override val description = "Click an element on the current web page (human-like Bézier mouse movement). Internal browser (Firefox in Termux) — no accessibility permission needed."
    override val parameters = schema(listOf("selector"),
        "selector" to prop("string", "CSS selector of the element to click"),
        "human" to prop("boolean", "Human-like mouse path (default true)"))

    override suspend fun execute(args: JsonObject): String {
        val sel = args.str("selector")?.takeIf { it.isNotBlank() } ?: return errorJson("selector is required")
        if (!bridge.isAvailable()) return errorJson(BRIDGE_DOWN)
        return try { bridge.click(sel, args.bool("human") ?: true).toToolJson { put("selector", sel) } }
        catch (e: IOException) { errorJson("bridge error: ${e.message}") }
    }
}

class WebTypeTool(private val bridge: BridgeClient) : AgentTool {
    override val name = "web_type"
    override val description = "Type text into an element on the current web page with human typing delays. Internal browser (Firefox in Termux) — no accessibility permission needed."
    override val parameters = schema(listOf("selector", "text"),
        "selector" to prop("string", "CSS selector of the input"),
        "text" to prop("string", "Text to type"),
        "submit" to prop("boolean", "Press Enter afterwards"))

    override suspend fun execute(args: JsonObject): String {
        val sel = args.str("selector")?.takeIf { it.isNotBlank() } ?: return errorJson("selector is required")
        val text = args.str("text") ?: return errorJson("text is required")
        if (!bridge.isAvailable()) return errorJson(BRIDGE_DOWN)
        return try { bridge.type(sel, text, args.bool("submit") == true).toToolJson { put("selector", sel) } }
        catch (e: IOException) { errorJson("bridge error: ${e.message}") }
    }
}

class WebSessionTool(private val bridge: BridgeClient) : AgentTool {
    override val name = "web_session"
    override val description = "Save or load the browser's cookies as a named session (e.g. to stay logged in). Internal browser (Firefox in Termux) — no accessibility permission needed."
    override val parameters = schema(listOf("action"),
        "action" to prop("string", "save | load | list"),
        "name" to prop("string", "Session name, e.g. 'x' or 'facebook'"))

    override suspend fun execute(args: JsonObject): String {
        if (!bridge.isAvailable()) return errorJson(BRIDGE_DOWN)
        val name = args.str("name") ?: "default"
        return try {
            when (args.str("action")) {
                "save" -> bridge.saveCookies(name).toToolJson { put("session", name) }
                "load" -> bridge.loadCookies(name).toToolJson { put("session", name) }
                "list" -> bridge.listSessions().toToolJson()
                else -> errorJson("action must be save, load or list")
            }
        } catch (e: IOException) { errorJson("bridge error: ${e.message}") }
    }
}
