package com.farrow.app.shizuku

import java.io.File
import java.util.Base64

/**
 * Shizuku's `rish` (https://github.com/RikkaApps/Shizuku-API/tree/master/rish): the user exports `rish` +
 * `rish_shizuku.dex`, Farrow validates them, then deploys both to [DEPLOY_DIR] via Shizuku (`chmod +x` both —
 * what worked on the phone; Android 14's "writable dex" rule does not apply under /data/local/tmp the same way).
 * [RishRunner] runs commands from that directory with `RISH_APPLICATION_ID=com.termux`.
 */
class RishStore(stagingDir: File) {
    /** App-private staging of the validated files (source for deploy; shell can't read this path). */
    val staging: File = stagingDir.canonicalFile
    val stagedScript: File get() = File(staging, SCRIPT)
    val stagedCompanion: File? get() = stagedScript.takeIf { it.isFile }?.let { companionName(it.readText()) }?.let { File(staging, it) }

    /** True when the validated pair is staged in the app (ready to deploy / already deployed once). */
    fun isStaged(): Boolean = stagedScript.isFile && stagedCompanion?.isFile == true

    data class Picked(val name: String, val bytes: ByteArray)
    sealed interface Result {
        data class Installed(val scriptName: String, val companionName: String, val deployed: Boolean, val detail: String? = null) : Result
        data class NeedCompanion(val name: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Validates and stages the picked files. [findSibling] resolves the companion next to the picked rish when only
     * rish was picked (needs All files access). Call [deploy] afterwards (or it is called by the Settings flow).
     */
    fun stage(picked: List<Picked>, findSibling: (String) -> ByteArray? = { null }): Result {
        val rish = picked.firstOrNull { isScript(it) } ?: return Result.Invalid(
            "Pick the rish file exported from Shizuku (Shizuku → Use Shizuku in terminal apps → Export files).")
        val text = rish.bytes.toString(Charsets.UTF_8)
        val compName = companionName(text) ?: DEFAULT_COMPANION
        val comp = picked.firstOrNull { it !== rish && (it.name == compName || isDex(it.bytes)) }?.bytes
            ?: findSibling(compName)
            ?: return Result.NeedCompanion(compName)
        if (!isDex(comp)) return Result.Invalid("$compName is not a dex file.")
        staging.mkdirs()
        staging.listFiles()?.forEach { it.delete() }
        stagedScript.writeBytes(rish.bytes)
        File(staging, compName).writeBytes(comp)
        return Result.Installed(SCRIPT, compName, deployed = false)
    }

    /** @deprecated use [stage]; kept name for callers. */
    fun install(picked: List<Picked>, findSibling: (String) -> ByteArray? = { null }): Result = stage(picked, findSibling)

    data class Found(val script: File, val companion: File)

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

    fun findAndStage(roots: List<File>): Result? {
        val f = find(roots) ?: return null
        return stage(listOf(Picked(f.script.name, f.script.readBytes()), Picked(f.companion.name, f.companion.readBytes())))
    }

    fun findAndInstall(roots: List<File>): Result? = findAndStage(roots)

    /**
     * Copies the staged pair into [DEPLOY_DIR] through [shell] (Shizuku) and `chmod +x` both files.
     * Uses base64 over the shell because the shell uid can't read the app-private staging folder.
     */
    suspend fun deploy(shell: suspend (command: String, timeoutMs: Long) -> ShellResult): Result {
        if (!isStaged()) return Result.Invalid("rish is not staged yet — pick or find it first.")
        val script = stagedScript.readBytes()
        val compFile = stagedCompanion!!
        val compName = compFile.name
        val dex = compFile.readBytes()
        val cmd = buildString {
            append("set -e\nmkdir -p ").append(DEPLOY_DIR).append('\n')
            append(writeBase64(SCRIPT, script))
            append(writeBase64(compName, dex))
            append("chmod +x ").append(DEPLOY_DIR).append('/').append(SCRIPT)
            append(' ').append(DEPLOY_DIR).append('/').append(compName).append('\n')
            append("ls -l ").append(DEPLOY_DIR).append('\n')
        }
        val r = shell(cmd, 60_000)
        if (r.exitCode != 0) return Result.Invalid("Deploy to $DEPLOY_DIR failed (exit ${r.exitCode}): ${(r.stderr + r.stdout).takeLast(500)}")
        return Result.Installed(SCRIPT, compName, deployed = true, detail = r.stdout.trim().takeLast(300))
    }

    /** `chmod +x` both files in [DEPLOY_DIR] (retry button). Returns the `ls -l` line for the dex, or null if missing. */
    suspend fun fixPermissions(shell: suspend (command: String, timeoutMs: Long) -> ShellResult): String? {
        val r = shell(
            "D=$DEPLOY_DIR; test -f \"\$D/$SCRIPT\" && test -f \"\$D/$DEFAULT_COMPANION\" || " +
                "test -n \"\$(ls \"\$D\"/rish_*.dex 2>/dev/null)\" || exit 2; " +
                "chmod +x \"\$D\"/*; ls -l \"\$D\"",
            15_000)
        if (r.exitCode != 0) return null
        return r.stdout.lineSequence().firstOrNull { it.contains(".dex") }?.trim()
            ?: r.stdout.trim().takeLast(200).ifBlank { "chmod +x ok" }
    }

    /** Whether [DEPLOY_DIR]/rish exists and is executable (via shell). */
    suspend fun isDeployed(shell: suspend (command: String, timeoutMs: Long) -> ShellResult): Boolean {
        val r = shell("test -x $DEPLOY_DIR/$SCRIPT && ls $DEPLOY_DIR/rish_*.dex >/dev/null 2>&1", 8_000)
        return r.exitCode == 0
    }

    fun removeStaging() { staging.listFiles()?.forEach { it.delete() } }

    /** Removes the deployed copies (via shell) and the staging folder. */
    suspend fun remove(shell: suspend (command: String, timeoutMs: Long) -> ShellResult) {
        runCatching { shell("rm -rf $DEPLOY_DIR", 10_000) }
        removeStaging()
    }

    companion object {
        const val SCRIPT = "rish"
        const val DEFAULT_COMPANION = "rish_shizuku.dex"
        /** Where the phone-proven setup lives (shell-writable; chmod +x both). */
        const val DEPLOY_DIR = "/data/local/tmp/farrow_rish"
        private const val MAX_SCRIPT = 64_000
        private const val MAX_DIRS = 200
        /** Base64 chunk size kept well under typical Android shell argument limits. */
        private const val B64_CHUNK = 48_000

        fun searchRoots(storage: File = File("/storage/emulated/0")): List<File> = listOf(
            File(storage, "Download"), File(storage, "Documents"), File(storage, "Documents/Farrow/Input"))

        fun isScript(p: Picked): Boolean =
            p.bytes.size in 3..MAX_SCRIPT && p.bytes[0] == '#'.code.toByte() && p.bytes[1] == '!'.code.toByte() &&
                p.bytes.toString(Charsets.UTF_8).let { it.contains("app_process") || it.contains("rish") }

        fun isDex(b: ByteArray): Boolean = b.size > 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() &&
            b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

        fun companionName(script: String): String? =
            Regex("""rish_[A-Za-z0-9_.\-]+""").findAll(script).map { it.value.trimEnd('.') }
                .firstOrNull { it.endsWith(".dex") } ?: Regex("""rish_[A-Za-z0-9_.\-]+""").find(script)?.value

        private fun writeBase64(name: String, bytes: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val path = "$DEPLOY_DIR/$name"
            val sb = StringBuilder()
            sb.append(": > ").append(path).append('\n')
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + B64_CHUNK, b64.length)
                sb.append("printf '%s' '").append(b64, i, end).append("' | base64 -d >> ").append(path).append('\n')
                i = end
            }
            return sb.toString()
        }
    }
}

/**
 * Runs a command through the deployed rish under [RishStore.DEPLOY_DIR] via Shizuku, always with
 * `RISH_APPLICATION_ID=com.termux`. Re-applies `chmod +x` before each run.
 */
class RishRunner(
    private val store: RishStore,
    private val shell: suspend (command: String, timeoutMs: Long) -> ShellResult,
) {
    data class Out(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    suspend fun run(command: String, timeoutS: Int): Out {
        store.fixPermissions(shell)
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        val cmd = "export RISH_APPLICATION_ID=$APPLICATION_ID; ${RishStore.DEPLOY_DIR}/${RishStore.SCRIPT} -c $quoted"
        val r = shell(cmd, (timeoutS + 5) * 1_000L)
        return Out(r.exitCode, r.stdout.take(MAX_OUT), r.stderr.take(MAX_OUT), r.timedOut)
    }

    companion object {
        const val APPLICATION_ID = "com.termux"
        private const val MAX_OUT = 200_000
    }
}
