package com.farrow.app.data.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SetupStep(
    val number: Int,
    val id: String,
    val title: String,
    val description: String,
    /** Full wrapped script (logging, ✅/❌ line, keeps the session open) — used for both Run and Copy. */
    val command: String,
    /** Step 1 must be pasted by hand (it is what enables RUN_COMMAND). */
    val runnable: Boolean = true,
    /** Shell condition that is true when the step is already done (Set up everything skips it). */
    val skipCheck: String? = null,
)

/** Builds the copyable / RUN_COMMAND-able setup commands for Termux + Firefox + Xvfb + TBP + the Farrow bridge. */
@Singleton
class BridgeInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: BridgeConfig,
    private val prefs: com.farrow.app.data.prefs.AppPrefs,
) {
    fun bridgeScript(): String = context.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }

    /** sha256 of the bridge file exactly as step 4's heredoc writes it (script trimmed + trailing newline). */
    fun bridgeSha(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((bridgeScript().trimEnd() + "\n").toByteArray()).joinToString("") { "%02x".format(it) }

    /** The single "Set up everything" script (steps 2–5 in one Termux session), starting at [from]. */
    fun setupAllScript(from: Int = 2): String {
        val s = steps().filter { it.runnable }
        return StepScripts.setupAll(s.map { it.number to it.command }, s.mapNotNull { st -> st.skipCheck?.let { st.number to it } }.toMap(), from)
    }

    fun skipChecks(): Map<Int, String> = steps().mapNotNull { st -> st.skipCheck?.let { st.number to it } }.toMap()

    fun installedQuery(): String = StepScripts.installedQuery(skipChecks())

    /** Step 5 alone, without waiting for Enter (used by the bridge auto-start). */
    /** VERSION of the bundled bridge script (asset). */
    val bundledVersion: String? by lazy { runCatching { BridgeVersions.parse(bridgeScript()) }.getOrNull() }

    /** Background bridge update: rewrite ~/.farrow/tbp_bridge.py (step 4) and restart the bridge (step 5). */
    fun updateBridgeScript(): String = "export FARROW_NO_WAIT=1\n" + steps().first { it.number == 4 }.command + "\n" + steps().first { it.number == 5 }.command

    fun startBridgeScript(): String = "export FARROW_NO_WAIT=1\n" + steps().first { it.number == 5 }.command

    fun steps(): List<SetupStep> {
        val port = config.port
        return listOf(
            SetupStep(1, "allow", "1. Allow Farrow to run Termux commands",
                "Paste this once into Termux. It enables allow-external-apps so the Run buttons work.",
                StepScripts.wrap(1, "allow external apps", """
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties
termux-reload-settings
grep '^allow-external-apps' ~/.termux/termux.properties
""".trim()), runnable = false),
            SetupStep(2, "packages", "2. Install Firefox, Xvfb and tools",
                "Waits for any running apt/dpkg (max 2 min), repairs dpkg, then installs non-interactively from x11-repo / tur-repo.",
                StepScripts.wrap(2, "install packages", """
farrow_wait_apt || return 1
${'$'}APT update || return 1
${'$'}APT install x11-repo tur-repo || return 1
${'$'}APT update || return 1
${'$'}APT install git python firefox xorg-server-xvfb xdotool xclip openbox ca-certificates || return 1
python --version && firefox --version 2>/dev/null | head -n 1
""".trim(), usesApt = true),
                skipCheck = "command -v python && command -v git && command -v firefox && command -v Xvfb && command -v xdotool"),
            SetupStep(3, "tbp", "3. Install Termux Browser Pilot (tbp)",
                "github.com/salviz/termux-browser-pilot — provides the `tbp` CLI and daemon.",
                StepScripts.wrap(3, "install Termux Browser Pilot", """
farrow_wait_apt || return 1
if [ -d ~/termux-browser-pilot/.git ]; then git -C ~/termux-browser-pilot pull --ff-only || return 1
else git clone https://github.com/salviz/termux-browser-pilot ~/termux-browser-pilot || return 1; fi
bash ~/termux-browser-pilot/setup.sh || return 1
command -v tbp >/dev/null || { echo "tbp command not found after setup.sh"; return 1; }
tbp --version 2>/dev/null || true
""".trim(), usesApt = true),
                skipCheck = "command -v tbp && [ -d ~/termux-browser-pilot ]"),
            SetupStep(4, "bridge", "4. Install the Farrow bridge",
                "Writes ~/.farrow/tbp_bridge.py (Python standard library only) and the shared token.",
                StepScripts.wrap(4, "install bridge", installBridgeBody()),
                // Same script version (sha256 of the bundled asset) and the same token → nothing to do.
                skipCheck = "[ \"$(sha256sum ~/.farrow/tbp_bridge.py | cut -c1-64)\" = ${bridgeSha()} ] && [ \"$(cat ~/.farrow/token)\" = ${config.token} ]"),
            SetupStep(5, "start", "5. Start the bridge",
                "Starts the bridge detached on 127.0.0.1:$port, waits until the port answers, then starts the tbp daemon. Re-run after a reboot.",
                StepScripts.wrap(5, "start bridge", StepScripts.startBridgeBody(port, prefs.browserLanguage.value))),
        )
    }

    private fun installBridgeBody(): String = buildString {
        append("mkdir -p ~/.farrow/sessions || return 1\n")
        append("cat > ~/.farrow/tbp_bridge.py <<'$HEREDOC'\n")
        append(bridgeScript().trimEnd())
        append("\n$HEREDOC\n")
        append("printf '%s' '${config.token}' > ~/.farrow/token && chmod 600 ~/.farrow/token || return 1\n")
        append("python -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' ~/.farrow/tbp_bridge.py || { echo 'bridge script is corrupt'; return 1; }\n")
        append("echo 'Bridge installed in ~/.farrow'")
    }


    companion object {
        const val BRIDGE_ASSET = "tbp_bridge.py"
        private const val HEREDOC = "FARROW_BRIDGE_EOF"
    }
}
