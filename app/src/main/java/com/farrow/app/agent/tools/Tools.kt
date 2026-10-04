package com.farrow.app.agent.tools

import kotlinx.serialization.json.*

/** Per-call context handed to tools (e.g. so a tool can pause its task on session expiry). */
data class ToolContext(val taskId: Long)

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    /** Returns a JSON string result. Errors are returned as {"error": "..."} rather than thrown. */
    suspend fun execute(args: JsonObject): String

    /** Context-aware variant; defaults to [execute]. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): String = execute(args)
}

data class ToolResult(val json: String, val isError: Boolean)

internal fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(props.toMap()))
    put("required", JsonArray(required.map { JsonPrimitive(it) }))
}

internal fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type); put("description", description)
}

internal fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

class ReadFileTool(private val sandbox: WorkspaceSandbox) : AgentTool {
    override val name = "read_file"
    override val description = "Read a UTF-8 text file from the agent workspace."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "File path relative to the workspace root"),
        "max_bytes" to prop("integer", "Maximum bytes to return (default 65536)"))

    override suspend fun execute(args: JsonObject): String {
        val f = sandbox.resolve(args.str("path") ?: return errorJson("path is required"))
        if (!f.exists()) return errorJson("File not found: ${sandbox.relativePath(f)}")
        if (f.isDirectory) return errorJson("Is a directory: ${sandbox.relativePath(f)}")
        val max = (args.int("max_bytes") ?: 65_536).coerceIn(1, 1_000_000)
        val bytes = f.readBytes()
        return buildJsonObject {
            put("path", sandbox.relativePath(f))
            put("size", bytes.size)
            put("truncated", bytes.size > max)
            put("content", String(bytes, 0, minOf(bytes.size, max), Charsets.UTF_8))
        }.toString()
    }
}

class WriteFileTool(private val sandbox: WorkspaceSandbox) : AgentTool {
    override val name = "write_file"
    override val description = "Create or overwrite (or append to) a text file in the agent workspace. Parent folders are created."
    override val parameters = schema(listOf("path", "content"),
        "path" to prop("string", "File path relative to the workspace root"),
        "content" to prop("string", "Text content to write"),
        "append" to prop("boolean", "Append instead of overwrite (default false)"))

    override suspend fun execute(args: JsonObject): String {
        val f = sandbox.resolve(args.str("path") ?: return errorJson("path is required"))
        if (f == sandbox.root || f.isDirectory) return errorJson("Path is a directory")
        val content = args.str("content") ?: return errorJson("content is required")
        f.parentFile?.mkdirs()
        if (args.bool("append") == true) f.appendText(content) else f.writeText(content)
        return buildJsonObject {
            put("ok", true); put("path", sandbox.relativePath(f)); put("bytes", f.length())
        }.toString()
    }
}

class ListDirTool(private val sandbox: WorkspaceSandbox) : AgentTool {
    override val name = "list_dir"
    override val description = "List files and folders in a workspace directory."
    override val parameters = schema(emptyList(), "path" to prop("string", "Directory relative to the workspace root (default '.')"))

    override suspend fun execute(args: JsonObject): String {
        val dir = sandbox.resolve(args.str("path") ?: ".")
        if (!dir.exists()) return errorJson("Directory not found: ${sandbox.relativePath(dir)}")
        if (!dir.isDirectory) return errorJson("Not a directory: ${sandbox.relativePath(dir)}")
        val entries = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name })).take(500)
        return buildJsonObject {
            put("path", sandbox.relativePath(dir))
            put("entries", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("name", e.name)
                    put("type", if (e.isDirectory) "dir" else "file")
                    if (e.isFile) put("size", e.length())
                }
            }))
        }.toString()
    }
}

/** Registered with a real schema so models learn the API, but not implemented until a later phase. */
class StubTool(override val name: String, override val description: String, override val parameters: JsonObject) : AgentTool {
    override suspend fun execute(args: JsonObject): String = NOT_AVAILABLE

    companion object {
        val NOT_AVAILABLE = errorJson("not available yet (coming in a later phase)")

        /** Phase 6: every tool is implemented now; kept so later experimental tools can be stubbed again. */
        fun all(): List<AgentTool> = emptyList()
    }
}

/**
 * All tools + the user's on/off switches (Settings > Tools). Disabled tools are left out of [schemas] / [names] (so the
 * model never sees them) and refused by [execute] if called anyway.
 */
class ToolRegistry(val tools: List<AgentTool>, private val switches: com.farrow.app.data.tools.ToolSwitches =
    com.farrow.app.data.tools.ToolSwitches.ALL_ON,
    /** Tools that come and go at runtime (remote MCP servers: mcp__<server>__<tool>). */
    private val dynamic: () -> List<AgentTool> = { emptyList() }) {
    private val staticByName = tools.associateBy { it.name }
    private val allTools: List<AgentTool> get() = tools + dynamic().filter { it.name !in staticByName }
    val enabledTools: List<AgentTool> get() = allTools.filter { switches.isEnabled(it.name) }
    val names: Set<String> get() = enabledTools.map { it.name }.toSet()

    fun schemas(): JsonArray = JsonArray(enabledTools.map { t ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", t.name); put("description", t.description); put("parameters", t.parameters)
            })
        }
    })

    suspend fun execute(name: String, argumentsJson: String, taskId: Long = 0L): ToolResult {
        val tool = staticByName[name] ?: dynamic().firstOrNull { it.name == name } ?: return ToolResult(errorJson("Unknown tool: $name"), true)
        if (!switches.isEnabled(name)) return ToolResult(errorJson("The tool $name is turned off by the user (Settings > Tools). Do not call it; use another approach or tell the user."), true)
        val args = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return ToolResult(errorJson("Arguments must be a JSON object"), true)
        return try {
            val out = tool.execute(args, ToolContext(taskId))
            ToolResult(out, out.trimStart().startsWith("{\"error\""))
        } catch (e: SecurityException) {
            ToolResult(errorJson(e.message ?: "Access denied"), true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(errorJson("${e.javaClass.simpleName}: ${e.message}"), true)
        }
    }
}
