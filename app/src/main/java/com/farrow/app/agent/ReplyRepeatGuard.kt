package com.farrow.app.agent

import com.farrow.app.data.social.StatusUrl
import com.farrow.app.domain.model.ToolCallRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.0.6 loop protection: x_reply on the same post more than [MAX_PER_POST] times since the user's last message stops
 * the task and reports to the user (a phone run kept looping on one reply).
 */
object ReplyRepeatGuard {
    const val MAX_PER_POST = 2
    const val TOOL = "x_reply"

    /** Canonical post URL of an x_reply call (or the raw url), null when it has none / isn't x_reply reply mode. */
    fun key(name: String, argumentsJson: String): String? {
        if (name != TOOL) return null
        val o = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull() ?: return null
        if ((o["mode"]?.jsonPrimitive?.contentOrNull ?: "reply").lowercase() != "reply") return null
        val raw = o["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return StatusUrl.canonical(raw)?.let { "x:" + it.id } ?: raw
    }

    /** Earlier x_reply calls on [key] started at/after [sinceMs] (the latest user message). */
    fun previous(records: List<ToolCallRecord>, key: String, sinceMs: Long): Int =
        records.count { it.startedAt >= sinceMs && key(it.name, it.argumentsJson) == key }

    fun blocked(previous: Int): Boolean = previous >= MAX_PER_POST

    fun message(url: String, previous: Int) =
        "Stopped: x_reply was already called $previous times for $url in this task without success. Not trying again — " +
            "report the last x_reply error to the user and wait for instructions (do not use web_click/web_type)."
}
