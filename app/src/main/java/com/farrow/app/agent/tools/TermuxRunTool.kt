package com.farrow.app.agent.tools

import com.farrow.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*

/**
 * Runs a bash command inside Termux through Termux's RUN_COMMAND service (background, as the Termux user, with the
 * packages installed from Settings > Tools). No bridge or browser involved.
 */
class TermuxRunTool(private val termux: TermuxRunner) : AgentTool {
    override val name = "termux_run"
    override val description = "Run a bash command inside Termux (cwd ~/farrow-work) to use Termux packages such as ffmpeg, " +
        "magick (imagemagick), yt-dlp, git, node, jq, curl, pandoc — whichever the user installed in Settings > Tools. " +
        "Returns exit_code, stdout, stderr. Timeout max 600 s."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "bash -lc command"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 120, max 600)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, MAX_TIMEOUT_S)
        if (!termux.isInstalled()) return errorJson("Termux is not installed (F-Droid build). $SETUP")
        if (!termux.hasRunCommandPermission()) return errorJson("Farrow doesn't have the 'Run commands in Termux' permission. $SETUP")
        val r = termux.runAndWait(script(command, timeout), "run-${System.nanoTime()}", (timeout + 15) * 1_000L, label = "Farrow: termux_run")
            ?: return errorJson("Termux did not answer within ${timeout + 15} s. Check that allow-external-apps = true is set in Termux. $SETUP")
        if (r.err != null && r.err != RESULT_OK && r.exitCode == null) return errorJson("Termux could not run the command: ${r.errmsg ?: "error ${r.err}"}")
        val code = r.exitCode ?: -1
        return buildJsonObject {
            put("exit_code", code)
            if (code == 124) put("timed_out", true)
            put("stdout", r.stdout.takeLast(30_000)); put("stderr", r.stderr.takeLast(10_000))
        }.toString()
    }

    companion object {
        const val MAX_TIMEOUT_S = 600
        /** Termux plugin API: err == Activity.RESULT_OK (-1) means the command ran. */
        private const val RESULT_OK = -1
        private const val SETUP = "Set it up in Settings > Tools > Termux."

        /** cd into ~/farrow-work and run [command] under coreutils `timeout` (exit 124 on timeout). */
        internal fun script(command: String, timeoutS: Int): String =
            "mkdir -p ~/farrow-work && cd ~/farrow-work && timeout -k 5 $timeoutS bash -lc " + shellQuote(command)

        internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
