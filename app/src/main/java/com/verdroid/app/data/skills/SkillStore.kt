package com.verdroid.app.data.skills

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A reusable procedure the agent saved (files/skills/<id>/SKILL.md). */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

/**
 * Agent-writable skills in app-internal storage: `files/skills/<id>/SKILL.md` with a small front matter
 * (`name`, `description`, `enabled`) and a Markdown body. Only an index (name + one-line description) of enabled skills
 * goes into the system prompt ([promptBlock]); bodies are loaded on demand with skill_get. Disabled skills are never sent.
 */
class SkillStore(root: File) {
    val root: File = root.apply { mkdirs() }.canonicalFile
    private val lock = Any()
    private val _skills = MutableStateFlow(load())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun list(): List<Skill> = _skills.value

    fun get(id: String): Skill? = list().firstOrNull { it.id == id.trim().lowercase() }

    /** Creates a skill; the id is a slug of [name] (suffixed -2, -3… when [overwrite] is false and it exists). */
    fun save(name: String, description: String, body: String, overwrite: Boolean = false): Skill = synchronized(lock) {
        val n = clean(name, MAX_NAME).ifEmpty { throw IllegalArgumentException("name is required") }
        val d = clean(description, MAX_DESCRIPTION).ifEmpty {
            throw IllegalArgumentException("description is required: one short line (what it does, when to use it), shown in the prompt index")
        }
        val b = body.trim().ifEmpty { throw IllegalArgumentException("body is required") }
        require(b.length <= MAX_BODY) { "body is longer than $MAX_BODY characters" }
        require(overwrite || list().size < MAX_SKILLS) { "too many skills (max $MAX_SKILLS); delete or edit one" }
        val base = slug(n)
        var id = base
        if (!overwrite) { var i = 2; while (dirOf(id).exists()) id = "$base-${i++}" }
        val prev = if (overwrite) read(id) else null
        val s = Skill(id, n, d, b, prev?.enabled ?: true, System.currentTimeMillis())
        write(s); refresh(); s
    }

    /** Updates fields; [find]/[replace] edits the body in place (exactly one occurrence required). */
    fun edit(id: String, name: String? = null, description: String? = null, body: String? = null,
             find: String? = null, replace: String? = null): Skill = synchronized(lock) {
        val cur = get(id) ?: throw NoSuchElementException("No skill '$id'")
        var b = body?.trim()?.ifEmpty { throw IllegalArgumentException("body is empty") } ?: cur.body
        if (find != null) {
            require(find.isNotEmpty()) { "find is empty" }
            val count = Regex(Regex.escape(find)).findAll(b).count()
            require(count == 1) { if (count == 0) "find text not found in the skill" else "find text occurs $count times; make it unique" }
            b = b.replace(find, replace ?: "")
        }
        require(b.length <= MAX_BODY) { "body is longer than $MAX_BODY characters" }
        val s = cur.copy(
            name = name?.let { clean(it, MAX_NAME) }?.ifEmpty { null } ?: cur.name,
            description = description?.let { clean(it, MAX_DESCRIPTION).ifEmpty { throw IllegalArgumentException("description can't be empty") } } ?: cur.description,
            body = b, updatedAt = System.currentTimeMillis())
        write(s); refresh(); s
    }

    fun setEnabled(id: String, on: Boolean): Skill? = synchronized(lock) {
        val cur = get(id) ?: return null
        cur.copy(enabled = on).also { write(it); refresh() }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val ok = get(id)?.let { dirOf(it.id).deleteRecursively() } ?: false
        refresh(); ok
    }

    /**
     * System-prompt block: only an index of enabled skills — id, name and one-line description each. The full SKILL.md
     * stays on disk and is loaded with skill_get. Empty when nothing is enabled; capped at [maxChars].
     */
    fun promptBlock(maxChars: Int = DEFAULT_PROMPT_CHARS): String {
        val on = list().filter { it.enabled }
        if (on.isEmpty()) return ""
        val sb = StringBuilder("## Saved skills (enabled)\nIndex only — call skill_get with the id to load the full steps before following a skill.\n")
        var shown = 0
        for (s in on) {
            val line = "- ${s.id}: ${s.name} — ${s.description.ifBlank { "(no description)" }.take(INDEX_DESCRIPTION)}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line); shown++
        }
        if (shown < on.size) sb.append("(${on.size - shown} more — see skill_list)\n")
        return sb.toString().trimEnd()
    }

    fun refresh() { _skills.value = load() }

    private fun dirOf(id: String): File {
        require(ID.matches(id)) { "invalid skill id '$id'" }
        return File(root, id)
    }

    private fun load(): List<Skill> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && ID.matches(it.name) }
        .mapNotNull { read(it.name) }
        .sortedBy { it.name.lowercase() }

    private fun read(id: String): Skill? {
        val f = File(dirOf(id), FILE)
        if (!f.isFile) return null
        return runCatching { parse(id, f.readText(), f.lastModified()) }.getOrNull()
    }

    private fun write(s: Skill) {
        val dir = dirOf(s.id).apply { mkdirs() }
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(format(s))
        if (!tmp.renameTo(File(dir, FILE))) { File(dir, FILE).writeText(format(s)); tmp.delete() }
    }

    companion object {
        const val FILE = "SKILL.md"
        const val MAX_NAME = 80
        /** Descriptions are one short line (the prompt index shows up to [INDEX_DESCRIPTION] chars). */
        const val MAX_DESCRIPTION = 160
        const val INDEX_DESCRIPTION = 160
        const val MAX_BODY = 20_000
        const val MAX_SKILLS = 100
        const val DEFAULT_PROMPT_CHARS = 4_000
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).trim('-').ifEmpty { "skill" }

        private fun clean(s: String, max: Int) = s.replace(Regex("[\\r\\n]+"), " ").trim().take(max)

        fun format(s: Skill): String = "---\nname: ${s.name}\ndescription: ${s.description}\nenabled: ${s.enabled}\n---\n\n${s.body.trim()}\n"

        fun parse(id: String, text: String, mtime: Long): Skill {
            var name = id; var desc = ""; var enabled = true; var body = text
            if (text.startsWith("---\n")) {
                val end = text.indexOf("\n---", 4)
                if (end > 0) {
                    text.substring(4, end).lineSequence().forEach { line ->
                        val k = line.substringBefore(':').trim(); val v = line.substringAfter(':', "").trim()
                        when (k) { "name" -> if (v.isNotEmpty()) name = v; "description" -> desc = v; "enabled" -> enabled = v != "false" }
                    }
                    body = text.substring(end + 4).trim()
                }
            }
            return Skill(id, name, desc, body.trim(), enabled, mtime)
        }
    }
}
