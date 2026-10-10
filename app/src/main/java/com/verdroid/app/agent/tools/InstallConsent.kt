package com.verdroid.app.agent.tools

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** One package an agent tool wants to install: [manager] is apt (also Termux pkg), pip, npm, gem, cargo or deb. */
data class PackageSpec(val manager: String, val name: String) {
    override fun toString() = "$manager:$name"

    companion object {
        /** "apt:poppler pip:pypdf" (space-separated manager:name tokens, as the Termux probes print them). */
        fun parseList(s: String?): List<PackageSpec> = s.orEmpty().split(Regex("\\s+")).mapNotNull { t ->
            val m = t.substringBefore(':', ""); val n = t.substringAfter(':', "")
            if (m.isEmpty() || n.isEmpty()) null else PackageSpec(m, n)
        }.distinct()
    }
}

/**
 * Agent-driven package installs (pip / apt / pkg / npm / … inside Termux) need the user's yes first, like
 * ebook_translate's ETA step. A tool that would install returns a pending payload ([pending]) listing the packages,
 * why, an approximate cost and a deterministic [installId]; nothing is installed. The model asks the user (agree/deny)
 * and only then calls again with `confirm_install=true` + that `install_id`. `confirm_install=false` → [denied].
 * Settings > Tools add-ons (the user tapped Install) don't go through here: that tap is the consent.
 * Pure Kotlin (JVM-tested); the Termux glue lives in the tools.
 */
object InstallConsent {
    const val PARAM = "confirm_install"
    const val ID_PARAM = "install_id"

    val CONFIRM_PROP = PARAM to prop("boolean", "Only after the user agreed to the packages listed in a needs_install_confirmation " +
        "result: true = install them now (with install_id); false = the user declined. Omit otherwise")
    val ID_PROP = ID_PARAM to prop("string", "The install_id from the needs_install_confirmation result the user agreed to")

    sealed interface Decision {
        /** No consent yet: show the pending payload, install nothing. */
        data object Ask : Decision
        /** The user declined. */
        data object Deny : Decision
        /** The user agreed to exactly these packages. */
        data object Allow : Decision
    }

    /** What the arguments say about installing [packages] for [group] (a tool name or a tool family such as "pdf"). */
    fun decide(args: JsonObject, group: String, packages: List<PackageSpec>): Decision = when {
        packages.isEmpty() -> Decision.Allow
        args.bool(PARAM) == false -> Decision.Deny
        args.bool(PARAM) == true && args.str(ID_PARAM)?.trim() == installId(group, packages) -> Decision.Allow
        else -> Decision.Ask
    }

    /** Short stable id for this exact package set (order-insensitive), so a "yes" only covers what the user saw. */
    fun installId(group: String, packages: List<PackageSpec>): String {
        val key = group + "|" + packages.map { it.toString() }.distinct().sorted().joinToString(",")
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }

    /** "apt: python, poppler; pip: pypdf" */
    fun describe(packages: List<PackageSpec>): String =
        packages.groupBy { it.manager }.entries.joinToString("; ") { (m, l) -> "$m: " + l.joinToString(", ") { it.name } }

    /** Rough download size / time; null-safe for unknown packages. */
    fun estimate(packages: List<PackageSpec>): String {
        val mb = packages.map { APPROX_MB[it.name.substringBefore('>').substringBefore('=').lowercase()] }
        val known = mb.filterNotNull().sum()
        return if (mb.all { it != null }) "about $known MB, usually 1–5 min in Termux"
        else "size/time unknown${if (known > 0) " (at least ~$known MB)" else ""}; usually a few minutes in Termux"
    }

    fun pending(tool: String, group: String, packages: List<PackageSpec>, reason: String, retryNote: String? = null): JsonObject {
        val id = installId(group, packages)
        val what = describe(packages)
        return buildJsonObject {
            put("ok", false)
            put("needs_install_confirmation", true)
            put("tool", tool)
            put("install_id", id)
            put("packages", JsonArray(packages.map { JsonPrimitive(it.toString()) }))
            put("reason", reason)
            put("estimate", estimate(packages))
            put("message", (retryNote?.let { "$it " } ?: "") + "Nothing installed yet. Ask the user to agree or deny: " +
                "\"$tool needs to install $what — $reason (${estimate(packages)}). Install?\" Then STOP and wait for their " +
                "answer. Only if they agree, call $tool again with the same arguments plus $PARAM=true and $ID_PARAM=\"$id\". " +
                "If they deny, don't install (no other tool, no workaround); continue without it or explain what can't be done.")
        }
    }

    fun denied(tool: String, packages: List<PackageSpec>): JsonObject = buildJsonObject {
        put("ok", false)
        put("install_denied", true)
        put("tool", tool)
        put("packages", JsonArray(packages.map { JsonPrimitive(it.toString()) }))
        put("message", "The user declined installing ${describe(packages)}. Nothing was installed and nothing was run. " +
            "Don't retry or install it another way; continue without it or tell the user what isn't possible. They can " +
            "install it themselves later in Settings > Tools.")
    }

    /** Note added when confirm_install=true came with a missing or stale install_id. */
    fun retryNote(args: JsonObject): String? =
        if (args.bool(PARAM) == true) "The install_id is missing or doesn't match these packages (the list changed), so the user must confirm this list." else null

    // ---- Termux probes for tools with a built-in setup (pdf_*, ebook_translate) -------------------------------------

    /** Shell tail of a probe: if `${'$'}verdroid_m` lists missing packages, print `<marker>{"ok":false,"needs_install":true,…}` and exit 5. */
    fun probeExit(marker: String): String =
        "verdroid_m=${'$'}(echo ${'$'}verdroid_m)\nif [ -n \"${'$'}verdroid_m\" ]; then printf '%s{\"ok\":false,\"needs_install\":true,\"missing\":\"%s\"}\\n' " +
            "'$marker' \"${'$'}verdroid_m\"; exit 5; fi"

    /** The packages a probe reported missing, or null when [o] isn't a probe result. */
    fun missingFrom(o: JsonObject): List<PackageSpec>? =
        if (o["needs_install"]?.jsonPrimitive?.booleanOrNull == true) PackageSpec.parseList(o["missing"]?.jsonPrimitive?.contentOrNull) else null

    private val APPROX_MB = mapOf(
        "python" to 30, "python-pip" to 5, "python-pymupdf" to 25, "poppler" to 15, "pypdf" to 2,
        "googletrans" to 3, "deep-translator" to 1, "langdetect" to 2, "mobi" to 1, "ebooklib" to 1, "python-docx" to 2,
        "selenium" to 10, "requests" to 1, "beautifulsoup4" to 1, "lxml" to 10,
    )

    // ---- Detecting installs in agent-written shell / Python ------------------------------------------------------

    private val PIP = Regex("pip(\\d+(\\.\\d+)?)?")
    private val PYTHON = Regex("python(\\d+(\\.\\d+)?)?")
    private val SHELLS = setOf("bash", "sh", "zsh", "dash", "su")
    private val PREFIXES = setOf("sudo", "command", "exec", "nohup", "nice", "env", "time", "builtin", "eval", "xargs", "yes")
    private val PIP_VALUE_OPTS = setOf("-r", "--requirement", "-c", "--constraint", "-t", "--target", "-i", "--index-url",
        "--extra-index-url", "-f", "--find-links", "--prefix", "--root", "--src", "--platform", "--python-version",
        "--implementation", "--abi", "--progress-bar", "--trusted-host", "--cache-dir", "--log", "--proxy", "--timeout", "--retries")
    private val APT_VALUE_OPTS = setOf("-o", "--option", "-t", "--target-release", "-c", "--config-file")
    private val APT_INSTALL = setOf("install", "reinstall")
    private val APT_UPGRADE = setOf("upgrade", "full-upgrade", "dist-upgrade")

    /**
     * Best-effort list of packages a shell [command] would install (pip/pip3/python -m pip/uv pip/pipx, apt/apt-get/pkg
     * install|reinstall|upgrade, dpkg -i, npm/pnpm/yarn install|add, gem install, cargo install), including inside
     * `bash -c "…"`. Empty = no install. Obfuscated commands can slip through; the system prompt rule still applies.
     */
    fun detectShell(command: String, depth: Int = 0): List<PackageSpec> {
        if (depth > 3) return emptyList()
        val out = mutableListOf<PackageSpec>()
        for (segment in segments(command)) {
            var t = stripRedirects(tokenize(segment))
            // Strip env assignments and wrappers (sudo, timeout N, nohup, …).
            while (t.isNotEmpty()) {
                val h = t.first().substringAfterLast('/')
                t = when {
                    Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(t.first()) -> t.drop(1)
                    h == "timeout" -> t.drop(1).dropWhile { it.startsWith("-") || it.matches(Regex("\\d+[smhd]?")) }
                    h in PREFIXES -> t.drop(1).dropWhile { it.startsWith("-") }
                    else -> break
                }
            }
            if (t.isEmpty()) continue
            val h = t.first().let { if (it.any(Char::isWhitespace)) it else it.substringAfterLast('/') }
            when {
                t.size == 1 && h.any(Char::isWhitespace) -> out += detectShell(t.first(), depth + 1) // eval "pip install x"
                h in SHELLS -> t.drop(1).firstOrNull { !it.startsWith("-") }?.let { out += detectShell(it, depth + 1) }
                PIP.matches(h) -> out += pip(t.drop(1))
                PYTHON.matches(h) && t.getOrNull(1) == "-m" && t.getOrNull(2)?.let { PIP.matches(it) } == true -> out += pip(t.drop(3))
                h == "-m" && t.getOrNull(1)?.let { PIP.matches(it) } == true -> out += pip(t.drop(2))
                h == "uv" && t.getOrNull(1) == "pip" -> out += pip(t.drop(2))
                h == "pipx" && t.getOrNull(1) == "install" -> out += t.drop(2).filter { !it.startsWith("-") }.map { PackageSpec("pip", it) }
                h == "ensurepip" -> out += PackageSpec("pip", "pip (ensurepip)")
                h == "apt" || h == "apt-get" || h == "pkg" || h == "aptitude" -> out += apt(t.drop(1))
                h == "dpkg" && t.any { it == "-i" || it == "--install" } ->
                    out += t.drop(1).filter { !it.startsWith("-") }.map { PackageSpec("deb", it) }
                h == "npm" || h == "pnpm" || h == "yarn" || h == "bun" -> out += npm(t.drop(1))
                h == "gem" && t.getOrNull(1) == "install" -> out += named("gem", t.drop(2), emptySet())
                h == "cargo" && t.getOrNull(1) == "install" -> out += named("cargo", t.drop(2), setOf("--git", "--path", "--version", "--root"))
            }
        }
        return out.distinct()
    }

    /**
     * Installs inside a Python script: shell strings (os.system / subprocess with a string) and argument lists such as
     * `[sys.executable, "-m", "pip", "install", "x"]`, plus pip's Python API / ensurepip.
     */
    fun detectPython(code: String): List<PackageSpec> {
        val out = mutableListOf<PackageSpec>()
        val lit = Regex("'''(.*?)'''|\"\"\"(.*?)\"\"\"|'((?:[^'\\\\\\n]|\\\\.)*)'|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        fun literals(s: String) = lit.findAll(s).map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }.toList()
        literals(code).forEach { out += detectShell(it) }
        Regex("[\\[(]([^\\[\\]()]*)[\\])]").findAll(code).forEach { m ->
            val parts = literals(m.groupValues[1]).filter { it.isNotEmpty() }
            if (parts.size >= 2) out += detectShell(parts.joinToString(" ") { if (it.any(Char::isWhitespace)) "'$it'" else it })
        }
        if (Regex("\\bpip\\s*\\.\\s*main\\s*\\(|pip\\._internal|\\bensurepip\\b|\\bpip\\s*\\.\\s*_?internal").containsMatchIn(code))
            out += PackageSpec("pip", "(via pip's Python API)")
        return out.distinct()
    }

    private fun pip(args: List<String>): List<PackageSpec> {
        val sub = firstNonOption(args, PIP_VALUE_OPTS) ?: return emptyList()
        if (args[sub] != "install") return emptyList()
        val rest = args.drop(sub + 1)
        val out = mutableListOf<PackageSpec>()
        var i = 0
        while (i < rest.size) {
            val a = rest[i]
            when {
                a == "-r" || a == "--requirement" -> { rest.getOrNull(i + 1)?.let { out += PackageSpec("pip", "requirements:$it") }; i++ }
                a.startsWith("--requirement=") -> out += PackageSpec("pip", "requirements:" + a.substringAfter('='))
                a == "-e" || a == "--editable" -> { rest.getOrNull(i + 1)?.let { out += PackageSpec("pip", it) }; i++ }
                a in PIP_VALUE_OPTS -> i++
                a.startsWith("-") -> {}
                else -> out += PackageSpec("pip", a)
            }
            i++
        }
        return out.ifEmpty { listOf(PackageSpec("pip", "(unspecified)")) }
    }

    private fun apt(args: List<String>): List<PackageSpec> {
        val sub = firstNonOption(args, APT_VALUE_OPTS) ?: return emptyList()
        return when (args[sub]) {
            in APT_INSTALL -> named("apt", args.drop(sub + 1), APT_VALUE_OPTS).ifEmpty { listOf(PackageSpec("apt", "(unspecified)")) }
            in APT_UPGRADE -> listOf(PackageSpec("apt", "upgrade (all upgradable packages)"))
            else -> emptyList()
        }
    }

    private fun npm(args: List<String>): List<PackageSpec> {
        val sub = firstNonOption(args, setOf("--prefix")) ?: return emptyList()
        val s = args[sub]
        val rest = when {
            s in setOf("install", "i", "in", "add", "ci") -> args.drop(sub + 1)
            s == "global" && args.getOrNull(sub + 1) == "add" -> args.drop(sub + 2) // yarn global add x
            else -> return emptyList()
        }
        return named("npm", rest, setOf("--prefix", "--registry")).ifEmpty { listOf(PackageSpec("npm", "(project dependencies)")) }
    }

    private fun named(manager: String, args: List<String>, valueOpts: Set<String>): List<PackageSpec> {
        val out = mutableListOf<PackageSpec>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a in valueOpts) i++ else if (!a.startsWith("-")) out += PackageSpec(manager, a)
            i++
        }
        return out
    }

    private fun firstNonOption(args: List<String>, valueOpts: Set<String>): Int? {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a in valueOpts) i += 2 else if (a.startsWith("-")) i++ else return i
        }
        return null
    }

    /** Splits on ; && || | & newlines and subshell/command-substitution brackets (quotes respected). */
    internal fun segments(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        var q: Char? = null; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                q != null -> { cur.append(c); if (c == q) q = null else if (c == '\\' && q == '"' && i + 1 < s.length) { cur.append(s[i + 1]); i++ } }
                c == '\'' || c == '"' -> { q = c; cur.append(c) }
                c == '\\' && i + 1 < s.length -> { cur.append(c).append(s[i + 1]); i++ }
                c in ";|&\n()`{}" || (c == '$' && s.getOrNull(i + 1) == '(') -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Drops redirections: `2>/dev/null`, `> out.txt`, `<in`, a dangling `2>` left by splitting `2>&1` at `&`. */
    private fun stripRedirects(t: List<String>): List<String> {
        val out = mutableListOf<String>(); var i = 0
        val op = Regex("^\\d*(>>?|<<?|&>)$"); val inline = Regex("^\\d*(>>?|<<?|&>).+")
        while (i < t.size) {
            when {
                op.matches(t[i]) -> i++ // operator alone: also skip its target
                inline.matches(t[i]) -> {}
                else -> out += t[i]
            }
            i++
        }
        return out
    }

    /** Whitespace split with '…' / "…" quotes and backslash escapes removed. */
    internal fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        var q: Char? = null; var has = false; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                q != null -> if (c == q) q = null else if (c == '\\' && q == '"' && i + 1 < s.length) { cur.append(s[i + 1]); i++ } else cur.append(c)
                c == '\'' || c == '"' -> { q = c; has = true }
                c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++; has = true }
                c.isWhitespace() -> { if (has || cur.isNotEmpty()) out += cur.toString(); cur.clear(); has = false }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
