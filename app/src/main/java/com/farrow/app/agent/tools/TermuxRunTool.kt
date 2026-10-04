package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import kotlinx.serialization.json.*
import java.io.IOException

/** Runs a command inside Termux through the bridge (as the Termux user, with packages installed via Settings > Tools). */
class TermuxRunTool(private val bridge: BridgeClient) : AgentTool {
    override val name = "termux_run"
    override val description = "Run a bash command inside Termux (cwd ~/farrow-work) to use Termux packages such as ffmpeg, " +
        "magick (imagemagick), yt-dlp, git, node, jq, curl, pandoc — whichever the user installed in Settings > Tools. " +
        "Returns exit_code, stdout, stderr. Timeout max 140 s."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "bash -lc command"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 120, max 140)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, 140)
        val r = try {
            bridge.command("exec", buildJsonObject { put("command", command); put("timeout_s", timeout) })
        } catch (e: IOException) {
            return errorJson("Termux bridge not reachable (Settings > Internal browser setup): ${e.message}")
        }
        if (r.code == 2 && r.stderr.startsWith("unknown cmd")) return errorJson("The Termux bridge is too old for termux_run — tap Set up everything in Settings > Internal browser setup")
        return buildJsonObject {
            put("exit_code", r.code); put("stdout", r.stdout.takeLast(30_000)); put("stderr", r.stderr.takeLast(10_000))
        }.toString()
    }
}
