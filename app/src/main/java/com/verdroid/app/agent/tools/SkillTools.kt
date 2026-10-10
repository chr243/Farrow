package com.verdroid.app.agent.tools

import com.verdroid.app.data.skills.Skill
import com.verdroid.app.data.skills.SkillStore
import kotlinx.serialization.json.*

private fun Skill.summary(): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("description", description); put("enabled", enabled); put("chars", body.length)
}

private inline fun skillCall(block: () -> String): String = try { block() }
    catch (e: IllegalArgumentException) { errorJson(e.message ?: "invalid input") }
    catch (e: NoSuchElementException) { errorJson(e.message ?: "not found") }

class SkillListTool(private val store: SkillStore) : AgentTool {
    override val name = "skill_list"
    override val description = "List saved skills (reusable procedures) with id, name, description and whether they are enabled."
    override val parameters = schema(emptyList())
    override suspend fun execute(args: JsonObject): String = buildJsonObject {
        putJsonArray("skills") { store.list().forEach { add(it.summary()) } }
    }.toString()
}

class SkillGetTool(private val store: SkillStore) : AgentTool {
    override val name = "skill_get"
    override val description = "Load the full SKILL.md of a saved skill by id. The prompt only lists skill names and descriptions — call this before following a skill."
    override val parameters = schema(listOf("id"), "id" to prop("string", "Skill id from skill_list"))
    override suspend fun execute(args: JsonObject): String {
        val s = store.get(args.str("id") ?: return errorJson("id is required")) ?: return errorJson("No skill '${args.str("id")}'")
        return JsonObject(s.summary() + ("body" to JsonPrimitive(s.body))).toString()
    }
}

class SkillSaveTool(private val store: SkillStore) : AgentTool {
    override val name = "skill_save"
    override val description = "Save a new skill: a reusable multi-step procedure worked out with the user (only after they agree). " +
        "description is required: one short line (≤160 chars) — only name + description go into the prompt index. " +
        "Body is Markdown: when to use it, numbered steps, tools/commands, pitfalls. Saved enabled."
    override val parameters = schema(listOf("name", "description", "body"),
        "name" to prop("string", "Short name, e.g. 'Weekly crypto report'"),
        "description" to prop("string", "Required. One short line (≤160 chars): what it does and when to use it — shown in the prompt index"),
        "body" to prop("string", "Markdown instructions (max 20000 chars)"))
    override suspend fun execute(args: JsonObject): String = skillCall {
        val s = store.save(args.str("name") ?: "", args.str("description") ?: "", args.str("body") ?: "")
        JsonObject(mapOf("ok" to JsonPrimitive(true)) + s.summary()).toString()
    }
}

class SkillEditTool(private val store: SkillStore) : AgentTool {
    override val name = "skill_edit"
    override val description = "Edit a saved skill: replace name/description/body, or change part of the body with find + replace (find must occur once). " +
        "Keep the one-line description accurate (add one if the skill has none)."
    override val parameters = schema(listOf("id"),
        "id" to prop("string", "Skill id"),
        "name" to prop("string", "New name"),
        "description" to prop("string", "New one-line description (≤160 chars, shown in the prompt index; can't be empty)"),
        "body" to prop("string", "New full body"),
        "find" to prop("string", "Exact text in the body to replace"),
        "replace" to prop("string", "Replacement for find"))
    override suspend fun execute(args: JsonObject): String = skillCall {
        val s = store.edit(args.str("id") ?: return errorJson("id is required"), args.str("name"), args.str("description"),
            args.str("body"), args.str("find"), args.str("replace"))
        JsonObject(mapOf("ok" to JsonPrimitive(true)) + s.summary()).toString()
    }
}

class SkillDeleteTool(private val store: SkillStore) : AgentTool {
    override val name = "skill_delete"
    override val description = "Delete a saved skill by id (only when the user asks)."
    override val parameters = schema(listOf("id"), "id" to prop("string", "Skill id"))
    override suspend fun execute(args: JsonObject): String {
        val id = args.str("id") ?: return errorJson("id is required")
        return if (store.delete(id)) buildJsonObject { put("ok", true); put("deleted", id) }.toString() else errorJson("No skill '$id'")
    }
}
