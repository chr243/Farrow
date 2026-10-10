package com.verdroid.app.data.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-chat tool presets (chat ⋮ menu → Tool presets). Each preset is a category of tools the user can switch off for
 * one chat; everything is on by default. Tools outside every preset (memory, skills, charts, crypto, MCP) are never
 * affected here — they follow Settings > Tools only.
 */
enum class ToolPreset(val label: String, val description: String) {
    WEB("Web", "web_search, web_fetch, headless Chromium (selenium_*)"),
    FILES("Files", "Documents/Verdroid (workspace_*), private scratch files, PDFs, ebook translation"),
    TERMUX("Termux", "termux_run, termux_python"),
    DEVICE("Device", "Shizuku shell, rish, screen control (accessibility), Git");

    companion object {
        /** The preset a tool belongs to, or null for tools no preset controls. */
        fun of(tool: String): ToolPreset? = when {
            tool in WEB_TOOLS || tool.startsWith("selenium_") -> WEB
            tool in FILE_TOOLS || tool.startsWith("workspace_") || tool.startsWith("pdf_") -> FILES
            tool == "termux_run" || tool == "termux_python" -> TERMUX
            tool in DEVICE_TOOLS || tool.startsWith("screen_") || tool.startsWith("git_") -> DEVICE
            else -> null
        }

        /** True unless [tool] belongs to a preset in [off]. */
        fun allowed(tool: String, off: Set<ToolPreset>): Boolean = off.isEmpty() || of(tool)?.let { it !in off } ?: true

        /** System-prompt note for a chat with some presets off ("" when all are on). */
        fun promptNote(off: Set<ToolPreset>): String = if (off.isEmpty()) "" else
            "Tool presets for this chat: the user turned off " + entries.filter { it in off }.joinToString { it.label } +
                " (chat menu → Tool presets). Those tools are not available in this chat; don't try to reach the same thing " +
                "another way (e.g. a shell or another tool). If the task needs them, tell the user which preset to turn on."

        fun encode(off: Set<ToolPreset>): Set<String> = off.map { it.name }.toSet()
        fun decode(raw: Set<String>?): Set<ToolPreset> = raw.orEmpty().mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()

        private val WEB_TOOLS = setOf("web_search", "web_fetch")
        private val FILE_TOOLS = setOf("read_file", "write_file", "list_dir", "ebook_translate")
        private val DEVICE_TOOLS = setOf("run_shell", "rish_run")
    }
}

/** What [com.verdroid.app.agent.tools.ToolRegistry] needs for per-chat presets (testable without Android). */
fun interface ChatToolFilter {
    fun allowed(taskId: Long, tool: String): Boolean

    companion object { val ALL = ChatToolFilter { _, _ -> true } }
}

/** Persists the presets each chat turned off (only "off" is stored, so a new chat = all on). */
@Singleton
class ChatToolPresets @Inject constructor(@ApplicationContext context: Context) : ChatToolFilter {
    private val prefs = context.getSharedPreferences("chat_tool_presets", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<Long, Set<ToolPreset>>>(load())

    private fun load(): Map<Long, Set<ToolPreset>> = prefs.all.mapNotNull { (k, v) ->
        val id = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        id to ToolPreset.decode(v as? Set<String>)
    }.filter { it.second.isNotEmpty() }.toMap()

    fun off(taskId: Long): Set<ToolPreset> = state.value[taskId].orEmpty()
    fun observe(taskId: Long): Flow<Set<ToolPreset>> = state.map { it[taskId].orEmpty() }

    override fun allowed(taskId: Long, tool: String): Boolean = ToolPreset.allowed(tool, off(taskId))

    fun setEnabled(taskId: Long, preset: ToolPreset, enabled: Boolean) =
        save(taskId, if (enabled) off(taskId) - preset else off(taskId) + preset)

    /** "All" switch: everything on, or every preset off. */
    fun setAll(taskId: Long, enabled: Boolean) = save(taskId, if (enabled) emptySet() else ToolPreset.entries.toSet())

    @Synchronized
    private fun save(taskId: Long, off: Set<ToolPreset>) {
        if (taskId == 0L) return
        val e = prefs.edit()
        if (off.isEmpty()) e.remove(KEY_PREFIX + taskId) else e.putStringSet(KEY_PREFIX + taskId, ToolPreset.encode(off))
        e.apply()
        state.value = if (off.isEmpty()) state.value - taskId else state.value + (taskId to off)
    }

    private companion object { const val KEY_PREFIX = "off_" }
}
