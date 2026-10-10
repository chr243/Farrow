package com.verdroid.app.agent.tools

import com.verdroid.app.shizuku.RishRunner
import com.verdroid.app.shizuku.RishStore
import com.verdroid.app.shizuku.ShellExecutor
import kotlinx.serialization.json.*

/** Runs a shell command through Shizuku's rish deployed at /data/local/tmp/farrow_rish (RISH_APPLICATION_ID=com.termux). */
class RishRunTool(
    private val store: RishStore,
    private val runner: RishRunner,
    private val shell: ShellExecutor,
) : AgentTool {
    override val name = "rish_run"
    override val description = "Run a shell command through Shizuku's rish at /data/local/tmp/farrow_rish " +
        "(privileged shell: adb/shell user, or root if Shizuku runs as root; RISH_APPLICATION_ID=com.termux). " +
        "Alternative to run_shell when the user set up rish in Settings. " +
        "When saving screenshots (screencap), images or any other deliverable for the user, write under " +
        "/storage/emulated/0/Documents/Farrow/Output (not Pictures or Download). Returns exit_code, stdout, stderr. Timeout max 600 s."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "Command for /system/bin/sh -c on the Shizuku side"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 60, max 600)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val sh: suspend (String, Long) -> com.verdroid.app.shizuku.ShellResult = { c, ms -> shell.exec(c, null, ms) }
        if (!store.isDeployed(sh)) {
            return errorJson(
                if (store.isStaged()) "rish is staged but not deployed to ${RishStore.DEPLOY_DIR}. Open Settings > Shizuku & Git > rish and tap Find/Pick again (needs Shizuku), or Fix rish permissions."
                else "rish is not set up. Ask the user to Find or Pick rish in Settings > Shizuku & Git > rish.")
        }
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600)
        val r = try { runner.run(command, timeout) }
            catch (e: Exception) { return errorJson("rish failed to start: ${e.message}") }
        return buildJsonObject {
            put("exit_code", r.exitCode)
            if (r.timedOut) put("timed_out", true)
            put("stdout", r.stdout.takeLast(30_000)); put("stderr", r.stderr.takeLast(10_000))
            if (r.exitCode != 0 && (r.stderr.contains("permission", true) || r.stderr.contains("binder", true)))
                put("hint", "Check that Shizuku is running and authorized for com.termux, and try Fix rish permissions (Settings > Shizuku & Git).")
        }.toString()
    }
}
