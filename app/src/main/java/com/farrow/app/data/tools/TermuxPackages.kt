package com.farrow.app.data.tools

import com.farrow.app.data.browser.StepScripts

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
        val k = StepScripts.parseKeys(stdout)
        if (k["PROBE"] != "ok") return null
        return list.associate { it.pkg to (k["P_${key(it.pkg)}"] == "1") }
    }

    /** Background install (no Termux window): waits for other apt runs, installs, prints RESULT=<exit>. */
    fun installScript(p: TermuxPackage): String = StepScripts.aptPrelude() + "\n" +
        "farrow_wait_apt >/dev/null 2>&1 || true\n(apt-get update -q >/dev/null 2>&1 || true)\n" +
        "${'$'}APT install ${p.pkg} 2>&1 | tail -n 20; rc=${'$'}{PIPESTATUS[0]}\n" +
        "command -v ${p.binary} >/dev/null 2>&1 && echo INSTALLED=1 || echo INSTALLED=0\necho RESULT=${'$'}rc"

    private fun key(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9]"), "_")
}
