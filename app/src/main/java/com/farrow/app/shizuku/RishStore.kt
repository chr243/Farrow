package com.farrow.app.shizuku

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shizuku's `rish` (https://github.com/RikkaApps/Shizuku-API/tree/master/rish): the user exports it from the Shizuku app
 * ("Use Shizuku in terminal apps" → Export files: `rish` + `rish_shizuku.dex`), picks it in Settings, and Farrow copies
 * BOTH files into its internal folder `files/rish/`. [RishRunner] then runs commands through the internal copies.
 */
class RishStore(dir: File) {
    val dir: File = dir.canonicalFile
    val script: File get() = File(dir, SCRIPT)

    /** Companion file name the script loads (`$BASEDIR/rish_shizuku.dex`), read from the installed script. */
    val companion: File? get() = script.takeIf { it.isFile }?.let { companionName(it.readText()) }?.let { File(dir, it) }

    fun isInstalled(): Boolean = script.isFile && companion?.isFile == true

    data class Picked(val name: String, val bytes: ByteArray)
    sealed interface Result {
        data class Installed(val script: File, val companion: File) : Result
        /** rish was picked but its companion (e.g. rish_shizuku.dex) wasn't; ask the user to pick it too. */
        data class NeedCompanion(val name: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Validates and copies the picked files. [picked] may contain rish and its companion in any order; [findSibling]
     * resolves the companion next to the picked rish when the user only picked rish (needs All files access).
     */
    fun install(picked: List<Picked>, findSibling: (String) -> ByteArray? = { null }): Result {
        val rish = picked.firstOrNull { isScript(it) } ?: return Result.Invalid(
            "Pick the rish file exported from Shizuku (Shizuku → Use Shizuku in terminal apps → Export files).")
        val text = rish.bytes.toString(Charsets.UTF_8)
        val compName = companionName(text) ?: DEFAULT_COMPANION
        val comp = picked.firstOrNull { it !== rish && (it.name == compName || isDex(it.bytes)) }?.bytes
            ?: findSibling(compName)
            ?: return Result.NeedCompanion(compName)
        if (!isDex(comp)) return Result.Invalid("$compName is not a dex file.")
        dir.mkdirs()
        // Replace atomically-ish: remove old copies (the dex is read-only), then write.
        dir.listFiles()?.forEach { it.setWritable(true, true); it.delete() }
        val s = File(dir, SCRIPT).apply { writeBytes(rish.bytes); setReadable(true, true); setExecutable(true, true) }
        val c = File(dir, compName).apply {
            writeBytes(comp)
            // Android 14+ refuses to load writable dex files: make the internal copy read-only.
            setWritable(false, false); setReadable(true, true)
        }
        return Result.Installed(s, c)
    }

    fun remove() { dir.listFiles()?.forEach { it.setWritable(true, true); it.delete() } }

    companion object {
        const val SCRIPT = "rish"
        const val DEFAULT_COMPANION = "rish_shizuku.dex"
        private const val MAX_SCRIPT = 64_000

        fun isScript(p: Picked): Boolean =
            p.bytes.size in 3..MAX_SCRIPT && p.bytes[0] == '#'.code.toByte() && p.bytes[1] == '!'.code.toByte() &&
                p.bytes.toString(Charsets.UTF_8).let { it.contains("app_process") || it.contains("rish") }

        fun isDex(b: ByteArray): Boolean = b.size > 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() &&
            b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

        /** `rish_shizuku.dex` (or whatever rish_* file the script references). */
        fun companionName(script: String): String? =
            Regex("""rish_[A-Za-z0-9_.\-]+""").findAll(script).map { it.value.trimEnd('.') }
                .firstOrNull { it.endsWith(".dex") } ?: Regex("""rish_[A-Za-z0-9_.\-]+""").find(script)?.value
    }
}

/** Runs `sh <files/rish/rish> -c <command>` in Farrow's process, with RISH_APPLICATION_ID = Farrow (holds the Shizuku permission). */
class RishRunner(
    private val store: RishStore,
    private val applicationId: String,
    private val shell: String = "/system/bin/sh",
) {
    data class Out(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun run(command: String, timeoutS: Int): Out {
        val pb = ProcessBuilder(shell, store.script.path, "-c", command).directory(store.dir)
        pb.environment()["RISH_APPLICATION_ID"] = applicationId
        val p = pb.start()
        p.outputStream.close()
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = Thread { p.inputStream.bufferedReader().use { r -> r.forEachLine { synchronized(out) { if (out.length < MAX_OUT) out.appendLine(it) } } } }
        val t2 = Thread { p.errorStream.bufferedReader().use { r -> r.forEachLine { synchronized(err) { if (err.length < MAX_OUT) err.appendLine(it) } } } }
        t1.start(); t2.start()
        val finished = p.waitFor(timeoutS.toLong(), TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        t1.join(2_000); t2.join(2_000)
        return Out(if (finished) p.exitValue() else 124, synchronized(out) { out.toString() }, synchronized(err) { err.toString() }, !finished)
    }

    private companion object { const val MAX_OUT = 200_000 }
}
