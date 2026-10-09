package com.farrow.app.data.tools

/** What the phone currently offers; [ToolStatus.of] turns it into "ready" or what a tool still needs. */
data class ToolEnv(
    val shizukuReady: Boolean = false,
    /** Termux installed and Farrow holds its RUN_COMMAND permission. */
    val termuxReady: Boolean = false,
    val accessibilityOn: Boolean = false,
    val gitToken: Boolean = false,
    /** All files access granted, so Documents/Farrow is usable. */
    val storageReady: Boolean = false,
)

data class ToolStatus(val ready: Boolean, val text: String) {
    companion object {
        private val READY = ToolStatus(true, "Ready")

        fun of(name: String, env: ToolEnv): ToolStatus = when {
            name in setOf("read_file", "write_file", "list_dir") -> READY
            name.startsWith("workspace_") -> if (env.storageReady) READY
                else ToolStatus(false, "Needs All files access for Documents/Farrow (Settings > Tools > Shared folder)")
            name == "web_fetch" || name == "web_search" -> READY
            name.startsWith("crypto_") -> when (name) {
                "crypto_place_order", "crypto_cancel_order" -> ToolStatus(true, "Live trading — off by default; needs a Coinbase Exchange API key (Settings)")
                "crypto_balance", "crypto_order_status" -> ToolStatus(true, "Needs a Coinbase Exchange API key (Settings > Shizuku, accessibility & Git)")
                else -> READY // public market data / backtest
            }
            name.startsWith("selenium_") || name == "termux_python" ->
                if (env.termuxReady) ToolStatus(true, "Ready if chromium-selenium is installed (Available to install)" +
                    if (name == "termux_python") "" else "; saving files needs termux-setup-storage in Termux")
                else ToolStatus(false, "Needs Termux + the chromium-selenium add-on (Settings > Tools)")
            name == "termux_run" -> if (env.termuxReady) ToolStatus(true, "Ready if allow-external-apps is on in Termux")
                else ToolStatus(false, "Needs Termux and the Run commands permission (Settings > Tools > Termux)")
            name == "run_shell" -> if (env.shizukuReady) READY else ToolStatus(false, "Needs Shizuku running with permission (Settings > Shizuku, accessibility & Git)")
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
