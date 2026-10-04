package com.farrow.app.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    /** Image data URLs to send with this (user) message; sent as OpenAI content parts by [ApiMessageWire]. */
    val images: List<String>? = null,
)

/**
 * Wire format of [ApiMessage]: a message with [ApiMessage.images] becomes
 * `content: [{type:text,text}, {type:image_url,image_url:{url}}…]`; the `images` field itself never goes out.
 */
object ApiMessageWire : JsonTransformingSerializer<ApiMessage>(ApiMessage.serializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return element
        val imgs = o["images"] as? JsonArray
        val rest = o.filterKeys { it != "images" }
        if (imgs.isNullOrEmpty()) return JsonObject(rest)
        val parts = buildJsonArray {
            (o["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { t ->
                add(buildJsonObject { put("type", "text"); put("text", t) })
            }
            imgs.forEach { u -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", u) }) }) }
        }
        return JsonObject(rest + ("content" to parts))
    }
}

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<@Serializable(with = ApiMessageWire::class) ApiMessage>,
    val tools: JsonArray? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)
