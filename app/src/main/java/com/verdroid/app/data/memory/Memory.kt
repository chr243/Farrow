package com.verdroid.app.data.memory

/**
 * A memory. [chatId] null = long-term (global: lasting facts about the user and their preferences);
 * otherwise the short-term scratchpad of that chat (task progress, decisions, findings), deleted with the chat.
 */
data class Memory(val id: Long, val text: String, val tags: List<String>, val createdAt: Long, val updatedAt: Long, val chatId: Long? = null) {
    val scope: String get() = if (chatId == null) MemoryText.SCOPE_GLOBAL else MemoryText.SCOPE_CHAT
}

/** Result of a save: the id and whether an existing (duplicate) memory was updated instead of adding one. */
data class SaveResult(val id: Long, val duplicateOf: Long?)

/** Storage behind the memory tools and the Memory screen (Room in the app, a list in tests). */
interface MemoryStore {
    suspend fun all(): List<Memory>
    /** [chatId] null = long-term; else that chat's short-term memory. Duplicates are merged within the same scope. */
    suspend fun save(text: String, tags: List<String>, chatId: Long? = null): SaveResult
    suspend fun update(id: Long, text: String, tags: List<String>): Boolean
    suspend fun delete(id: Long): Boolean
    /** [chatId] null = clear all long-term memories; else clear that chat's short-term memory. */
    suspend fun clear(chatId: Long? = null)
    /** Moves a memory to another scope (e.g. short-term → long-term with [chatId] null). */
    suspend fun move(id: Long, chatId: Long?): Boolean
    /** Automatic saving (Settings > Memory): off = save only when the user explicitly asks. */
    val autoSave: Boolean
}

/** Pure helpers: normalisation, duplicate detection, search ranking, the system-prompt block and the export file. */
object MemoryText {
    const val MAX_TEXT = 500
    const val PROMPT_ITEMS = 20
    /** ~1.5k tokens at ~4 chars per token. */
    const val PROMPT_CHARS = 6_000
    /** Short-term (per chat): ~1k tokens, oldest trimmed. */
    const val CHAT_PROMPT_CHARS = 4_000
    const val SCOPE_CHAT = "chat"
    const val SCOPE_GLOBAL = "global"

    fun global(all: List<Memory>) = all.filter { it.chatId == null }
    fun ofChat(all: List<Memory>, chatId: Long?) = if (chatId == null) emptyList() else all.filter { it.chatId == chatId }

    /** Short-term notes for the prompt: the newest that fit in [CHAT_PROMPT_CHARS], shown oldest → newest. */
    fun chatForPrompt(items: List<Memory>): List<Memory> {
        val out = mutableListOf<Memory>(); var chars = 0
        for (m in items.sortedWith(compareByDescending<Memory> { it.createdAt }.thenByDescending { it.id })) {
            val len = line(m).length + 1
            if (chars + len > CHAT_PROMPT_CHARS) break
            out += m; chars += len
        }
        return out.reversed()
    }

    fun normalize(t: String): String = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun tags(raw: Any?): List<String> = when (raw) {
        is String -> raw.split(',', ';', '#')
        is List<*> -> raw.map { it.toString() }
        else -> emptyList()
    }.map { it.trim().lowercase().replace(Regex("\\s+"), "-") }.filter { it.isNotBlank() }.distinct().take(8)

    fun tagsString(tags: List<String>) = tags.joinToString(",")
    fun parseTags(s: String) = s.split(',').map { it.trim() }.filter { it.isNotBlank() }

    /** Same fact: equal after normalisation, or one contains the other and they're of similar length. */
    fun isDuplicate(a: String, b: String): Boolean {
        val na = normalize(a); val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val (s, l) = if (na.length <= nb.length) na to nb else nb to na
        return l.contains(s) && s.length >= l.length * 0.8
    }

    /** Duplicate in the same scope ([chatId] null = long-term). */
    fun findDuplicate(existing: List<Memory>, text: String, chatId: Long? = null): Memory? =
        existing.firstOrNull { it.chatId == chatId && isDuplicate(it.text, text) }

    /** Memories matching any word of [query] (text or tags), best matches first; blank query = most recent. */
    fun search(all: List<Memory>, query: String, limit: Int = 20): List<Memory> {
        val words = normalize(query).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return all.sortedByDescending { it.updatedAt }.take(limit)
        return all.map { m ->
            val hay = normalize(m.text + " " + m.tags.joinToString(" "))
            m to words.count { hay.contains(it) } * 10 + (if (hay.contains(normalize(query))) 5 else 0)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(limit).map { it.first }
    }

    /** Memories for the prompt: "important"/"pinned" tags first, then most recent; ≤ [PROMPT_ITEMS] and ≤ [PROMPT_CHARS]. */
    fun forPrompt(all: List<Memory>): List<Memory> {
        val ordered = all.sortedWith(compareByDescending<Memory> { m -> m.tags.any { it == "important" || it == "pinned" } }
            .thenByDescending { it.updatedAt })
        val out = mutableListOf<Memory>(); var chars = 0
        for (m in ordered) {
            if (out.size >= PROMPT_ITEMS) break
            val line = line(m)
            if (chars + line.length > PROMPT_CHARS) continue
            out += m; chars += line.length + 1
        }
        return out
    }

    private fun line(m: Memory) = "- [#${m.id}] ${m.text}" + if (m.tags.isEmpty()) "" else " (${m.tags.joinToString()})"

    /** System-prompt block: instructions for both tiers + the long-term selection + this chat's short-term notes. */
    fun promptBlock(all: List<Memory>, autoSave: Boolean, chatId: Long? = null): String = buildString {
        val global = global(all)
        appendLine("Memory (tools memory_save, memory_search, memory_delete) has two tiers:")
        appendLine("- Short-term (scope=\"chat\"): this chat's scratchpad. Save task progress, decisions and things you figured out " +
            "(e.g. which approach worked, IDs, partial results) so you can pick up where you left off. It is shown below in every " +
            "turn of this chat and deleted with the chat. Keep the current task there: as soon as the user gives you a task, " +
            "announces what you'll work on together (even with details still TBD) or changes it, save one line " +
            "\"Task: <goal, key constraints; TBD for unknown details>\" in that same reply (scope=\"chat\", tags [\"task\"]; " +
            "delete the outdated task note first, update it when details arrive), and follow it until it's done. " +
            "Skip this only for a single question you fully answer in this reply.")
        if (autoSave) appendLine("- Long-term (scope=\"global\", the default): lasting facts about the user and their preferences (name, language, " +
            "accounts, recurring tasks, style, things to avoid), kept across all chats. Save them as one short self-contained sentence; " +
            "no one-off task details, secrets or passwords. Check the list below (or memory_search) first and never save a duplicate; " +
            "if a fact changed, delete the old memory and save the new one.")
        else appendLine("- Long-term (scope=\"global\"): automatic saving is off — save long-term memories only when the user explicitly " +
            "asks you to remember something. Short-term notes are fine.")
        val sel = forPrompt(global)
        if (sel.isEmpty()) appendLine("Long-term memories: none yet.")
        else {
            appendLine("Long-term memories (${sel.size} of ${global.size}; memory_search for more):")
            appendLine(sel.joinToString("\n") { line(it) })
        }
        val chat = ofChat(all, chatId)
        if (chatId != null) {
            val cs = chatForPrompt(chat)
            if (cs.isEmpty()) append("Short-term memory of this chat: empty.")
            else {
                appendLine("Short-term memory of this chat" + (if (cs.size < chat.size) " (newest ${cs.size} of ${chat.size}; older ones trimmed)" else "") + ":")
                append(cs.joinToString("\n") { line(it) })
            }
        }
    }.trimEnd()

    /** Readable export (files/memory/MEMORY.md): long-term first, then each chat's short-term notes. */
    fun markdown(all: List<Memory>, fmt: (Long) -> String): String = buildString {
        fun item(m: Memory) {
            append("- **#${m.id}** ").append(m.text)
            if (m.tags.isNotEmpty()) append("  `").append(m.tags.joinToString(", ")).append('`')
            appendLine("  _(updated ${fmt(m.updatedAt)})_")
        }
        val global = global(all)
        appendLine("# Verdroid memory")
        appendLine()
        appendLine("Edit in the app (Settings > Memory); this file is rewritten on every change.")
        appendLine()
        appendLine("## Long-term (${global.size})")
        appendLine()
        global.sortedByDescending { it.updatedAt }.forEach(::item)
        all.filter { it.chatId != null }.groupBy { it.chatId!! }.toSortedMap().forEach { (chat, items) ->
            appendLine()
            appendLine("## Short-term — chat $chat (${items.size})")
            appendLine()
            items.sortedBy { it.createdAt }.forEach(::item)
        }
    }
}
