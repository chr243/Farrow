package com.verdroid.app.agent.tools

import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.tools.ToolEnv
import com.verdroid.app.data.tools.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceToolsTest {
    private lateinit var base: File
    private lateinit var root: File
    private var access = true
    private lateinit var folder: SharedFolder

    @Before fun setUp() {
        base = Files.createTempDirectory("verdroid-docs").toFile()
        root = File(base, "Documents/Verdroid")
        access = true
        folder = SharedFolder(root) { access }
    }

    @After fun tearDown() { base.deleteRecursively() }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun args(vararg p: Pair<String, Any>) = buildJsonObject {
        p.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); is Number -> put(k, v); else -> put(k, v.toString()) } }
    }

    @Test fun ensureCreatesInputAndOutputOnlyWithAccess() {
        access = false
        assertFalse(folder.ensure())
        assertFalse(root.exists())
        access = true
        assertTrue(folder.ensure())
        assertTrue(File(root, "Input").isDirectory && File(root, "Output").isDirectory)
        File(root, "Output").delete()
        assertTrue(folder.ensure()) // recreated when missing
        assertTrue(File(root, "Output").isDirectory)
    }

    @Test fun sandboxRejectsEscapes() {
        folder.ensure()
        val sb = SharedFolderSandbox(folder)
        assertEquals(File(root, "Input/a.txt").canonicalFile, sb.resolve("Input/a.txt"))
        assertEquals(File(root, "Output/b.md").canonicalFile, sb.resolve("${SharedFolder.DISPLAY_PATH}/Output/b.md"))
        assertEquals(File(root, "Output").canonicalFile, sb.resolve(root.absolutePath + "/Output"))
        assertEquals(root.canonicalFile, sb.resolve("/sdcard/Documents/Verdroid"))
        for (bad in listOf("../x", "Input/../../x", "/etc/passwd", "/storage/emulated/0/Download/x",
                "${SharedFolder.DISPLAY_PATH}/../Secret", "/storage/emulated/0/Documents/VerdroidEvil/x", "a\u0000b")) {
            assertThrows(bad, SecurityException::class.java) { sb.resolve(bad) }
        }
        val outside = File(base, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(SecurityException::class.java) { sb.resolve("link/file") }
    }

    @Test fun writeReadListDelete() = runTest {
        val w = WorkspaceWriteTool(folder); val r = WorkspaceReadTool(folder)
        val l = WorkspaceListTool(folder); val d = WorkspaceDeleteTool(folder)

        val wr = obj(w.execute(args("path" to "Output/sub/report.md", "content" to "# Hi\n")))
        assertEquals(true, wr["ok"]?.jsonPrimitive?.boolean)
        assertEquals(true, wr["created"]?.jsonPrimitive?.boolean)
        assertEquals("Output/sub/report.md", wr["path"]?.jsonPrimitive?.content)
        w.execute(args("path" to "Output/sub/report.md", "content" to "more", "mode" to "append"))
        assertEquals("# Hi\nmore", File(root, "Output/sub/report.md").readText())
        assertTrue(obj(w.execute(args("path" to "Output/sub/report.md", "content" to "x", "mode" to "create"))).containsKey("error"))

        val rd = obj(r.execute(args("path" to "Output/sub/report.md", "offset" to 2, "max_bytes" to 2)))
        assertEquals("Hi", rd["content"]?.jsonPrimitive?.content)
        assertEquals(true, rd["truncated"]?.jsonPrimitive?.boolean)

        val ls = obj(l.execute(args()))
        val names = ls["entries"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals(listOf("Input", "Output"), names)
        val deep = obj(l.execute(args("recursive" to true)))["entries"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertTrue("Output/sub/report.md" in deep)

        assertTrue(obj(d.execute(args("path" to "Output/sub"))).containsKey("error")) // not empty
        assertEquals(true, obj(d.execute(args("path" to "Output/sub", "recursive" to true)))["ok"]?.jsonPrimitive?.boolean)
        assertFalse(File(root, "Output/sub").exists())
        assertTrue(obj(d.execute(args("path" to "."))).containsKey("error"))
        assertTrue(obj(d.execute(args("path" to "/"))).containsKey("error"))
        d.execute(args("path" to "Input", "recursive" to true))
        assertTrue(File(root, "Input").isDirectory) // Input/ is kept

        assertTrue(obj(w.execute(args("path" to "../escape.txt", "content" to "x"))).containsKey("error"))
        assertFalse(File(base, "Documents/escape.txt").exists())
        assertTrue(obj(r.execute(args("path" to "/etc/hostname"))).containsKey("error"))
    }

    @Test fun recursiveDeleteDoesNotFollowSymlinks() = runTest {
        folder.ensure()
        val outside = File(base, "keep").apply { mkdirs(); File(this, "precious.txt").writeText("x") }
        File(root, "Output/dir").mkdirs()
        Files.createSymbolicLink(File(root, "Output/dir/link").toPath(), outside.toPath())
        obj(WorkspaceDeleteTool(folder).execute(args("path" to "Output/dir", "recursive" to true)))
        assertFalse(File(root, "Output/dir").exists())
        assertTrue(File(outside, "precious.txt").exists())
    }

    @Test fun noAccessGivesClearError() = runTest {
        access = false
        val e = obj(WorkspaceWriteTool(folder).execute(args("path" to "Output/a", "content" to "x")))["error"]!!.jsonPrimitive.content
        assertTrue(e.contains("All files access"))
        assertFalse(ToolStatus.of("workspace_write", ToolEnv()).ready)
        assertTrue(ToolStatus.of("workspace_write", ToolEnv(storageReady = true)).ready)
    }
}
