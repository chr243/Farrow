package com.verdroid.app.data.memory

import com.verdroid.app.agent.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure memory logic + the three tools against an in-memory store. */
class MemoryTest {
    private class FakeStore(override var autoSave: Boolean = true) : MemoryStore {
        val items = mutableListOf<Memory>(); var next = 1L; var clock = 1_000L
        override suspend fun all() = items.toList()
        override suspend fun save(text: String, tags: List<String>, chatId: Long?): SaveResult {
            val now = clock++
            MemoryText.findDuplicate(items, text, chatId)?.let { d ->
                items[items.indexOf(d)] = d.copy(tags = (d.tags + tags).distinct(), updatedAt = now); return SaveResult(d.id, d.id)
            }
            val m = Memory(next++, text, tags, now, now, chatId); items += m; return SaveResult(m.id, null)
        }
        override suspend fun move(id: Long, chatId: Long?) = items.indexOfFirst { it.id == id }.let { i ->
            if (i < 0) false else { items[i] = items[i].copy(chatId = chatId); true } }
        override suspend fun update(id: Long, text: String, tags: List<String>) = items.indexOfFirst { it.id == id }.let { i ->
            if (i < 0) false else { items[i] = items[i].copy(text = text, tags = tags, updatedAt = clock++); true } }
        override suspend fun delete(id: Long) = items.removeAll { it.id == id }
        override suspend fun clear(chatId: Long?) { items.removeAll { it.chatId == chatId } }
    }

    private fun mem(id: Long, text: String, tags: List<String> = emptyList(), t: Long = id, chat: Long? = null) = Memory(id, text, tags, t, t, chat)

    @Test fun `duplicates and tags`() {
        assertTrue(MemoryText.isDuplicate("The user prefers French.", "the user prefers french"))
        assertTrue(MemoryText.isDuplicate("User lives in Paris", "User lives in Paris."))
        assertFalse(MemoryText.isDuplicate("User lives in Paris", "User lives in Paris and works at a bank in Lyon on Mondays"))
        assertFalse(MemoryText.isDuplicate("Likes tea", "Likes coffee"))
        assertEquals(listOf("language", "x-account"), MemoryText.tags("Language, X account,language"))
        assertEquals(listOf("a", "b"), MemoryText.tags(listOf("A", " b ")))
    }

    @Test fun `search ranks by matching words and blank query returns recent`() {
        val all = listOf(mem(1, "Prefers answers in French", listOf("language")), mem(2, "X handle is @chris"), mem(3, "Likes French cheese and wine"))
        assertEquals(listOf(1L, 3L), MemoryText.search(all, "french language").map { it.id })
        assertEquals(listOf(2L), MemoryText.search(all, "handle").map { it.id })
        assertEquals(listOf(3L, 2L, 1L), MemoryText.search(all, "").map { it.id })
        assertTrue(MemoryText.search(all, "zzz").isEmpty())
    }

    @Test fun `prompt keeps important first and caps items and size`() {
        val many = (1L..40L).map { mem(it, "Fact number $it about the user") } + mem(100, "Name is Chris", listOf("important"), t = 0)
        val sel = MemoryText.forPrompt(many)
        assertEquals(MemoryText.PROMPT_ITEMS, sel.size)
        assertEquals(100L, sel.first().id)
        assertEquals(40L, sel[1].id)
        val big = (1L..30L).map { mem(it, "x".repeat(450) + it) }
        val bs = MemoryText.forPrompt(big)
        assertTrue(bs.sumOf { it.text.length } <= MemoryText.PROMPT_CHARS)
        val block = MemoryText.promptBlock(many, autoSave = true)
        assertTrue(block.contains("memory_save") && block.contains("never save a duplicate") && block.contains("[#100] Name is Chris"))
        assertTrue(block.length < MemoryText.PROMPT_CHARS + 1_500)
        assertTrue(MemoryText.promptBlock(emptyList(), autoSave = false).contains("only when the user explicitly asks"))
    }

    @Test fun `markdown export lists every memory`() {
        val md = MemoryText.markdown(listOf(mem(1, "A", listOf("t")), mem(2, "B")), { "d$it" })
        assertTrue(md.startsWith("# Farrow memory") && md.contains("**#1** A") && md.contains("`t`") && md.indexOf("#2") < md.indexOf("#1**"))
        val md2 = MemoryText.markdown(listOf(mem(1, "A"), mem(2, "note", chat = 7)), { "d" })
        assertTrue(md2, md2.contains("## Long-term (1)") && md2.contains("## Short-term — chat 7 (1)") && md2.indexOf("**#2**") > md2.indexOf("chat 7"))
    }

    @Test fun `tools save without duplicates, search and delete`() = runTest {
        val store = FakeStore()
        val save = MemorySaveTool(store); val search = MemorySearchTool(store); val del = MemoryDeleteTool(store)
        val r1 = Json.parseToJsonElement(save.execute(buildJsonObject { put("text", "The user prefers French."); put("tags", buildJsonArray { add("language") }) })).jsonObject
        assertEquals(1L, r1["id"]!!.jsonPrimitive.long)
        val r2 = Json.parseToJsonElement(save.execute(buildJsonObject { put("text", "the user prefers french") })).jsonObject
        assertTrue(r2["note"]!!.jsonPrimitive.content.contains("Already remembered as #1"))
        assertEquals(1, store.items.size)
        assertTrue(save.execute(buildJsonObject { put("text", " ") }).contains("error"))
        val s = Json.parseToJsonElement(search.execute(buildJsonObject { put("query", "french") })).jsonObject
        assertEquals(1, s["count"]!!.jsonPrimitive.int)
        assertTrue(del.execute(buildJsonObject { put("id", "#1") }).contains("\"deleted\":1"))
        assertTrue(del.execute(buildJsonObject { put("id", 1) }).contains("No memory"))
        assertTrue(store.items.isEmpty())
    }

    @Test fun `short-term notes are per chat, trimmed oldest first and shown in that chat's prompt only`() {
        val notes = (1L..60L).map { mem(it, "Step $it: " + "detail ".repeat(10), chat = 5) }
        val all = notes + mem(100, "Name is Chris") + mem(200, "Other chat note", chat = 6)
        val sel = MemoryText.chatForPrompt(MemoryText.ofChat(all, 5))
        assertTrue(sel.size in 10 until 60)
        assertEquals(60L, sel.last().id)                  // newest kept
        assertEquals(sel.sortedBy { it.createdAt }, sel)  // shown oldest → newest
        assertTrue(sel.sumOf { it.text.length } <= MemoryText.CHAT_PROMPT_CHARS)
        val block = MemoryText.promptBlock(all, autoSave = true, chatId = 5)
        assertTrue(block.contains("Short-term (scope=\"chat\")") && block.contains("Long-term (scope=\"global\""))
        assertTrue(block.contains("[#100] Name is Chris") && block.contains("[#60] Step 60") && block.contains("older ones trimmed"))
        assertFalse(block.contains("Other chat note")); assertFalse(block.contains("[#1] Step 1:"))
        assertTrue(MemoryText.promptBlock(all, autoSave = true, chatId = 9).contains("Short-term memory of this chat: empty."))
        assertFalse(MemoryText.promptBlock(all, autoSave = true, chatId = null).contains("Step 60"))
        // Duplicates only within the same scope.
        assertNull(MemoryText.findDuplicate(all, "Name is Chris", chatId = 5))
        assertEquals(100L, MemoryText.findDuplicate(all, "name is chris.", chatId = null)?.id)
    }

    @Test fun `scoped save, search labels the scope and hides other chats, move to long-term`() = runTest {
        val store = FakeStore()
        val save = MemorySaveTool(store); val search = MemorySearchTool(store)
        val ctx = ToolContext(5)
        save.execute(buildJsonObject { put("text", "User likes French news") }, ctx)
        val r = Json.parseToJsonElement(save.execute(buildJsonObject { put("text", "Found the French news feed at lemonde.fr"); put("scope", "chat") }, ctx)).jsonObject
        assertEquals("chat", r["scope"]!!.jsonPrimitive.content)
        save.execute(buildJsonObject { put("text", "French draft for chat 6"); put("scope", "chat") }, ToolContext(6))
        assertEquals(5L, store.items[1].chatId); assertNull(store.items[0].chatId)
        assertTrue(save.execute(buildJsonObject { put("text", "x"); put("scope", "chat") }).contains("needs a chat"))
        assertTrue(save.execute(buildJsonObject { put("text", "x"); put("scope", "weird") }, ctx).contains("scope must be"))
        val hits = Json.parseToJsonElement(search.execute(buildJsonObject { put("query", "french") }, ctx)).jsonObject["memories"]!!.jsonArray
        assertEquals(setOf("global", "chat"), hits.map { it.jsonObject["scope"]!!.jsonPrimitive.content }.toSet())
        assertFalse(hits.toString().contains("chat 6"))
        assertTrue(store.move(store.items[1].id, null)); assertNull(store.items[1].chatId)
    }

    @Test fun `room migration 3 to 4 adds chatId and matches the exported schema`() {
        val python3 = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { java.io.File(it).canExecute() }
        org.junit.Assume.assumeTrue(python3 != null)
        val sqls = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, a ->
            if (m.name == "execSQL") sqls += a!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        com.verdroid.app.data.local.MIGRATION_3_4.migrate(db)
        assertEquals(2, sqls.size)
        val dir = listOf("schemas", "app/schemas").map { java.io.File(it, "com.verdroid.app.data.local.VerdroidDatabase") }.first { it.exists() }
        fun entity(v: Int) = Json.parseToJsonElement(java.io.File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "memories" }
        fun lit(t: String) = JsonPrimitive(t.replace("`\${TABLE_NAME}`", "`memories`")).toString()
        val v3 = entity(3); val v4 = entity(4)
        val idx3 = v3["indices"]!!.jsonArray.map { lit(it.jsonObject["createSql"]!!.jsonPrimitive.content) }
        val idx4 = v4["indices"]!!.jsonArray.map { lit(it.jsonObject["createSql"]!!.jsonPrimitive.content) }
        val script = java.io.File.createTempFile("mig", ".py")
        script.writeText(listOf(
            "import sqlite3",
            "a = sqlite3.connect(':memory:'); b = sqlite3.connect(':memory:')",
            "a.execute(${lit(v3["createSql"]!!.jsonPrimitive.content)})",
            "for s in [${idx3.joinToString(",")}]: a.execute(s)",
            "a.execute(\"INSERT INTO memories (text, tags, createdAt, updatedAt) VALUES ('kept', '', 1, 1)\")",
            "for s in [${sqls.joinToString(",") { lit(it) }}]: a.execute(s)",
            "b.execute(${lit(v4["createSql"]!!.jsonPrimitive.content)})",
            "for s in [${idx4.joinToString(",")}]: b.execute(s)",
            "cols = lambda c: [tuple(r[1:]) for r in c.execute('PRAGMA table_info(memories)')]",
            "assert cols(a) == cols(b), (cols(a), cols(b))",
            "ix = lambda c: sorted(r[1] for r in c.execute('PRAGMA index_list(memories)'))",
            "assert ix(a) == ix(b), (ix(a), ix(b))",
            "assert a.execute('SELECT text, chatId FROM memories').fetchall() == [('kept', None)]",
            "print('MIGRATION OK')",
        ).joinToString("\n"))
        val p = ProcessBuilder(python3, script.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        assertTrue(out, out.contains("MIGRATION OK"))
    }
}
