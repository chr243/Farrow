package com.farrow.app.data.skills

import com.farrow.app.agent.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillStoreTest {
    private lateinit var dir: File
    private lateinit var store: SkillStore
    @Before fun setUp() { dir = Files.createTempDirectory("skills").toFile(); store = SkillStore(File(dir, "skills")) }
    @After fun tearDown() { dir.deleteRecursively() }
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun saveWritesSkillMdAndReloads() {
        val s = store.save("Weekly Crypto Report!", "Line one\nline two", "1. a\n2. b")
        assertEquals("weekly-crypto-report", s.id)
        val f = File(dir, "skills/weekly-crypto-report/SKILL.md")
        assertTrue(f.readText().startsWith("---\nname: Weekly Crypto Report!\ndescription: Line one line two\nenabled: true\n---"))
        val again = SkillStore(File(dir, "skills")).get("weekly-crypto-report")!!
        assertEquals("1. a\n2. b", again.body); assertTrue(again.enabled)
        assertEquals("weekly-crypto-report-2", store.save("weekly crypto report", "", "x").id)
        assertThrows(IllegalArgumentException::class.java) { store.save(" ", "", "x") }
        assertThrows(IllegalArgumentException::class.java) { store.save("n", "", " ") }
    }

    @Test fun editEnableDelete() {
        val id = store.save("Deploy", "", "step one\nstep two").id
        assertEquals("step ONE\nstep two", store.edit(id, find = "one", replace = "ONE").body)
        assertThrows(IllegalArgumentException::class.java) { store.edit(id, find = "step", replace = "x") } // ambiguous
        assertThrows(IllegalArgumentException::class.java) { store.edit(id, find = "nope", replace = "x") }
        assertThrows(NoSuchElementException::class.java) { store.edit("missing", body = "x") }
        assertEquals("New", store.edit(id, name = "New").name)
        assertFalse(store.setEnabled(id, false)!!.enabled)
        assertFalse(SkillStore(File(dir, "skills")).get(id)!!.enabled) // persisted
        assertTrue(store.edit(id, body = "b2").let { !it.enabled }) // edit keeps the toggle
        assertTrue(store.delete(id)); assertFalse(File(dir, "skills/$id").exists()); assertFalse(store.delete(id))
        assertFalse(store.delete("../../etc")); assertNull(store.get("../x"))
    }

    @Test fun promptBlockSkipsDisabledAndRespectsBudget() {
        assertEquals("", store.promptBlock())
        store.save("A", "first", "body-a")
        val b = store.save("B", "second", "body-b " + "x".repeat(500))
        val c = store.save("C", "", "body-c")
        store.setEnabled(c.id, false)
        val p = store.promptBlock()
        assertTrue(p.contains("- a: A — first") && p.contains("body-a") && p.contains("body-b"))
        assertFalse(p.contains("body-c") || p.contains("- c:"))
        val small = store.promptBlock(maxChars = 300)
        assertTrue(small.contains("body-a") && !small.contains("body-b") && small.contains("skill_get when needed: ${b.id}"))
    }

    @Test fun toolsRoundTrip() = runTest {
        val saved = obj(SkillSaveTool(store).execute(buildJsonObject { put("name", "Scrape news"); put("description", "d"); put("body", "1. go") }))
        assertEquals("scrape-news", saved["id"]?.jsonPrimitive?.content)
        assertEquals(1, obj(SkillListTool(store).execute(JsonObject(emptyMap())))["skills"]!!.jsonArray.size)
        obj(SkillEditTool(store).execute(buildJsonObject { put("id", "scrape-news"); put("find", "go"); put("replace", "open the site") }))
        assertEquals("1. open the site", obj(SkillGetTool(store).execute(buildJsonObject { put("id", "scrape-news") }))["body"]?.jsonPrimitive?.content)
        assertTrue(obj(SkillSaveTool(store).execute(buildJsonObject { put("name", "x") })).containsKey("error"))
        assertTrue(obj(SkillEditTool(store).execute(buildJsonObject { put("id", "nope"); put("body", "x") })).containsKey("error"))
        assertEquals(true, obj(SkillDeleteTool(store).execute(buildJsonObject { put("id", "scrape-news") }))["ok"]?.jsonPrimitive?.boolean)
        assertTrue(obj(SkillGetTool(store).execute(buildJsonObject { put("id", "scrape-news") })).containsKey("error"))
    }
}
