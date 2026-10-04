package com.farrow.app.data.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

open class McpException(message: String) : Exception(message)
class McpHttpException(val code: Int, message: String) : McpException(message)
class McpRpcException(val code: Int, message: String) : McpException(message)

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

/** tools/call result as text for the model; [isError] = the tool reported an error (isError: true). */
data class McpCallResult(val text: String, val isError: Boolean)

/**
 * Minimal remote MCP client (JSON-RPC 2.0): Streamable HTTP (POST, reply as JSON or as an SSE stream, Mcp-Session-Id)
 * with a fallback to the legacy HTTP+SSE transport (GET …/sse → "endpoint" event, POST requests there, replies on the
 * stream). No local stdio servers. initialize → notifications/initialized → tools/list → tools/call.
 */
class McpClient(
    val url: String,
    private val headers: Map<String, String> = emptyMap(),
    http: OkHttpClient = DEFAULT_HTTP,
    private val requestTimeoutMs: Long = 60_000,
) {
    enum class Transport { STREAMABLE_HTTP, SSE }

    private val http = http.newBuilder().callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val ids = AtomicLong(1)
    @Volatile var sessionId: String? = null; private set
    @Volatile var protocolVersion: String? = null; private set
    @Volatile var serverName: String? = null; private set
    @Volatile var transport: Transport? = null; private set

    // legacy SSE state
    @Volatile private var sseCall: Call? = null
    @Volatile private var sseEndpoint: String? = null
    private var endpointReady = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    suspend fun initialize(): JsonObject {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", "Farrow"); put("version", CLIENT_VERSION) })
        }
        val legacy = url.substringBefore('?').trimEnd('/').endsWith("/sse")
        val result = if (!legacy) {
            transport = Transport.STREAMABLE_HTTP
            try {
                request("initialize", params)
            } catch (e: McpHttpException) {
                if (e.code !in setOf(400, 404, 405)) throw e
                transport = Transport.SSE; openSse(); request("initialize", params)
            }
        } else {
            transport = Transport.SSE; openSse(); request("initialize", params)
        }
        protocolVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull
        serverName = (result["serverInfo"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        notify("notifications/initialized")
        return result
    }

    suspend fun listTools(): List<McpTool> {
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = request("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            (r["tools"] as? JsonArray)?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                out += McpTool(name, o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
            }
            cursor = r["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } while (cursor != null && ++pages < 20)
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val r = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        return McpCallResult(resultText(r), r["isError"]?.jsonPrimitive?.booleanOrNull == true)
    }

    fun close() {
        sseCall?.cancel(); sseCall = null
        pending.values.forEach { it.completeExceptionally(McpException("connection closed")) }
        pending.clear()
    }

    // ---------------- JSON-RPC ----------------

    suspend fun request(method: String, params: JsonObject? = null): JsonObject {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) }
        }
        val reply = withTimeout(requestTimeoutMs) {
            if (transport == Transport.SSE) sseRequest(id, body) else postRequest(id, body)
        }
        (reply["error"] as? JsonObject)?.let { e ->
            throw McpRpcException(e["code"]?.jsonPrimitive?.intOrNull ?: 0,
                "$method: " + (e["message"]?.jsonPrimitive?.contentOrNull ?: e.toString()))
        }
        return reply["result"] as? JsonObject ?: buildJsonObject { }
    }

    suspend fun notify(method: String) {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString()
        val target = if (transport == Transport.SSE) sseEndpoint ?: return else url
        runCatching { runInterruptible(Dispatchers.IO) { http.newCall(post(target, body)).execute().close() } }
    }

    private fun post(target: String, body: String): Request = Request.Builder().url(target)
        .post(body.toRequestBody(JSON))
        .header("Accept", "application/json, text/event-stream")
        .apply {
            sessionId?.let { header("Mcp-Session-Id", it) }
            protocolVersion?.let { header("MCP-Protocol-Version", it) }
            headers.forEach { (k, v) -> header(k, v) }
        }.build()

    private suspend fun postRequest(id: Long, body: JsonObject): JsonObject = runInterruptible(Dispatchers.IO) {
        http.newCall(post(url, body.toString())).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!resp.isSuccessful) {
                val txt = resp.body?.string().orEmpty().take(300)
                throw McpHttpException(resp.code, "HTTP ${resp.code}" + (if (resp.code == 401 || resp.code == 403) " (auth header/token missing or wrong)" else "") +
                    if (txt.isNotBlank()) ": $txt" else "")
            }
            val type = resp.header("Content-Type").orEmpty()
            if (type.contains("text/event-stream")) {
                val reader = resp.body?.charStream()?.buffered() ?: throw McpException("empty SSE reply")
                readSse(reader) { _, data -> matchReply(data, id) } ?: throw McpException("SSE stream ended without a reply to request $id")
            } else {
                val txt = resp.body?.string().orEmpty()
                if (txt.isBlank()) throw McpException("empty reply (HTTP ${resp.code})")
                matchReply(txt, id) ?: throw McpException("no reply with id $id in: ${txt.take(200)}")
            }
        }
    }

    // ---------------- legacy HTTP+SSE ----------------

    private suspend fun openSse() {
        if (sseCall != null) return
        endpointReady = CompletableDeferred()
        val req = Request.Builder().url(url).get().header("Accept", "text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = streamHttp.newCall(req)
        sseCall = call
        Thread({
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) { endpointReady.completeExceptionally(McpHttpException(resp.code, "SSE HTTP ${resp.code}")); return@use }
                    val reader = resp.body!!.charStream().buffered()
                    readSse(reader) { event, data ->
                        when (event) {
                            "endpoint" -> { val ep = resolve(data.trim()); sseEndpoint = ep; endpointReady.complete(ep) }
                            else -> runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()?.let { o ->
                                o["id"]?.jsonPrimitive?.longOrNull?.let { pending.remove(it)?.complete(o) }
                            }
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                endpointReady.completeExceptionally(McpException("SSE: ${e.message}"))
            } finally {
                pending.values.forEach { it.completeExceptionally(McpException("SSE stream closed")) }
                pending.clear()
                sseCall = null
            }
        }, "mcp-sse").apply { isDaemon = true }.start()
        withTimeout(minOf(requestTimeoutMs, 20_000)) { endpointReady.await() }
    }

    private fun resolve(ep: String): String = url.toHttpUrl().resolve(ep)?.toString() ?: ep

    private suspend fun sseRequest(id: Long, body: JsonObject): JsonObject {
        val ep = sseEndpoint ?: throw McpException("SSE endpoint unknown")
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        try {
            runInterruptible(Dispatchers.IO) {
                http.newCall(post(ep, body.toString())).execute().use { r ->
                    if (!r.isSuccessful) throw McpHttpException(r.code, "HTTP ${r.code}: ${r.body?.string().orEmpty().take(300)}")
                }
            }
            return d.await()
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_VERSION = "0.9.18"
        private val JSON = "application/json".toMediaType()
        val DEFAULT_HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        }

        /** Reads SSE events ("event:" + "data:" lines up to a blank line); stops when [onEvent] returns non-null. */
        fun <T> readSse(reader: BufferedReader, onEvent: (event: String, data: String) -> T?): T? {
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            onEvent(event, data.toString())?.let { return it }
                        }
                        event = "message"; data.setLength(0)
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> event = line.substringAfter(":").trim()
                    line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(":").removePrefix(" ")) }
                }
            }
            if (data.isNotEmpty()) return onEvent(event, data.toString())
            return null
        }

        /** The JSON-RPC response with [id] from a single message or a batch; null if absent. */
        fun matchReply(text: String, id: Long): JsonObject? {
            val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            val objs = when (el) { is JsonArray -> el.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(el); else -> emptyList() }
            return objs.firstOrNull { o -> o["id"]?.jsonPrimitive?.longOrNull == id && ("result" in o || "error" in o) }
        }

        /** tools/call result → text: text parts joined; images/resources summarised; structuredContent as JSON. */
        fun resultText(r: JsonObject, max: Int = 30_000): String {
            val parts = (r["content"] as? JsonArray)?.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                    "image", "audio" -> "[${o["type"]!!.jsonPrimitive.content} ${o["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""}, " +
                        "${o["data"]?.jsonPrimitive?.contentOrNull?.length ?: 0} base64 chars]"
                    "resource" -> (o["resource"] as? JsonObject)?.let { res -> res["text"]?.jsonPrimitive?.contentOrNull ?: "[resource ${res["uri"]?.jsonPrimitive?.contentOrNull}]" }
                    "resource_link" -> "[resource ${o["uri"]?.jsonPrimitive?.contentOrNull} ${o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()}]"
                    else -> o.toString()
                }
            }.orEmpty()
            val text = parts.joinToString("\n").ifBlank { r["structuredContent"]?.toString() ?: "" }
            return if (text.length > max) text.take(max) + "\n…[truncated ${text.length - max} chars]" else text
        }
    }
}
