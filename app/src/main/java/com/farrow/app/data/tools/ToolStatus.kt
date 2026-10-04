package com.farrow.app.data.tools

/** What the phone currently offers; [ToolStatus.of] turns it into "ready" or what a tool still needs. */
data class ToolEnv(
    val bridgeUp: Boolean = false,
    val browserUp: Boolean = false,
    val shizukuReady: Boolean = false,
    val accessibilityOn: Boolean = false,
    val gitToken: Boolean = false,
)

data class ToolStatus(val ready: Boolean, val text: String) {
    companion object {
        private val READY = ToolStatus(true, "Ready")

        fun of(name: String, env: ToolEnv): ToolStatus = when {
            name in setOf("read_file", "write_file", "list_dir") -> READY
            name == "web_scrape" -> if (env.browserUp) READY else ToolStatus(true, "Ready (plain HTTP fallback) — full browser needs Settings > Internal browser setup")
            name.startsWith("web_") -> if (env.browserUp) READY else ToolStatus(false, "Needs the internal browser (Settings > Internal browser setup)")
            name.startsWith("x_") -> if (env.browserUp) ToolStatus(true, "Ready if logged in (Settings > X.com account)")
                else ToolStatus(false, "Needs the internal browser and an X login (Settings > X.com account)")
            name.startsWith("fb_") -> if (env.browserUp) ToolStatus(true, "Ready if logged in (Settings > Facebook account)")
                else ToolStatus(false, "Needs the internal browser and a Facebook login (Settings > Facebook account)")
            name == "run_shell" -> if (env.shizukuReady) READY else ToolStatus(false, "Needs Shizuku running with permission (Settings > Shizuku, accessibility & Git)")
            name == "termux_run" -> if (env.bridgeUp) READY else ToolStatus(false, "Needs the Termux bridge (Settings > Internal browser setup)")
            name == "git_push" || name == "git_clone" -> if (env.gitToken) READY
                else ToolStatus(true, "Ready for public repos — private ones need a Git token (Settings > Shizuku, accessibility & Git)")
            name.startsWith("git_") -> READY
            name.startsWith("screen_") -> if (env.accessibilityOn) READY else ToolStatus(false, "Needs the Farrow accessibility service (Settings > Shizuku, accessibility & Git)")
            else -> READY
        }

        /** First sentence of a tool description, for the list. */
        fun short(description: String): String {
            val i = Regex("""\.(\s|$)""").find(description)?.range?.first
            return (if (i != null && i < 160) description.substring(0, i + 1) else description.take(160)).trim()
        }
    }
}
