package com.verdroid.app.shizuku

import com.verdroid.app.data.tools.ToolEnv
import com.verdroid.app.data.tools.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RishStoreTest {
    private lateinit var base: File
    private lateinit var store: RishStore
    private lateinit var deployRoot: File
    private val script = """
        #!/bin/sh
        BASEDIR=${'$'}(dirname "${'$'}0")
        DEX="${'$'}BASEDIR"/rish_shizuku.dex
        if [ ! -f "${'$'}DEX" ]; then echo "Cannot find ${'$'}DEX"; exit 1; fi
        [ -z "${'$'}RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="PKG"
        echo "app=${'$'}RISH_APPLICATION_ID"; exec sh "${'$'}@"
    """.trimIndent().toByteArray()
    private val dex = "dex\n035\u0000".toByteArray() + ByteArray(32)

    /** Fake shell that maps DEPLOY_DIR under [deployRoot] and runs bash. */
    private fun fakeShell(): suspend (String, Long) -> ShellResult = { cmd, _ ->
        val dir = File(deployRoot, "data/local/tmp/farrow_rish")
        val mapped = cmd.replace(RishStore.DEPLOY_DIR, dir.path)
        val pb = ProcessBuilder("bash", "-c", mapped)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        ShellResult("test", p.waitFor(), out, err)
    }

    @Before fun setUp() {
        base = Files.createTempDirectory("rish").toFile()
        deployRoot = File(base, "root").apply { mkdirs() }
        store = RishStore(File(base, "files/rish"))
    }
    @After fun tearDown() { base.deleteRecursively() }

    @Test fun stageValidatesAndKeepsPair() {
        val r = store.stage(listOf(RishStore.Picked("rish_shizuku.dex", dex), RishStore.Picked("rish", script)))
        assertTrue(r is RishStore.Result.Installed)
        assertTrue(store.isStaged())
        assertArrayEquals(script, store.stagedScript.readBytes())
        assertArrayEquals(dex, store.stagedCompanion!!.readBytes())
        assertEquals(RishStore.Result.NeedCompanion("rish_shizuku.dex"), store.stage(listOf(RishStore.Picked("rish", script))))
    }

    @Test fun findScansDownloadDocumentsAndInputThenStages() {
        val storage = File(base, "storage").apply { mkdirs() }
        val roots = RishStore.searchRoots(storage)
        assertEquals(listOf("Download", "Documents", "Documents/Farrow/Input"), roots.map { it.relativeTo(storage).path })
        File(storage, "Download").mkdirs(); File(storage, "Download/rish").writeBytes(script)
        assertNull(store.find(roots))
        val exportDir = File(storage, "Documents/Farrow/Input/shizuku").apply { mkdirs() }
        File(exportDir, "rish").writeBytes(script); File(exportDir, "rish_shizuku.dex").writeBytes(dex)
        assertTrue(store.findAndStage(roots) is RishStore.Result.Installed)
        assertTrue(store.isStaged())
    }

    @Test fun deployWritesToTmpAndChmodPlusX() = runTest {
        store.stage(listOf(RishStore.Picked("rish", script), RishStore.Picked("rish_shizuku.dex", dex)))
        val shell = fakeShell()
        val r = store.deploy(shell) as RishStore.Result.Installed
        assertTrue(r.deployed)
        val dir = File(deployRoot, "data/local/tmp/farrow_rish")
        assertArrayEquals(script, File(dir, "rish").readBytes())
        assertArrayEquals(dex, File(dir, "rish_shizuku.dex").readBytes())
        assertTrue(File(dir, "rish").canExecute())
        assertTrue(File(dir, "rish_shizuku.dex").canExecute()) // chmod +x both (not 400)
        assertTrue(store.isDeployed(shell))
        File(dir, "rish").setExecutable(false)
        assertNotNull(store.fixPermissions(shell))
        assertTrue(File(dir, "rish").canExecute())
    }

    @Test fun rishRunUsesDeployDirAndTermuxApplicationId() = runTest {
        val shell = fakeShell()
        val runner = RishRunner(store, shell)
        store.stage(listOf(RishStore.Picked("rish", script), RishStore.Picked("rish_shizuku.dex", dex)))
        store.deploy(shell)
        val out = runner.run("echo hi; echo oops >&2; exit 3", 15)
        assertEquals(3, out.exitCode)
        assertEquals("app=com.termux\nhi\n", out.stdout)
        assertEquals("oops\n", out.stderr)
        assertTrue(store.isDeployed(shell))
        assertTrue(ToolStatus.of("rish_run", ToolEnv(shizukuReady = true, rishReady = true)).ready)
        assertTrue(ToolStatus.of("rish_run", ToolEnv(shizukuReady = true, rishReady = true)).text.contains("farrow_rish"))
        assertFalse(ToolStatus.of("rish_run", ToolEnv(shizukuReady = true)).ready)
    }

    @Test fun rishRunToolDescriptionMentionsOutputAndTermux() {
        assertEquals("com.termux", RishRunner.APPLICATION_ID)
        assertEquals("/data/local/tmp/farrow_rish", RishStore.DEPLOY_DIR)
        val src = File("src/main/java/com/verdroid/app/agent/tools/RishRunTool.kt").readText()
        assertTrue(src.contains("Documents/Farrow/Output") && src.contains("not Pictures") && src.contains("com.termux"))
        val prompt = File("src/main/java/com/verdroid/app/agent/AgentLoop.kt").readText()
        assertTrue(prompt.contains("ALL user-facing deliverables go in Output/"))
        assertTrue(prompt.contains("never Pictures") || prompt.contains("Never save deliverables to Pictures"))
        assertTrue(prompt.contains("/data/local/tmp/farrow_rish"))
    }

    @Test fun rejectsWrongFiles() {
        assertTrue(store.stage(listOf(RishStore.Picked("photo.jpg", ByteArray(100) { 1 }))) is RishStore.Result.Invalid)
        assertFalse(store.isStaged())
    }
}
