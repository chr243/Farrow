package com.farrow.app.data.tools

/** Optional Termux packages the agent can use through the termux_run tool (installed with pkg in the background). */
data class TermuxPackage(val pkg: String, val binary: String, val description: String)

object TermuxPackages {
    val ALL = listOf(
        TermuxPackage("ffmpeg", "ffmpeg", "Convert, cut and compress audio/video (ffmpeg, ffprobe)"),
        TermuxPackage("imagemagick", "magick", "Resize, convert and edit images (magick)"),
        TermuxPackage("yt-dlp", "yt-dlp", "Download video/audio from YouTube and many other sites"),
        TermuxPackage("git", "git", "Git command line inside Termux (git_* tools use the built-in JGit)"),
        TermuxPackage("nodejs", "node", "Run JavaScript with Node.js and npm"),
        TermuxPackage("jq", "jq", "Filter and transform JSON on the command line"),
        TermuxPackage("curl", "curl", "HTTP requests and downloads from the command line"),
        TermuxPackage("pandoc", "pandoc", "Convert documents (Markdown, HTML, DOCX, …)"),
    )

    /** Background query: P_<pkg>=1/0 per package, then PROBE=ok. */
    fun detectQuery(list: List<TermuxPackage> = ALL): String =
        list.joinToString(" ") { "if command -v ${it.binary} >/dev/null 2>&1; then echo P_${key(it.pkg)}=1; else echo P_${key(it.pkg)}=0; fi;" } +
            " echo PROBE=ok"

    fun parseDetect(stdout: String, list: List<TermuxPackage> = ALL): Map<String, Boolean>? {
        val k = parseKeys(stdout)
        if (k["PROBE"] != "ok") return null
        return list.associate { it.pkg to (k["P_${key(it.pkg)}"] == "1") }
    }

    /** KEY=value lines (before an optional "---" separator). */
    fun parseKeys(stdout: String): Map<String, String> =
        stdout.substringBefore("\n---").lineSequence().filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    /** Background install (no Termux window): waits for other apt runs, installs, prints INSTALLED=1/0 and RESULT=<exit>. */
    fun installScript(p: TermuxPackage): String = aptPrelude() + "\n" +
        "farrow_wait_apt >/dev/null 2>&1 || true\n(apt-get update -q >/dev/null 2>&1 || true)\n" +
        "${'$'}APT install ${p.pkg} 2>&1 | tail -n 20; rc=${'$'}{PIPESTATUS[0]}\n" +
        "command -v ${p.binary} >/dev/null 2>&1 && echo INSTALLED=1 || echo INSTALLED=0\necho RESULT=${'$'}rc"

    /** Non-interactive apt + a helper that waits (≤ [maxSeconds]) for other apt/dpkg runs, then repairs dpkg. */
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
      return 1
    fi
    sleep 1; i=$((i + 1))
  done
  dpkg --configure -a
}
""".trim()

    private fun key(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9]"), "_")
}
