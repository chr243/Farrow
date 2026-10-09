package com.farrow.app.agent.tools

import com.farrow.app.shizuku.RishRunner
import com.farrow.app.shizuku.RishStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Runs a shell command through Shizuku's rish, using the copies in Farrow's internal folder (files/rish). */
class RishRunTool(private val store: RishStore, private val runner: RishRunner) : AgentTool {
    override val name = "rish_run"
    override val description = "Run a shell command through Shizuku's rish (privileged shell: adb/shell user, or root if Shizuku runs as root). " +
        "Alternative to run_shell when the user set up rish in Settings. Returns exit_code, stdout, stderr. Timeout max 600 s."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "Command for /system/bin/sh -c on the Shizuku side"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 60, max 600)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        if (!store.isInstalled()) return errorJson("rish is not set up. Ask the user to pick the rish file in Settings > Shizuku, accessibility & Git > rish.")
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600)
        val r = try { withContext(Dispatchers.IO) { runner.run(command, timeout) } }
            catch (e: Exception) { return errorJson("rish failed to start: ${e.message}") }
        return buildJsonObject {
            put("exit_code", r.exitCode)
            if (r.timedOut) put("timed_out", true)
            put("stdout", r.stdout.takeLast(30_000)); put("stderr", r.stderr.takeLast(10_000))
            if (r.exitCode != 0 && (r.stderr.contains("permission", true) || r.stderr.contains("binder", true)))
                put("hint", "Check that Shizuku is running and authorized for com.termux, and try Fix rish permissions (Settings > Shizuku, accessibility & Git).")
        }.toString()
    }
}
