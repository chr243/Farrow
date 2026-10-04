package com.farrow.app.data.browser

/**
 * Builds the bash scripts for the Termux setup wizard. Every step is wrapped so that it:
 *  - tees all output to ~/.farrow/logs/step-N.log and writes the exit code to ~/.farrow/logs/step-N.status
 *    ("running" while in progress),
 *  - prints a final "✅ Step N succeeded" / "❌ Step N failed (exit X)" line,
 *  - in a visible Termux session: on success returns to Farrow and exits 0 (Termux closes the session); on failure
 *    keeps it open (read -p) so the error can be read. Background RUN_COMMAND runs never wait.
 * The same text is used for "Run in Termux" (RUN_COMMAND) and for the copyable command.
 */
object StepScripts {
    const val LOG_DIR = "~/.farrow/logs"

    const val APP_COMPONENT = "com.farrow.app/com.farrow.app.MainActivity"

    /**
     * Foreground (Termux UI) runs only: bring Farrow back to the front. `[ -t 0 ]` = we're in a Termux session with
     * a TTY (background RUN_COMMAND tasks have none). Termux removes a session that ends with exit code 0 and finishes
     * its activity when no session is left, so `exit 0` after this closes the Termux UI — the Termux app process and
     * its service keep running (the bridge holds termux-wake-lock and is detached with setsid/nohup).
     */
    val BACK_TO_APP = "farrow_back_to_app() { [ -t 0 ] || return 0; echo '↩️  Returning to Farrow…'; sleep 1; " +
        "am start --activity-reorder-to-front -n $APP_COMPONENT >/dev/null 2>&1 || true; }"

    fun logPath(n: Int) = "$LOG_DIR/step-$n.log"
    fun statusPath(n: Int) = "$LOG_DIR/step-$n.status"

    /** Waits up to [maxSeconds] for other apt/apt-get/dpkg processes, then repairs dpkg. Uses /proc only (no procps). */
    fun aptPrelude(maxSeconds: Int = 120): String = """
export DEBIAN_FRONTEND=noninteractive
APT="apt-get -y -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef"
farrow_apt_busy() {
  for f in /proc/[0-9]*/comm; do
    c=""; read -r c < "${'$'}f" 2>/dev/null || continue
    case "${'$'}c" in apt|apt-get|dpkg|unattended-upgr) return 0;; esac
  done
  return 1
}
farrow_wait_apt() {
  i=0
  while farrow_apt_busy; do
    if [ "${'$'}i" -ge $maxSeconds ]; then
      echo "⚠️  Another apt/dpkg is still running after ${maxSeconds}s and holds the dpkg lock."
      echo "    Close other Termux sessions that run pkg/apt (or run: pkill apt; pkill dpkg),"
      echo "    then run 'dpkg --configure -a' and retry this step."
      return 1
    fi
    if [ $((i % 10)) -eq 0 ]; then echo "⏳ Waiting for another apt/dpkg to finish (${'$'}{i}s / ${maxSeconds}s)…"; fi
    sleep 1; i=$((i + 1))
  done
  echo "🔧 dpkg --configure -a"
  dpkg --configure -a
}
""".trim()

    /**
     * One-time move of the state dir used by the app's previous name (sessions, bridge token, logs) to ~/.farrow, only if
     * ~/.farrow doesn't exist yet. The old name is assembled from two parts on purpose.
     */
    val MIGRATE_OLD_DIR = "[ -d ~/.farrow ] || { o=\"${'$'}HOME/.bob\"\"libot\"; [ -d \"${'$'}o\" ] && mv \"${'$'}o\" ~/.farrow; }; true"

    /** Wraps [body] (runs inside a bash function) for step [n]. */
    fun wrap(n: Int, title: String, body: String, usesApt: Boolean = false): String = buildString {
        append("$MIGRATE_OLD_DIR\n")
        append("mkdir -p $LOG_DIR\n")
        append("FARROW_LOG=${logPath(n)}; FARROW_ST=${statusPath(n)}\n")
        append("echo running > \"${'$'}FARROW_ST\"\n")
        if (usesApt) append(aptPrelude()).append('\n')
        append("farrow_step() {\n")
        append("echo \"▶️  Step $n: ${title.replace("\"", "'")}\"\n")
        append(body.trimEnd()).append('\n')
        append("}\n")
        append("farrow_step 2>&1 | tee \"${'$'}FARROW_LOG\"\n")
        append("FARROW_RC=${'$'}{PIPESTATUS[0]}\n")
        append("echo \"${'$'}FARROW_RC\" > \"${'$'}FARROW_ST\"\n")
        append("if [ \"${'$'}FARROW_RC\" -eq 0 ]; then echo \"✅ Step $n succeeded\" | tee -a \"${'$'}FARROW_LOG\"; ")
        append("else echo \"❌ Step $n failed (exit ${'$'}FARROW_RC) — log: ${logPath(n)}\" | tee -a \"${'$'}FARROW_LOG\"; fi\n")
        // Keep the session visible. RUN_COMMAND foreground sessions have a TTY; if read can't work, keep a shell open.
        // Foreground session only (a TTY): on success return to Farrow and close the session (exit 0); on failure keep
        // it open so the error can be read. Background tasks never wait.
        append("if [ -z \"${'$'}FARROW_NO_WAIT\" ] && [ -t 0 ]; then\n")
        append("  if [ \"${'$'}FARROW_RC\" -eq 0 ]; then $BACK_TO_APP; farrow_back_to_app; exit 0; fi\n")
        append("  read -r -p 'Press Enter to close' _ </dev/tty 2>/dev/null || exec bash\nfi\n")
    }

    /**
     * Never `pkill -f tbp_bridge.py` here: the bash running this step has the whole script (including that name)
     * in its argv, so pkill kills the step itself — the v0.9.1 "started but not reachable" bug. Even the
     * `[t]bp_bridge` bracket trick fails for that reason. We only kill processes whose comm is python*.
     */
    fun startBridgeBody(port: Int, lang: String = BrowserLanguage.DEFAULT): String = """
command -v python >/dev/null || { echo "python not found — run step 2"; return 1; }
[ -f ~/.farrow/tbp_bridge.py ] && [ -s ~/.farrow/token ] || { echo "bridge not installed — run step 4"; return 1; }
# Browser language (Firefox user.js intl.* prefs are written by the bridge before every daemon start)
printf '%s' '${BrowserLanguage.of(lang).code}' > ~/.farrow/browser_lang
termux-wake-lock 2>/dev/null || true
farrow_kill_bridge() { for p in ${'$'}(pgrep -f 'tbp_bridge[.]py' 2>/dev/null); do case "${'$'}(cat /proc/${'$'}p/comm 2>/dev/null)" in python*) kill ${'$'}p 2>/dev/null;; esac; done; }
farrow_kill_bridge; sleep 0.5
FARROW_DETACH=${'$'}(command -v setsid || true)
echo "--- ${'$'}(date) starting bridge" >> ~/.farrow/bridge.log
${'$'}FARROW_DETACH nohup python -u ~/.farrow/tbp_bridge.py --port $port >> ~/.farrow/bridge.log 2>&1 < /dev/null &
echo ${'$'}! > ~/.farrow/bridge.pid
farrow_port_up() { python -c 'import socket,sys;s=socket.socket();s.settimeout(1);sys.exit(0 if s.connect_ex(("127.0.0.1",$port))==0 else 1)'; }
echo "⏳ Waiting for 127.0.0.1:$port …"
i=0; while [ ${'$'}i -lt 20 ] && ! farrow_port_up; do sleep 0.5; i=${'$'}((i + 1)); done
if ! farrow_port_up; then echo "Bridge did not start listening. Last bridge.log lines:"; tail -n 30 ~/.farrow/bridge.log; return 1; fi
echo "Bridge listening on 127.0.0.1:$port"
# Never run `tbp start` directly: a second concurrent start loses daemon.pid and crashes on the browser lock.
# Ask the bridge (single-flight, cleans stale/orphan locks) instead.
if command -v tbp >/dev/null; then (${'$'}FARROW_DETACH nohup python -c 'import urllib.request as u,os;t=open(os.path.expanduser("~/.farrow/token")).read().strip();u.urlopen(u.Request("http://127.0.0.1:$port/daemon/start",data=b"{}",headers={"X-Bridge-Token":t}),timeout=90).read()' >/dev/null 2>&1 < /dev/null &); echo "tbp daemon starting via the bridge (log: ~/.farrow/tbp.log)"
else echo "⚠️  tbp not installed — run step 3"; fi
""".trim()

    /** The one manual command (RUN_COMMAND can't enable itself): idempotent allow-external-apps + reload. */
    const val ALLOW_ONE_LINER = "mkdir -p ~/.termux && (grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings && echo OK"

    const val ALL_STATUS = "$LOG_DIR/step-all.status"
    const val ALL_CURRENT = "$LOG_DIR/step-all.current"

    /**
     * "Set up everything": ONE Termux session that runs [steps] (wrapped scripts) in order starting at [from].
     * A step whose skip check ([skipChecks], a shell condition) passes is marked "skipped" without running.
     * Each step keeps its own step-N.log/.status; step-all.status ends as "0" or "<step>:<exit>" and
     * step-all.current holds the step being worked on, so the app can show live progress and retry from the failure.
     */
    fun setupAll(steps: List<Pair<Int, String>>, skipChecks: Map<Int, String>, from: Int): String = buildString {
        append("$MIGRATE_OLD_DIR\n")
        append("mkdir -p $LOG_DIR\nexport FARROW_NO_WAIT=1\necho running > $ALL_STATUS\n")
        append("echo '🚀 Farrow: setting up the internal browser (steps ${steps.filter { it.first >= from }.joinToString(", ") { it.first.toString() }})'\n")
        for ((n, script) in steps.filter { it.first >= from }) {
            append("echo $n > $ALL_CURRENT\n")
            val check = skipChecks[n]
            if (check != null) {
                append("if ( $check ) >/dev/null 2>&1; then\n")
                append("  echo skipped > ${statusPath(n)}; echo \"⏭️  Step $n already done — skipped\" | tee ${logPath(n)}\nelse\n")
            }
            append("(\n").append(script.trimEnd()).append("\n)\n")
            append("FARROW_ALL_RC=$(cat ${statusPath(n)} 2>/dev/null || echo 1)\n")
            append("if [ \"${'$'}FARROW_ALL_RC\" != 0 ]; then echo \"$n:${'$'}FARROW_ALL_RC\" > $ALL_STATUS\n")
            append("  echo \"❌ Setup stopped at step $n (exit ${'$'}FARROW_ALL_RC). Fix the problem, then tap Retry in Farrow.\"\n")
            append("  if [ -z \"${'$'}FARROW_ALL_NO_WAIT\" ] && [ -t 0 ]; then read -r -p 'Press Enter to close' _ </dev/tty 2>/dev/null || exec bash; fi; exit 1\nfi\n")
            if (check != null) append("fi\n")
        }
        append("echo 0 > $ALL_STATUS; echo done > $ALL_CURRENT\n")
        append("echo '✅ Internal browser set up — the bridge is running in the background.'\n")
        append("termux-wake-lock 2>/dev/null || true\n")
        append("$BACK_TO_APP\n[ -z \"${'$'}FARROW_ALL_NO_WAIT\" ] && farrow_back_to_app\nexit 0\n")
    }

    /** Status of the combined run: ALL=…, CUR=…, S<n>=… lines, then the current step's log tail. */
    fun setupAllQuery(steps: List<Int>, lines: Int = 30): String =
        "echo \"ALL=$(cat $ALL_STATUS 2>/dev/null || echo none)\"; c=$(cat $ALL_CURRENT 2>/dev/null); echo \"CUR=${'$'}c\"; " +
            steps.joinToString(" ") { "echo \"S$it=$(cat ${statusPath(it)} 2>/dev/null || echo none)\";" } +
            " echo '---'; [ -n \"${'$'}c\" ] && tail -n $lines $LOG_DIR/step-${'$'}c.log 2>/dev/null"

    /** Fast "what is already installed" probe: C<n>=1/0 per skip check, plus PROBE=ok (proves RUN_COMMAND works). */
    fun installedQuery(skipChecks: Map<Int, String>): String =
        skipChecks.entries.sortedBy { it.key }.joinToString(" ") { (n, c) -> "if ( $c ) >/dev/null 2>&1; then echo C$n=1; else echo C$n=0; fi;" } +
            " echo PROBE=ok"

    fun parseKeys(stdout: String): Map<String, String> =
        stdout.substringBefore("\n---").lineSequence().filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    /** Background command that reports a step's status + log tail (parsed by [parseStatus]). */
    fun statusQuery(n: Int, lines: Int = 50): String =
        "echo \"STATUS=$(cat ${statusPath(n)} 2>/dev/null || echo none)\"; echo '---'; tail -n $lines ${logPath(n)} 2>/dev/null"

    /** Background command that returns the bridge log tail and whether the port is listening. */
    fun bridgeLogQuery(port: Int, lines: Int = 50): String =
        "echo \"LISTENING=$(python3 -c 'import socket,sys;s=socket.socket();s.settimeout(1);sys.stdout.write(\"yes\" if s.connect_ex((\"127.0.0.1\",$port))==0 else \"no\")' 2>/dev/null || echo unknown)\"; " +
            "echo '---'; tail -n $lines ~/.farrow/bridge.log 2>/dev/null || echo '(no ~/.farrow/bridge.log yet)'"

    data class ParsedStatus(val status: String, val tail: String)

    fun parseStatus(stdout: String, key: String = "STATUS"): ParsedStatus {
        val first = stdout.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim() ?: "none"
        val tail = stdout.substringAfter("---\n", "").trimEnd()
        return ParsedStatus(first, tail)
    }
}
