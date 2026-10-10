package com.verdroid.app.agent.tools

import com.verdroid.app.data.memory.MemoryStore
import com.verdroid.app.data.memory.MemoryText
import kotlinx.serialization.json.*

private fun memJson(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block).toString()

class MemorySaveTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_save"
    override val description = "Save to memory. scope=\"global\" (default, long-term): a lasting fact about the user or their preferences, " +
        "kept across chats. scope=\"chat\" (short-term): this chat's scratchpad for task progress, decisions and findings, always " +
        "shown in this chat's prompt and deleted with the chat. One short self-contained sentence; no secrets. Duplicates are merged."
    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("text", buildJsonObject { put("type", "string"); put("description", "The fact, e.g. 'The user prefers answers in French.'") })
            put("tags", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                put("description", "Optional tags, e.g. [\"language\"]; add \"important\" to always keep it in the prompt") })
            put("scope", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("global"); add("chat") })
                put("description", "global = long-term (default), chat = short-term for the current task") })
        })
        put("required", buildJsonArray { add("text") })
    }

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return errorJson("text is required")
        val tags = MemoryText.tags((args["tags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: args["tags"]?.jsonPrimitive?.contentOrNull)
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: MemoryText.SCOPE_GLOBAL
        val chatId = when (scope) {
            MemoryText.SCOPE_GLOBAL, "long-term", "long_term" -> null
            MemoryText.SCOPE_CHAT, "short-term", "short_term" -> ctx.taskId.takeIf { it > 0 } ?: return errorJson("scope=chat needs a chat (none active)")
            else -> return errorJson("scope must be \"global\" or \"chat\"")
        }
        val r = store.save(text, tags, chatId)
        return memJson {
            put("ok", true); put("id", r.id); put("scope", if (chatId == null) MemoryText.SCOPE_GLOBAL else MemoryText.SCOPE_CHAT)
            if (r.duplicateOf != null) put("note", "Already remembered as #${r.duplicateOf}; updated it instead of adding a duplicate.")
        }
    }
}

class MemorySearchTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_search"
    override val description = "Search memory by words or tags: long-term facts/preferences and this chat's short-term notes; each hit is labelled with its scope. Empty query = most recent."
    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject { put("type", "string") })
            put("limit", buildJsonObject { put("type", "integer"); put("description", "Max results (default 10)") })
        })
    }

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val q = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
        val all = store.all()
        val pool = MemoryText.global(all) + MemoryText.ofChat(all, ctx.taskId.takeIf { it > 0 })
        val hits = MemoryText.search(pool, q, limit)
        return memJson {
            put("count", hits.size)
            put("memories", JsonArray(hits.map { m -> buildJsonObject {
                put("id", m.id); put("scope", m.scope); put("text", m.text); put("tags", JsonArray(m.tags.map { JsonPrimitive(it) }))
            } }))
        }
    }
}

class MemoryDeleteTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_delete"
    override val description = "Delete a memory (long-term or short-term) by id (from the prompt's memory lists or memory_search), e.g. when a fact is outdated."
    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("id", buildJsonObject { put("type", "integer") }) })
        put("required", buildJsonArray { add("id") })
    }

    override suspend fun execute(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.toLongOrNull() ?: return errorJson("id is required")
        return if (store.delete(id)) memJson { put("ok", true); put("deleted", id) } else errorJson("No memory with id $id")
    }
}
