package com.verdroid.app.agent.tools

import com.verdroid.app.data.mcp.MiniHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WebFetchToolTest {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    @Test fun `GET returns status body and truncates`() = runBlocking {
        server.route("/hello") { _, res -> res.reply(200, "x".repeat(50), "text/plain") }
        server.start()
        val r = Json.parseToJsonElement(WebFetchTool().execute(buildJsonObject {
            put("url", "http://127.0.0.1:${server.port}/hello"); put("max_bytes", 10)
        })).jsonObject
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, r["status"]!!.jsonPrimitive.int)
        assertEquals(true, r["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(10, r["body"]!!.jsonPrimitive.content.length)
    }
}
