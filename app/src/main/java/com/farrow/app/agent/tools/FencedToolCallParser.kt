package com.farrow.app.agent.tools

import com.farrow.app.data.network.ParsedToolCall
import kotlinx.serialization.json.*

/**
 * Fallback for free models that ignore native tool calling and instead write e.g.
 * ```json
 * {"tool": "write_file", "arguments": {"path": "a.txt", "content": "hi"}}
 * ```
 * Accepts {"tool"|"name"|"function": ..., "arguments"|"args"|"parameters": {...} or "json string"}.
 */
object FencedToolCallParser {
    private val fence = Regex("```[ \\t]*(?:json|tool_call|tool|javascript)?[ \\t]*\\r?\\n?(\\{[\\s\\S]*?\\})[ \\t]*\\r?\\n?```", RegexOption.IGNORE_CASE)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(content: String?, knownTools: Set<String>): List<ParsedToolCall> {
        if (content.isNullOrBlank()) return emptyList()
        return fence.findAll(content).mapIndexedNotNull { i, m ->
            val obj = runCatching { json.parseToJsonElement(m.groupValues[1]) as? JsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val inner = (obj["tool_call"] as? JsonObject) ?: obj
            val fnObj = inner["function"] as? JsonObject
            val name = fnObj?.get("name")?.prim() ?: inner["tool"]?.prim() ?: inner["name"]?.prim() ?: inner["function"]?.prim()
                ?: return@mapIndexedNotNull null
            if (name !in knownTools) return@mapIndexedNotNull null
            val argsEl = fnObj?.get("arguments") ?: inner["arguments"] ?: inner["args"] ?: inner["parameters"]
            val args = when (argsEl) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> argsEl.contentOrNull ?: "{}"
                else -> argsEl.toString()
            }
            ParsedToolCall("fenced_${System.currentTimeMillis()}_$i", name, args)
        }.toList()
    }

    private fun JsonElement.prim(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
