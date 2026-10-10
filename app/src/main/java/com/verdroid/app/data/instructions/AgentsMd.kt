package com.verdroid.app.data.instructions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The user's AGENTS.md (Settings → Agent tools → AGENTS.md): standing instructions for the agent, stored in
 * app-internal `files/AGENTS.md` and added to every chat's system prompt. Empty by default (nothing is sent).
 */
class AgentsMdStore(private val file: File) {
    private val _text = MutableStateFlow(read())
    val text: StateFlow<String> = _text.asStateFlow()

    private fun read(): String = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")

    /** Saves [text] (trimmed, capped at [MAX_CHARS]); blank deletes the file. */
    @Synchronized
    fun save(text: String): String {
        val t = clean(text)
        if (t.isEmpty()) file.delete() else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(t)
            if (!tmp.renameTo(file)) { file.writeText(t); tmp.delete() }
        }
        _text.value = t
        return t
    }

    fun promptBlock(): String = promptBlock(_text.value)

    companion object {
        const val MAX_CHARS = 8000
        fun clean(text: String): String = text.replace("\r\n", "\n").trim().take(MAX_CHARS)

        /** System-prompt block for [text] ("" when blank). */
        fun promptBlock(text: String): String {
            val t = clean(text)
            return if (t.isEmpty()) "" else
                "User instructions (AGENTS.md, written by the user in Settings; follow them unless they conflict with " +
                    "safety rules above or the user's latest message):\n$t"
        }
    }
}
