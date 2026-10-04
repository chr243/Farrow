package com.farrow.app.data.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.farrow.app.agent.tools.AgentTool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    /** Header carrying the secret (value is stored encrypted, never here). */
    val authHeaderName: String = "Authorization",
    val example: Boolean = false,
)

@Serializable
data class CachedTool(val name: String, val description: String = "", val inputSchema: JsonObject = JsonObject(emptyMap()))

enum class McpState { OFF, CONNECTING, CONNECTED, ERROR }

data class McpServerStatus(val state: McpState, val error: String? = null, val tools: List<McpTool> = emptyList(), val serverName: String? = null, val transport: String? = null)

/** Name exposed to the model: mcp__<server>__<tool>, [a-zA-Z0-9_-], ≤ 64 chars (OpenAI/OpenRouter limit). */
object McpNaming {
    fun slug(s: String): String = s.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { "server" }

    fun toolName(server: String, tool: String): String {
        val t = tool.replace(Regex("[^a-zA-Z0-9_-]+"), "_")
        val full = "mcp__${slug(server).take(20)}__$t"
        if (full.length <= 64) return full
        val hash = Integer.toHexString((server + "/" + tool).hashCode()).take(6)
        return full.take(57) + "_" + hash
    }

    /** Verified (initialize + tools/list from curl, 2026-10-03), public, no key. Off by default. */
    val EXAMPLES = listOf(
        McpServerConfig("ex-deepwiki", "DeepWiki", "https://mcp.deepwiki.com/mcp", example = true),
        McpServerConfig("ex-context7", "Context7", "https://mcp.context7.com/mcp", example = true),
        McpServerConfig("ex-mslearn", "Microsoft Learn", "https://learn.microsoft.com/api/mcp", example = true),
        McpServerConfig("ex-huggingface", "Hugging Face", "https://huggingface.co/mcp", example = true),
    )

    /** "abc" for Authorization → "Bearer abc"; anything with a space (e.g. "Bearer x", "Token x") or another header as is. */
    fun headerValue(headerName: String, secret: String): String {
        val s = secret.trim()
        return if (headerName.equals("Authorization", ignoreCase = true) && !s.contains(' ')) "Bearer $s" else s
    }
}

/** One MCP tool as an agent tool. */
class McpAgentTool(private val manager: McpManager, val serverId: String, serverName: String, val tool: McpTool) : AgentTool {
    override val name = McpNaming.toolName(serverName, tool.name)
    override val description = "[MCP $serverName] " + tool.description.ifBlank { tool.name }.take(1_000)
    override val parameters: JsonObject = tool.inputSchema.let { s ->
        if (s["type"] == null) JsonObject(s + ("type" to JsonPrimitive("object"))) else s
    }

    override suspend fun execute(args: JsonObject): String {
        val r = manager.call(serverId, tool.name, args)
        return if (r.isError) buildJsonObject { put("error", "MCP tool ${tool.name} failed: ${r.text.take(4_000)}") }.toString()
        else buildJsonObject { put("result", r.text) }.toString()
    }
}

/**
 * Remote MCP servers (Settings > Tools > MCP servers): config in SharedPreferences, auth secrets in
 * EncryptedSharedPreferences, last tools/list cached so the model sees the tools right after app start.
 */
@Singleton
class McpManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val secrets: SharedPreferences? by lazy {
        runCatching {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "mcp_secrets", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableMapOf<String, McpClient>()
    private val mutex = Mutex()

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val _status = MutableStateFlow(_servers.value.associate { s ->
        s.id to McpServerStatus(if (s.enabled) McpState.CONNECTING else McpState.OFF, tools = cached(s.id))
    })
    val status: StateFlow<Map<String, McpServerStatus>> = _status.asStateFlow()

    init { _servers.value.filter { it.enabled }.forEach { s -> scope.launch { connect(s.id) } } }

    private fun loadServers(): List<McpServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null)
        return if (raw == null) McpNaming.EXAMPLES.also { save(it) }
        else runCatching { json.decodeFromString<List<McpServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(list: List<McpServerConfig>) { prefs.edit().putString(KEY_SERVERS, json.encodeToString(list)).apply() }

    private fun cached(id: String): List<McpTool> = runCatching {
        json.decodeFromString<List<CachedTool>>(prefs.getString("tools_$id", null) ?: return emptyList()).map { McpTool(it.name, it.description, it.inputSchema) }
    }.getOrDefault(emptyList())

    fun hasSecret(id: String): Boolean = !secrets?.getString(id, null).isNullOrBlank()

    /** Add (id null) or edit a server. [secret]: null = keep, "" = remove. */
    fun upsert(id: String?, name: String, url: String, headerName: String, secret: String?, enabled: Boolean): McpServerConfig {
        val cfg = McpServerConfig(id ?: UUID.randomUUID().toString(), name.trim(), url.trim(), enabled, headerName.trim().ifBlank { "Authorization" })
        secret?.let { s -> secrets?.edit()?.apply { if (s.isBlank()) remove(cfg.id) else putString(cfg.id, s.trim()) }?.apply() }
        val ordered = if (_servers.value.any { it.id == cfg.id }) _servers.value.map { if (it.id == cfg.id) cfg else it } else _servers.value + cfg
        _servers.value = ordered; save(ordered)
        disconnect(cfg.id)
        if (cfg.enabled) scope.launch { connect(cfg.id) } else setStatus(cfg.id, McpServerStatus(McpState.OFF, tools = cached(cfg.id)))
        return cfg
    }

    fun delete(id: String) {
        val next = _servers.value.filter { it.id != id }
        _servers.value = next; save(next)
        secrets?.edit()?.remove(id)?.apply()
        prefs.edit().remove("tools_$id").apply()
        disconnect(id)
        _status.update { it - id }
    }

    fun setEnabled(id: String, on: Boolean) {
        val s = _servers.value.firstOrNull { it.id == id } ?: return
        val next = _servers.value.map { if (it.id == id) s.copy(enabled = on) else it }
        _servers.value = next; save(next)
        if (on) scope.launch { connect(id) } else { disconnect(id); setStatus(id, McpServerStatus(McpState.OFF, tools = cached(id))) }
    }

    fun refresh(id: String) { scope.launch { disconnect(id); connect(id) } }

    private fun disconnect(id: String) { synchronized(clients) { clients.remove(id) }?.close() }

    private fun setStatus(id: String, st: McpServerStatus) = _status.update { it + (id to st) }

    private fun newClient(s: McpServerConfig): McpClient {
        val secret = secrets?.getString(s.id, null)
        val headers = if (secret.isNullOrBlank()) emptyMap() else mapOf(s.authHeaderName to McpNaming.headerValue(s.authHeaderName, secret))
        return McpClient(s.url, headers, requestTimeoutMs = CALL_TIMEOUT_MS)
    }

    /** initialize + tools/list (20 s). Returns the client or null (status = ERROR with the message). */
    suspend fun connect(id: String): McpClient? = mutex.withLock {
        synchronized(clients) { clients[id] }?.let { return it }
        val s = _servers.value.firstOrNull { it.id == id } ?: return null
        setStatus(id, McpServerStatus(McpState.CONNECTING, tools = cached(id)))
        val c = newClient(s)
        try {
            val tools = withTimeout(CONNECT_TIMEOUT_MS) { c.initialize(); c.listTools() }
            prefs.edit().putString("tools_$id", json.encodeToString(tools.map { CachedTool(it.name, it.description, it.inputSchema) })).apply()
            synchronized(clients) { clients[id] = c }
            setStatus(id, McpServerStatus(McpState.CONNECTED, tools = tools, serverName = c.serverName, transport = c.transport?.name))
            c
        } catch (e: kotlinx.coroutines.CancellationException) {
            c.close()
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                setStatus(id, McpServerStatus(McpState.ERROR, "Timed out after ${CONNECT_TIMEOUT_MS / 1000} s (${s.url})", cached(id))); null
            } else throw e
        } catch (e: Exception) {
            c.close()
            setStatus(id, McpServerStatus(McpState.ERROR, "${e.message ?: e.javaClass.simpleName} (${s.url})", cached(id)))
            null
        }
    }

    /** tools/call with one reconnect when the session expired (HTTP 404) or the connection dropped. */
    suspend fun call(serverId: String, tool: String, args: JsonObject): McpCallResult {
        val s = _servers.value.firstOrNull { it.id == serverId } ?: return McpCallResult("MCP server was removed", true)
        if (!s.enabled) return McpCallResult("MCP server ${s.name} is turned off (Settings > Tools)", true)
        repeat(2) { attempt ->
            val c = connect(serverId) ?: return McpCallResult("MCP server ${s.name} is not reachable: ${_status.value[serverId]?.error}", true)
            try {
                return withTimeout(CALL_TIMEOUT_MS) { c.callTool(tool, args) }
            } catch (e: McpRpcException) {
                return McpCallResult(e.message ?: "JSON-RPC error ${e.code}", true)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                return McpCallResult("MCP call $tool timed out after ${CALL_TIMEOUT_MS / 1000} s", true)
            } catch (e: Exception) {
                disconnect(serverId)
                if (attempt == 1) return McpCallResult("MCP call $tool failed: ${e.message}", true)
            }
        }
        return McpCallResult("MCP call $tool failed", true)
    }

    /** Agent tools of the enabled servers (per-tool on/off is the normal tool switch on the exposed name). */
    fun agentTools(): List<AgentTool> = _servers.value.filter { it.enabled }.flatMap { s ->
        val tools = _status.value[s.id]?.tools?.takeIf { it.isNotEmpty() } ?: cached(s.id)
        tools.map { McpAgentTool(this, s.id, s.name, it) }
    }.distinctBy { it.name }

    companion object {
        private const val KEY_SERVERS = "servers"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val CALL_TIMEOUT_MS = 90_000L
    }
}
