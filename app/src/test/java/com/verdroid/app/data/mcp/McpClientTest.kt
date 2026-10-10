package com.verdroid.app.data.mcp

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** JSON-RPC client against an in-process fake MCP server (JDK HttpServer). */
class McpClientTest {
    private val server = MiniHttpServer()
    private val base get() = "http://127.0.0.1:${server.port}"
    private val seen = mutableListOf<String>()
    private val authSeen = mutableListOf<String?>()

    @After fun stop() = server.stop()

    /** Server logic shared by all transports: returns the JSON-RPC reply (null for notifications). */
    private fun handle(req: JsonObject): JsonObject? {
        val method = req["method"]!!.jsonPrimitive.content
        synchronized(seen) { seen += method }
        val id = req["id"] ?: return null
        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-03-26"); put("capabilities", buildJsonObject { })
                put("serverInfo", buildJsonObject { put("name", "Fake"); put("version", "1") })
            }
            "tools/list" -> if (req["params"]?.jsonObject?.get("cursor") == null) buildJsonObject {
                put("tools", buildJsonArray { add(buildJsonObject { put("name", "echo"); put("description", "Echo text")
                    put("inputSchema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) }) }) }) })
                put("nextCursor", "p2")
            } else buildJsonObject { put("tools", buildJsonArray { add(buildJsonObject { put("name", "fail") }) }) }
            "tools/call" -> {
                val p = req["params"]!!.jsonObject
                when (p["name"]!!.jsonPrimitive.content) {
                    "echo" -> buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "echo: " + p["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content) }) }) }
                    "fail" -> buildJsonObject { put("isError", true); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "boom") }) }) }
                    else -> return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32602); put("message", "Unknown tool") }) }
                }
            }
            else -> return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") }) }
        }
        return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
    }

    private fun streamable(sse: Boolean) {
        server.route("/mcp") { rq, ex ->
            if (rq.method != "POST") return@route ex.reply(405)
            val req = Json.parseToJsonElement(rq.body).jsonObject
            synchronized(authSeen) { authSeen += rq.header("Authorization") }
            val isInit = req["method"]?.jsonPrimitive?.content == "initialize"
            if (!isInit && rq.header("Mcp-Session-Id") != "S1") return@route ex.reply(400, """{"error":"missing session"}""")
            val hdr = if (isInit) mapOf("Mcp-Session-Id" to "S1") else emptyMap()
            val r = handle(req) ?: return@route ex.reply(202, headers = hdr)
            if (sse) ex.reply(200, ": ping\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: $r\n\n", "text/event-stream", hdr)
            else ex.reply(200, r.toString(), headers = hdr)
        }
        server.start()
    }

    private suspend fun exercise(c: McpClient) {
        val init = c.initialize()
        assertEquals("2025-03-26", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("Fake", c.serverName)
        val tools = c.listTools()
        assertEquals(listOf("echo", "fail"), tools.map { it.name })
        assertEquals("object", tools[0].inputSchema["type"]!!.jsonPrimitive.content)
        assertEquals(McpCallResult("echo: hi", false), c.callTool("echo", buildJsonObject { put("text", "hi") }))
        assertEquals(McpCallResult("boom", true), c.callTool("fail", buildJsonObject { }))
        val e = runCatching { c.callTool("nope", buildJsonObject { }) }.exceptionOrNull()
        assertTrue(e.toString(), e is McpRpcException && e.code == -32602 && e.message!!.contains("Unknown tool"))
    }

    @Test fun `streamable HTTP with JSON replies and session id`() = runBlocking {
        streamable(sse = false)
        val c = McpClient("$base/mcp", mapOf("Authorization" to "Bearer t0k"))
        exercise(c)
        assertEquals(McpClient.Transport.STREAMABLE_HTTP, c.transport)
        assertEquals("S1", c.sessionId)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call", "tools/call", "tools/call"), seen)
        assertTrue(authSeen.all { it == "Bearer t0k" })
    }

    @Test fun `streamable HTTP with SSE replies`() = runBlocking {
        streamable(sse = true)
        exercise(McpClient("$base/mcp"))
    }

    @Test fun `legacy HTTP+SSE transport`() = runBlocking {
        val out = LinkedBlockingQueue<String>()
        server.route("/sse") { _, ex ->
            ex.startStream()
            ex.event("event: endpoint\ndata: /messages?sessionId=abc\n\n")
            while (true) {
                val m = out.poll(10, TimeUnit.SECONDS) ?: break
                ex.event("event: message\ndata: $m\n\n")
            }
        }
        server.route("/messages") { rq, ex ->
            assertEquals("sessionId=abc", rq.query)
            handle(Json.parseToJsonElement(rq.body).jsonObject)?.let { out.put(it.toString()) }
            ex.reply(202)
        }
        server.start()
        val c = McpClient("$base/sse", requestTimeoutMs = 10_000)
        try { exercise(c) } finally { c.close() }
        assertEquals(McpClient.Transport.SSE, c.transport)
    }

    @Test fun `HTTP errors are reported with the status`() = runBlocking {
        server.route("/mcp") { _, ex -> ex.reply(401, "nope") }
        server.start()
        val e = runCatching { McpClient("$base/mcp").initialize() }.exceptionOrNull()
        assertTrue(e.toString(), e is McpHttpException && e.code == 401 && e.message!!.contains("auth"))
    }

    @Test fun `timeouts are enforced`() = runBlocking {
        server.route("/mcp") { _, ex -> Thread.sleep(3_000); ex.reply(200, "{}") }
        server.start()
        val t0 = System.currentTimeMillis()
        val e = runCatching { McpClient("$base/mcp", requestTimeoutMs = 800).initialize() }.exceptionOrNull()
        assertNotNull(e)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_500)
    }

    @Test fun `sse parsing, batch matching and result text`() {
        val r = McpClient.readSse("event: x\ndata: a\ndata: b\n\ndata: {\"id\":3}\n\n".reader().buffered()) { ev, d -> if (ev == "message") d else null }
        assertEquals("{\"id\":3}", r)
        assertNotNull(McpClient.matchReply("""[{"jsonrpc":"2.0","method":"n"},{"jsonrpc":"2.0","id":7,"result":{}}]""", 7))
        assertNull(McpClient.matchReply("""{"jsonrpc":"2.0","id":8,"result":{}}""", 7))
        val text = McpClient.resultText(Json.parseToJsonElement("""{"content":[{"type":"text","text":"one"},{"type":"image","mimeType":"image/png","data":"AAAA"},{"type":"resource","resource":{"uri":"u","text":"res"}}]}""").jsonObject)
        assertEquals("one\n[image image/png, 4 base64 chars]\nres", text)
        assertEquals("""{"a":1}""", McpClient.resultText(Json.parseToJsonElement("""{"content":[],"structuredContent":{"a":1}}""").jsonObject))
    }

    @Test fun `exposed names and auth header`() {
        assertEquals("mcp__deepwiki__ask_wiki_question", McpNaming.toolName("DeepWiki", "ask_wiki_question"))
        assertEquals("mcp__microsoft_learn__microsoft_docs_search", McpNaming.toolName("Microsoft Learn", "microsoft_docs_search"))
        assertEquals("mcp__context7__resolve-library-id", McpNaming.toolName("Context7", "resolve-library-id"))
        val long = McpNaming.toolName("A very long server name indeed", "x".repeat(80))
        assertTrue(long.length <= 64 && long.matches(Regex("[a-zA-Z0-9_-]+")))
        assertEquals("Bearer abc", McpNaming.headerValue("Authorization", "abc"))
        assertEquals("Token abc", McpNaming.headerValue("Authorization", "Token abc"))
        assertEquals("abc", McpNaming.headerValue("X-API-Key", "abc"))
        assertTrue(McpNaming.EXAMPLES.none { it.enabled })
    }

    @Test fun `registry exposes dynamic MCP tools with their schema and honours switches`() = runBlocking {
        val dyn = object : AgentTool {
            override val name = "mcp__fake__echo"; override val description = "[MCP Fake] Echo"
            override val parameters = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject) = """{"result":"ok"}"""
        }
        var off = false
        val reg = ToolRegistry(emptyList(), { name -> !(off && name == dyn.name) }, dynamic = { listOf(dyn) })
        assertTrue(reg.schemas().toString().contains("mcp__fake__echo"))
        assertEquals("""{"result":"ok"}""", reg.execute("mcp__fake__echo", "{}").json)
        off = true
        assertFalse(reg.names.contains("mcp__fake__echo"))
        assertTrue(reg.execute("mcp__fake__echo", "{}").isError)
    }
}
