package com.verdroid.app.agent.tools

import android.accessibilityservice.AccessibilityService
import com.verdroid.app.data.a11y.A11yNode
import com.verdroid.app.data.a11y.VerdroidAccessibilityService
import com.verdroid.app.data.git.GitManager
import com.verdroid.app.shizuku.ShellExecutor
import kotlinx.serialization.json.*

// ---------------------------------------------------------------- Shizuku shell

class RunShellTool(private val shell: ShellExecutor, private val sandbox: WorkspaceSandbox) : AgentTool {
    override val name = "run_shell"
    override val description = "Run a shell command on the phone as the adb shell user via Shizuku (no Termux packages here — use termux_run for those). Returns backend, exit_code, stdout and stderr."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "Shell command (sh -c)"),
        "workdir" to prop("string", "Working directory relative to the agent workspace (default: workspace root)"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 60, max 600)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600) * 1000L
        // The shell uid can't read the app's private dir, so the workdir is only applied when it is accessible.
        val dir = args.str("workdir")?.let { sandbox.resolve(it).absolutePath }
        return try {
            val r = shell.exec(command, dir, timeout)
            buildJsonObject {
                put("backend", r.backend); put("exit_code", r.exitCode)
                put("stdout", r.stdout.takeLast(MAX_OUT)); put("stderr", r.stderr.takeLast(MAX_OUT))
                if (r.timedOut) put("timed_out", true)
            }.toString()
        } catch (e: IllegalStateException) { errorJson(e.message ?: "no shell backend available") }
    }

    private companion object { const val MAX_OUT = 30_000 }
}

// ---------------------------------------------------------------- JGit

private fun JsonObjectBuilder.putSet(key: String, set: Set<String>) { put(key, JsonArray(set.sorted().take(300).map { JsonPrimitive(it) })) }

class GitStatusTool(private val git: GitManager) : AgentTool {
    override val name = "git_status"
    override val description = "Show git status of a repository in the workspace."
    override val parameters = schema(emptyList(), "repo_path" to prop("string", "Repository path relative to the workspace"))
    override suspend fun execute(args: JsonObject): String {
        val s = git.status(args.str("repo_path"))
        return buildJsonObject {
            put("branch", s.branch); put("clean", s.clean)
            putSet("added", s.added); putSet("changed", s.changed); putSet("modified", s.modified)
            putSet("removed", s.removed); putSet("missing", s.missing); putSet("untracked", s.untracked); putSet("conflicting", s.conflicting)
        }.toString()
    }
}

class GitCommitTool(private val git: GitManager) : AgentTool {
    override val name = "git_commit"
    override val description = "Stage all changes (including deletions) and commit in a workspace repository."
    override val parameters = schema(listOf("message"),
        "repo_path" to prop("string", "Repository path relative to the workspace"),
        "message" to prop("string", "Commit message"))
    override suspend fun execute(args: JsonObject): String {
        val msg = args.str("message")?.takeIf { it.isNotBlank() } ?: return errorJson("message is required")
        val id = git.commit(args.str("repo_path"), msg)
        return buildJsonObject { put("ok", true); put("commit", id) }.toString()
    }
}

class GitCloneTool(private val git: GitManager) : AgentTool {
    override val name = "git_clone"
    override val description = "Clone an https:// git repository into the workspace (uses the Git token from settings for private repos)."
    override val parameters = schema(listOf("url"),
        "url" to prop("string", "https:// clone URL"),
        "path" to prop("string", "Target folder relative to the workspace (default: repo name)"),
        "branch" to prop("string", "Branch to check out"))
    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url") ?: return errorJson("url is required")
        val path = git.clone(url, args.str("path"), args.str("branch"), null)
        return buildJsonObject { put("ok", true); put("path", path) }.toString()
    }
}

class GitPushTool(private val git: GitManager) : AgentTool {
    override val name = "git_push"
    override val description = "Push a workspace repository to its remote using the Git token from encrypted settings."
    override val parameters = schema(emptyList(),
        "repo_path" to prop("string", "Repository path relative to the workspace"),
        "remote" to prop("string", "Remote name (default origin)"),
        "branch" to prop("string", "Branch/refspec to push (default: current branch)"))
    override suspend fun execute(args: JsonObject): String = try {
        val updates = git.push(args.str("repo_path"), args.str("remote"), args.str("branch"))
        buildJsonObject { put("ok", updates.none { "REJECTED" in it }); put("updates", JsonArray(updates.map { JsonPrimitive(it) })) }.toString()
    } catch (e: IllegalStateException) { errorJson(e.message ?: "push failed") }
}

// ---------------------------------------------------------------- Accessibility

private const val A11Y_OFF = "Accessibility service is off (enable 'Farrow agent control' in Settings > Shizuku & accessibility setup). " +
    "It is only needed to control OTHER Android apps on the phone screen."
private const val PHONE = "Phone screen (accessibility): controls other Android apps on the phone's display. "

private fun A11yNode.toJson(): JsonObject = buildJsonObject {
    cls?.let { put("class", it.substringAfterLast('.')) }
    text?.takeIf { it.isNotBlank() }?.let { put("text", it.take(300)) }
    desc?.takeIf { it.isNotBlank() }?.let { put("desc", it.take(200)) }
    viewId?.let { put("id", it) }
    if (clickable) put("clickable", true)
    if (editable) put("editable", true)
    put("bounds", JsonArray(bounds.map { JsonPrimitive(it) }))
    if (children.isNotEmpty()) put("children", JsonArray(children.map { it.toJson() }))
}

class ScreenReadTool : AgentTool {
    override val name = "screen_read"
    override val description = PHONE + "Read the current screen's UI tree (class, text, description, view id, bounds [l,t,r,b], clickable/editable)."
    override val parameters = schema(emptyList(), "max_depth" to prop("integer", "Max tree depth (default 25)"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val tree = svc.screenTree((args.int("max_depth") ?: 25).coerceIn(1, 60)) ?: return errorJson("No active window")
        val s = tree.toJson().toString()
        if (s.contains("com.verdroid.app")) return buildJsonObject { put("note", "This is Farrow's own UI."); put("tree", s.take(60_000)) }.toString()
        return if (s.length > 60_000) buildJsonObject { put("truncated", true); put("tree", s.take(60_000)) }.toString() else s
    }
}

class ScreenTapTool : AgentTool {
    override val name = "screen_tap"
    override val description = PHONE + "Tap the screen at pixel coordinates (use the centre of a node's bounds from screen_read)."
    override val parameters = schema(listOf("x", "y"), "x" to prop("number", "X in pixels"), "y" to prop("number", "Y in pixels"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val x = args.num("x") ?: return errorJson("x is required")
        val y = args.num("y") ?: return errorJson("y is required")
        return buildJsonObject { put("ok", svc.tap(x.toFloat(), y.toFloat())) }.toString()
    }
}

class ScreenSwipeTool : AgentTool {
    override val name = "screen_swipe"
    override val description = PHONE + "Swipe from (x1,y1) to (x2,y2) in pixels."
    override val parameters = schema(listOf("x1", "y1", "x2", "y2"),
        "x1" to prop("number", "Start X"), "y1" to prop("number", "Start Y"),
        "x2" to prop("number", "End X"), "y2" to prop("number", "End Y"),
        "duration_ms" to prop("integer", "Duration (default 300)"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val c = listOf("x1", "y1", "x2", "y2").map { args.num(it) ?: return errorJson("$it is required") }
        val ok = svc.swipe(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), (args.int("duration_ms") ?: 300).toLong())
        return buildJsonObject { put("ok", ok) }.toString()
    }
}

class ScreenTypeTool : AgentTool {
    override val name = "screen_type"
    override val description = PHONE + "Set the text of the focused/first editable field on screen."
    override val parameters = schema(listOf("text"), "text" to prop("string", "Text to enter"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val text = args.str("text") ?: return errorJson("text is required")
        return buildJsonObject { put("ok", svc.setText(text)) }.toString()
    }
}

class ScreenGlobalActionTool : AgentTool {
    override val name = "screen_action"
    override val description = PHONE + "Perform a global action: back, home, recents, notifications, quick_settings, lock_screen, screenshot."
    override val parameters = schema(listOf("action"), "action" to prop("string", "back | home | recents | notifications | quick_settings | lock_screen | screenshot"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val action = when (args.str("action")) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return errorJson("unknown action")
        }
        return buildJsonObject { put("ok", svc.performGlobalAction(action)) }.toString()
    }
}

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
