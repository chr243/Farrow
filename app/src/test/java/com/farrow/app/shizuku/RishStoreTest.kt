package com.farrow.app.shizuku

import com.farrow.app.agent.tools.RishRunTool
import com.farrow.app.data.tools.ToolEnv
import com.farrow.app.data.tools.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RishStoreTest {
    private lateinit var base: File
    private lateinit var store: RishStore
    // Shape of the script Shizuku exports; the last line is replaced by a local stand-in for app_process.
    private val script = """
        #!/system/bin/sh
        BASEDIR=${'$'}(dirname "${'$'}0")
        DEX="${'$'}BASEDIR"/rish_shizuku.dex
        if [ ! -f "${'$'}DEX" ]; then echo "Cannot find ${'$'}DEX"; exit 1; fi
        [ -z "${'$'}RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="PKG"
        # /system/bin/app_process -Djava.class.path="${'$'}DEX" /system/bin --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader "${'$'}@"
        echo "app=${'$'}RISH_APPLICATION_ID"; exec sh "${'$'}@"
    """.trimIndent().toByteArray()
    private val dex = "dex\n035\u0000".toByteArray() + ByteArray(32)

    @Before fun setUp() { base = Files.createTempDirectory("rish").toFile(); store = RishStore(File(base, "files/rish")) }
    @After fun tearDown() { File(base, "files/rish").listFiles()?.forEach { it.setWritable(true) }; base.deleteRecursively() }

    @Test fun copiesBothFilesWhenPickedTogether() {
        val r = store.install(listOf(RishStore.Picked("rish_shizuku.dex", dex), RishStore.Picked("rish", script)))
        assertTrue(r is RishStore.Result.Installed)
        assertTrue(store.isInstalled())
        assertArrayEquals(script, File(base, "files/rish/rish").readBytes())
        val d = File(base, "files/rish/rish_shizuku.dex")
        assertArrayEquals(dex, d.readBytes()); assertFalse(d.canWrite()) // read-only for Android 14+
        assertEquals("r--------", RishStore.mode(d)) // chmod 400 right after the copy
        assertEquals("rish_shizuku.dex", RishStore.companionName(String(script)))
        // Picking again replaces the read-only dex.
        assertTrue(store.install(listOf(RishStore.Picked("rish", script), RishStore.Picked("x", dex))) is RishStore.Result.Installed)
    }

    @Test fun onlyRishPickedUsesSiblingOrAsksForIt() {
        assertEquals(RishStore.Result.NeedCompanion("rish_shizuku.dex"), store.install(listOf(RishStore.Picked("rish", script))))
        assertFalse(store.isInstalled())
        var asked: String? = null
        val r = store.install(listOf(RishStore.Picked("rish", script))) { asked = it; dex }
        assertEquals("rish_shizuku.dex", asked)
        assertTrue(r is RishStore.Result.Installed && store.isInstalled())
    }

    @Test fun findScansDownloadDocumentsAndInputThenCopies() {
        val storage = File(base, "storage").apply { mkdirs() }
        val roots = RishStore.searchRoots(storage)
        assertEquals(listOf("Download", "Documents", "Documents/Farrow/Input"), roots.map { it.relativeTo(storage).path })
        assertNull(store.find(roots))
        // A rish without its dex is ignored; a decoy file named rish that isn't a script too.
        File(storage, "Download").mkdirs(); File(storage, "Download/rish").writeBytes(script)
        File(storage, "Documents/other").mkdirs(); File(storage, "Documents/other/rish").writeText("hello")
        assertNull(store.find(roots))
        val exportDir = File(storage, "Documents/Farrow/Input/shizuku").apply { mkdirs() }
        File(exportDir, "rish").writeBytes(script); File(exportDir, "rish_shizuku.dex").writeBytes(dex)
        val found = store.find(roots)!!
        assertEquals(File(exportDir, "rish"), found.script)
        assertTrue(store.findAndInstall(roots) is RishStore.Result.Installed)
        assertTrue(store.isInstalled())
        assertArrayEquals(dex, File(base, "files/rish/rish_shizuku.dex").readBytes())
    }

    @Test fun fixPermissionsRestoresChmod400() {
        assertNull(store.fixPermissions())
        store.install(listOf(RishStore.Picked("rish", script), RishStore.Picked("rish_shizuku.dex", dex)))
        val d = File(base, "files/rish/rish_shizuku.dex")
        Files.setPosixFilePermissions(d.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals("r--------", store.fixPermissions())
        assertEquals("r--------", RishStore.mode(d))
        assertEquals("rwx------", RishStore.mode(File(base, "files/rish/rish")))
    }

    @Test fun rejectsWrongFiles() {
        assertTrue(store.install(listOf(RishStore.Picked("photo.jpg", ByteArray(100) { 1 }))) is RishStore.Result.Invalid)
        assertTrue(store.install(listOf(RishStore.Picked("rish", script), RishStore.Picked("rish_shizuku.dex", "not a dex".toByteArray())))
            .let { it is RishStore.Result.Invalid || it is RishStore.Result.NeedCompanion })
        assertFalse(store.isInstalled())
    }

    @Test fun rishRunUsesInternalCopiesWithFarrowAsApplicationId() = runTest {
        val runner = RishRunner(store, shell = "sh")
        val tool = RishRunTool(store, runner)
        assertTrue(Json.parseToJsonElement(tool.execute(buildJsonObject { put("command", "id") })).jsonObject["error"]!!
            .jsonPrimitive.content.contains("Settings"))
        store.install(listOf(RishStore.Picked("rish", script), RishStore.Picked("rish_shizuku.dex", dex)))
        val r = Json.parseToJsonElement(tool.execute(buildJsonObject { put("command", "echo hi; echo oops >&2; exit 3") })).jsonObject
        assertEquals(3, r["exit_code"]?.jsonPrimitive?.int)
        assertEquals("app=com.termux\nhi\n", r["stdout"]?.jsonPrimitive?.content)
        assertEquals("oops\n", r["stderr"]?.jsonPrimitive?.content)
        val t = Json.parseToJsonElement(tool.execute(buildJsonObject { put("command", "sleep 30"); put("timeout_seconds", 1) })).jsonObject
        assertEquals(true, t["timed_out"]?.jsonPrimitive?.boolean)
        assertFalse(ToolStatus.of("rish_run", ToolEnv(shizukuReady = true)).ready)
        assertTrue(ToolStatus.of("rish_run", ToolEnv(shizukuReady = true, rishReady = true)).ready)
    }
}
