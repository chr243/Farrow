package com.verdroid.app.agent.tools

import com.verdroid.app.data.mcp.MiniHttpServer
import com.verdroid.app.data.websearch.HttpReply
import com.verdroid.app.data.websearch.WebSearcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WebSearchToolTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/websearch/$name")!!.readText()
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    @Test fun `web_search returns fused results, engine status and honours site`() = runBlocking {
        var seenQuery = ""
        val searcher = WebSearcher(fetch = { c ->
            if ("html.duckduckgo.com" in c.url) { seenQuery = c.form!!["q"]!!; HttpReply(200, fixture("ddg_html.html")) }
            else if ("search.brave.com" in c.url) HttpReply(200, fixture("brave.html"))
            else HttpReply(500, "")
        })
        val o = Json.parseToJsonElement(WebSearchTool(searcher).execute(buildJsonObject {
            put("query", "kotlin coroutines"); put("site", "https://kotlinlang.org/"); put("max_results", 3); put("detail", "detailed")
        })).jsonObject
        assertEquals("kotlin coroutines site:kotlinlang.org", seenQuery)
        assertTrue(o["ok"]!!.jsonPrimitive.boolean)
        val results = o["results"]!!.jsonArray
        assertEquals(3, results.size)
        val top = results[0].jsonObject
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", top["url"]!!.jsonPrimitive.content)
        assertEquals(setOf("duckduckgo", "brave"), top["engines"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(o["engines"]!!.jsonObject["bing"]!!.jsonPrimitive.content.startsWith("error"))
        assertTrue(WebSearchTool(searcher).execute(buildJsonObject { put("query", " ") }).contains("query is required"))
        assertTrue(WebSearchTool(searcher).execute(buildJsonObject { put("query", "x"); put("freshness", "soon") }).contains("freshness"))
    }

    @Test fun `web_fetch markdown pages a document, caches it and fences it`() = runBlocking {
        var hits = 0
        val para = (1..40).joinToString("") { "<p>Paragraph $it ${"lorem ".repeat(30)}</p>" }
        server.route("/article") { _, res -> hits++; res.reply(200, "<html><head><title>T</title></head><body><nav>menu</nav><main>$para</main></body></html>", "text/html") }
        server.start()
        val url = "http://127.0.0.1:${server.port}/article"
        val tool = WebFetchTool()
        val p1 = Json.parseToJsonElement(tool.execute(buildJsonObject { put("url", url); put("format", "markdown"); put("page_size_tokens", 500) })).jsonObject
        assertEquals("T", p1["title"]!!.jsonPrimitive.content)
        assertTrue(p1["has_more"]!!.jsonPrimitive.boolean)
        assertEquals("live", p1["source"]!!.jsonPrimitive.content)
        val content = p1["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("UNTRUSTED DATA") && content.contains("Paragraph 1 ") && !content.contains("menu"))
        val total = p1["total_pages"]!!.jsonPrimitive.int
        val last = Json.parseToJsonElement(tool.execute(buildJsonObject { put("url", url); put("format", "markdown"); put("page_size_tokens", 500); put("page", total) })).jsonObject
        assertEquals("cache", last["source"]!!.jsonPrimitive.content)
        assertFalse(last["has_more"]!!.jsonPrimitive.boolean)
        assertTrue(last["content"]!!.jsonPrimitive.content.contains("Paragraph 40 "))
        assertEquals(1, hits)
        assertTrue(tool.execute(buildJsonObject { put("url", url); put("format", "pdf") }).contains("format must be"))
    }
}
