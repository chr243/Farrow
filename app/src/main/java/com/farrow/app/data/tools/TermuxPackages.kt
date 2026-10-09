package com.farrow.app.data.tools

/** Optional Termux packages the agent can use through the termux_run tool (installed with pkg in the background). */
data class TermuxPackage(
    val pkg: String,
    val binary: String,
    val description: String,
    /** Shell test that succeeds when installed (default: [binary] on PATH). */
    val detect: String = "command -v $binary >/dev/null 2>&1",
    /** Custom install commands (run after [TermuxPackages.aptPrelude]); null = `apt-get install <pkg>`. */
    val install: String? = null,
)

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
        SELENIUM,
        EBOOK_TRANSLATE,
    )

    /** Headless Chromium + chromedriver (TUR via x11-repo/tur-repo) and Python Selenium, for selenium_* and termux_python. */
    val SELENIUM: TermuxPackage get() = TermuxPackage(
        pkg = "chromium-selenium",
        binary = "chromedriver",
        description = "Headless Chromium + Selenium (Python) for selenium_* tools and your own scrapers (large, ~300 MB)",
        detect = "{ command -v chromium-browser >/dev/null 2>&1 || command -v chromium >/dev/null 2>&1; } && " +
            "command -v chromedriver >/dev/null 2>&1 && python3 -c 'import selenium' >/dev/null 2>&1",
        install = listOf(
            "${'$'}APT install x11-repo tur-repo 2>&1 | tail -n 5",
            "(apt-get update -q >/dev/null 2>&1 || true)",
            "${'$'}APT install python python-pip chromium 2>&1 | tail -n 10",
            "command -v chromedriver >/dev/null 2>&1 || ${'$'}APT install chromedriver 2>&1 | tail -n 5",
            "pip install -U selenium 2>&1 | tail -n 5",
        ).joinToString("\n"),
    )

    /** Python stack for ebook_translate: googletrans>=4.0.2, deep-translator (MyMemory), mobi, ebooklib, python-docx, langdetect + poppler. */
    val EBOOK_TRANSLATE: TermuxPackage get() = TermuxPackage(
        pkg = "ebook-translate",
        binary = "python3",
        description = "Ebook/document translation (MOBI/EPUB/PDF/DOCX) for ebook_translate — pip: googletrans>=4.0.2, deep-translator, mobi, ebooklib, python-docx, langdetect; apt: poppler (installed automatically on first use too)",
        detect = "python3 -c 'import googletrans,deep_translator,ebooklib,docx,langdetect,mobi' >/dev/null 2>&1 && " +
            "[ -s '${com.farrow.app.data.ebook.EbookTranslatePy.FILE}' ]",
        install = listOf(
            "${'$'}APT install python python-pip poppler 2>&1 | tail -n 5",
            "pip install -U ${com.farrow.app.data.ebook.EbookTranslatePy.PIP_CORE} mobi ebooklib python-docx 2>&1 | tail -n 15",
            // Deploy the translator script too (ebook_translate also rewrites it before every run).
            com.farrow.app.data.ebook.EbookTranslatePy.installCommand(),
        ).joinToString("\n"),
    )

    /** Shared storage written by Termux scrapers (needs `termux-setup-storage` once inside Termux). */
    const val STORAGE_PROBE_DIR = "/storage/emulated/0/Documents"

    /** Background query: P_<pkg>=1/0 per package, STORAGE=1/0 (Termux can write shared storage), then PROBE=ok. */
    fun detectQuery(list: List<TermuxPackage> = ALL): String =
        list.joinToString(" ") { "if ${it.detect}; then echo P_${key(it.pkg)}=1; else echo P_${key(it.pkg)}=0; fi;" } +
            " if [ -w $STORAGE_PROBE_DIR ]; then echo STORAGE=1; else echo STORAGE=0; fi; echo PROBE=ok"

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
        (p.install?.let { "$it\nrc=${'$'}?\n" } ?: "${'$'}APT install ${p.pkg} 2>&1 | tail -n 20; rc=${'$'}{PIPESTATUS[0]}\n") +
        "if ${p.detect}; then echo INSTALLED=1; else echo INSTALLED=0; fi\necho RESULT=${'$'}rc"

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
