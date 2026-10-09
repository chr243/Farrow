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
        val c = File(dir, compName).apply { writeBytes(comp) }
        fixPermissions() // Android 14+ refuses to load a writable dex: chmod 400 right after copying
        return Result.Installed(s, c)
    }

    /** A rish script found on shared storage together with its companion dex. */
    data class Found(val script: File, val companion: File)

    /**
     * Looks for `rish` + its companion (`rish_shizuku.dex`) in [roots] and their sub-folders up to [depth] levels
     * (newest match first). Needs All files access on the device.
     */
    fun find(roots: List<File>, depth: Int = 2): Found? {
        val hits = mutableListOf<Found>()
        fun scan(dir: File, level: Int) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: return
            files.filter { it.isFile && it.name == SCRIPT && it.length() in 3..MAX_SCRIPT }.forEach { f ->
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                if (!isScript(Picked(f.name, bytes))) return@forEach
                val comp = File(dir, companionName(bytes.toString(Charsets.UTF_8)) ?: DEFAULT_COMPANION)
                if (comp.isFile && comp.canRead()) hits += Found(f, comp)
            }
            if (level < depth) files.filter { it.isDirectory && !it.name.startsWith(".") }.take(MAX_DIRS).forEach { scan(it, level + 1) }
        }
        roots.distinct().filter { it.isDirectory }.forEach { scan(it, 0) }
        return hits.maxByOrNull { it.script.lastModified() }
    }

    /** [find] + copy both files into [dir]. */
    fun findAndInstall(roots: List<File>): Result? {
        val f = find(roots) ?: return null
        return install(listOf(Picked(f.script.name, f.script.readBytes()), Picked(f.companion.name, f.companion.readBytes())))
    }

    /**
     * chmod 400 on the internal dex (owner read-only; Android 14+ refuses writable dex files) and owner rwx on the script.
     * Returns the dex mode afterwards (e.g. "r--------"), or null when rish isn't installed.
     */
    fun fixPermissions(): String? {
        val c = companion?.takeIf { it.isFile } ?: return null
        chmod(c, "r--------")
        if (script.isFile) chmod(script, "rwx------")
        return mode(c)
    }

    fun remove() { dir.listFiles()?.forEach { it.setWritable(true, true); it.delete() } }

    companion object {
        const val SCRIPT = "rish"
        const val DEFAULT_COMPANION = "rish_shizuku.dex"
        private const val MAX_SCRIPT = 64_000
        private const val MAX_DIRS = 200

        private fun chmod(f: File, perms: String) {
            val ok = runCatching {
                java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString(perms)); true
            }.getOrDefault(false)
            if (!ok) { // fallback without POSIX attribute support
                f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
                f.setReadable(perms[0] == 'r', true); f.setWritable(perms[1] == 'w', true); f.setExecutable(perms[2] == 'x', true)
            }
        }

        /** POSIX mode string such as "r--------" (null if unsupported). */
        fun mode(f: File): String? = runCatching {
            java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(f.toPath()))
        }.getOrNull()
        /** Where "Find rish" looks: Download, Documents and Documents/Farrow/Input (and their sub-folders). */
        fun searchRoots(storage: File = File("/storage/emulated/0")): List<File> = listOf(
            File(storage, "Download"), File(storage, "Documents"), File(storage, "Documents/Farrow/Input"))

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

/** Runs `sh <files/rish/rish> -c <command>` in Farrow's process, always with RISH_APPLICATION_ID=com.termux. */
class RishRunner(
    private val store: RishStore,
    private val shell: String = "/system/bin/sh",
) {
    data class Out(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun run(command: String, timeoutS: Int): Out {
        val pb = ProcessBuilder(shell, store.script.path, "-c", command).directory(store.dir)
        pb.environment()["RISH_APPLICATION_ID"] = APPLICATION_ID
        store.fixPermissions() // keep the dex at 400 even if something reset it
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

    companion object {
        /** rish_run always identifies as Termux (the terminal app the rish files were exported for). */
        const val APPLICATION_ID = "com.termux"
        private const val MAX_OUT = 200_000
    }
}
