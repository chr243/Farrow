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
        "Returns exit_code, stdout, stderr as soon as the command finishes (timeout_seconds is only a cap, max 600 s; " +
        "background jobs left running are stopped when the command ends)."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "bash -lc command"),
        "timeout_seconds" to prop("integer", "Max seconds before the command is killed (default 120, max 600); the tool returns as soon as it finishes"))

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

        /**
         * cd into ~/farrow-work and run [command] under coreutils `timeout` (exit 124 on timeout); the timeout is only a cap.
         *
         * Termux returns the result only when the stdout/stderr pipes reach EOF, not when bash exits. Anything the command
         * (or the login profile of `bash -l`, e.g. sshd/ssh-agent/crond/pulseaudio) leaves running in the background kept
         * those pipes open, so every call waited the FULL timeout until `timeout` killed the group (v1.0.23 bug). Now the
         * command writes to temp files with stdin from /dev/null, the wrapper waits only for the command itself, cleans up
         * leftovers in its process group (timeout's pid == pgid), then prints the captured output and exits at once.
         */
        internal fun script(command: String, timeoutS: Int): String =
            "mkdir -p ~/farrow-work && cd ~/farrow-work || exit 1\n" + capped("bash -lc " + shellQuote(command), timeoutS)

        /**
         * Runs the shell fragment [cmdline] under `timeout -k [grace] [timeoutS]` so that the Termux result comes back as soon
         * as it exits: output goes to temp files (stdin /dev/null), leftovers in its process group are stopped, then the
         * captured stdout/stderr are replayed and the exit code (124 on timeout) is returned. Must be the script's last step.
         */
        internal fun capped(cmdline: String, timeoutS: Int, grace: Int = 5): String = """
fo=${'$'}(mktemp) && fe=${'$'}(mktemp) || exit 1
timeout -k $grace $timeoutS $cmdline </dev/null >"${'$'}fo" 2>"${'$'}fe" &
pid=${'$'}!
wait "${'$'}pid"; rc=${'$'}?
kill -TERM -- "-${'$'}pid" 2>/dev/null
cat "${'$'}fo"; cat "${'$'}fe" >&2; rm -f "${'$'}fo" "${'$'}fe"
exit "${'$'}rc"
""".trim()

        internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
