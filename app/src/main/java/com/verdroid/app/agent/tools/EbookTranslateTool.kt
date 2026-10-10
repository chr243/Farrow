package com.verdroid.app.agent.tools

import com.verdroid.app.data.ebook.EbookTranslatePy
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*
import java.io.File

/**
 * Translates an ebook/document via Termux Python (MOBI first, then EPUB/PDF/DOCX/TXT).
 * Input must be under Documents/Farrow (usually Input/); output always under Output/.
 */
class EbookTranslateTool(
    private val termux: TermuxRunner,
    private val folder: SharedFolder,
) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)

    override val name = "ebook_translate"
    override val description = "Translate an ebook or document with Termux Python (MOBI preferred, also EPUB/PDF/DOCX/TXT). " +
        "Only call this when the user explicitly asked for a translation (and the target language); an attached file " +
        "on its own is not a request to translate — ask what they want instead. " +
        "googletrans (browser User-Agent) in <=4000-char chunks, ~0.3 s between requests, backoff on Too many requests, a 5-10 s pause every 4 chapters, " +
        "MyMemory fallback, language check, resume if interrupted. input_path is under Documents/Farrow (e.g. Input/book.mobi); " +
        "the result is always written under Output/. TWO STEPS: call first WITHOUT confirmed → returns chapters, chunks and an " +
        "estimated time. Tell the user the ETA and ask them to confirm; only after " +
        "they agree call again with confirmed=true (pass suggested_timeout_seconds as timeout_seconds). If Python packages are " +
        "missing it first returns needs_install_confirmation: ask the user, and only after they agree call again with " +
        "confirm_install=true + install_id. Needs Termux + termux-setup-storage."
    override val parameters = schema(listOf("input_path", "dest_lang"),
        "input_path" to prop("string", "Path relative to Documents/Farrow, e.g. Input/novel.mobi"),
        "src_lang" to prop("string", "Source language code (default auto)"),
        "dest_lang" to prop("string", "Target language code, e.g. fr, de, es, zh-CN"),
        "output_name" to prop("string", "Optional file name under Output/ (default: <stem>.<dest>.txt or .docx)"),
        "format" to prop("string", "Force format: mobi, epub, pdf, docx, txt"),
        "resume" to prop("boolean", "Resume a previous run if a state file exists (default true)"),
        "confirmed" to prop("boolean", "false/omitted = estimate only; true = the user confirmed the ETA, translate now"),
        "timeout_seconds" to prop("integer", "Max seconds for the translation (default 7200 = max; returns as soon as it finishes). " +
            "If the book needs longer, re-run with resume=true to continue"),
        InstallConsent.CONFIRM_PROP, InstallConsent.ID_PROP)

    override suspend fun execute(args: JsonObject): String {
        if (!termux.isInstalled()) return errorJson("Termux is not installed. Set it up in Settings > Tools.")
        if (!termux.hasRunCommandPermission()) return errorJson("Needs the Run commands in Termux permission (Settings > Tools / Permissions).")
        if (!folder.hasAccess() || !folder.ensure()) return errorJson("Needs All files access and Documents/Farrow (Settings > Permissions).")
        val rel = args.str("input_path")?.trim()?.takeIf { it.isNotEmpty() } ?: return errorJson("input_path is required")
        val dest = args.str("dest_lang")?.trim()?.takeIf { it.isNotEmpty() } ?: return errorJson("dest_lang is required")
        if (!LANG.matches(dest)) return errorJson("dest_lang must look like a language code (e.g. fr, en, zh-CN)")
        val src = args.str("src_lang")?.trim()?.ifEmpty { null } ?: "auto"
        if (src != "auto" && !LANG.matches(src)) return errorJson("src_lang must be auto or a language code")
        val input = try { sandbox.resolve(rel) } catch (e: SecurityException) { return errorJson(e.message ?: "Path not allowed") }
        if (!input.isFile) return errorJson("Input not found: ${sandbox.relativePath(input)}")
        val stem = input.nameWithoutExtension
        val defaultName = if (input.extension.equals("docx", true)) "$stem.$dest.docx" else "$stem.$dest.txt"
        val outRel = (args.str("output_name")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName)
            .let { if (it.startsWith("Output/")) it else "Output/$it" }
        val output = try { sandbox.resolve(outRel) } catch (e: SecurityException) { return errorJson(e.message ?: "Path not allowed") }
        if (output == sandbox.root || sandbox.relativePath(output) == "Output") return errorJson("output_name must be a file under Output/")
        val confirmed = args.bool("confirmed") == true
        val timeout = (args.int("timeout_seconds") ?: MAX_TIMEOUT).coerceIn(60, MAX_TIMEOUT)
        val resume = args.bool("resume") != false
        val fmt = args.str("format")?.trim()?.lowercase()
        val absIn = "${SharedFolder.DISPLAY_PATH}/${sandbox.relativePath(input)}"
        val absOut = "${SharedFolder.DISPLAY_PATH}/${sandbox.relativePath(output)}"
        val cli = buildList {
            add(EbookTranslatePy.FILE); add("--input"); add(absIn); add("--src"); add(src); add("--dest"); add(dest)
            add("--output"); add(absOut)
            if (fmt != null) { add("--format"); add(fmt) }
            if (!resume) add("--no-resume")
            if (!confirmed) add("--estimate")
        }
        val setupFmt = fmt ?: FORMATS[input.extension.lowercase()]
        val storageGuard = "mkdir -p ${TermuxRunTool.shellQuote(File(absOut).parent)} 2>/dev/null; " +
            "if [ ! -w ${TermuxRunTool.shellQuote(SharedFolder.DISPLAY_PATH + "/Output")} ]; then " +
            "echo '${EbookTranslatePy.MARKER}{\"ok\":false,\"error\":\"Termux cannot write Documents/Farrow/Output. Run termux-setup-storage in Termux once.\"}'; exit 3; fi\n"
        val python = "python3 " + cli.joinToString(" ") { TermuxRunTool.shellQuote(it) }
        suspend fun run(allowInstall: Boolean): Pair<com.verdroid.app.data.termux.TermuxResult?, Int> {
            val cmd = EbookTranslatePy.installCommand() + "\n" + EbookTranslatePy.setupCommand(setupFmt, allowInstall) + "\n" + storageGuard +
                TermuxRunTool.capped(python, if (confirmed) timeout else ESTIMATE_TIMEOUT, grace = 30)
            // Setup (first run: apt + pip, each capped at 900 s) happens before the capped python step.
            val wait = (if (confirmed) timeout + (if (allowInstall) SETUP_ALLOWANCE else 300) else ESTIMATE_TIMEOUT + SETUP_ALLOWANCE) + 45
            return termux.runAndWait(cmd, "ebook-${System.nanoTime()}", wait * 1_000L, label = "Farrow: ebook_translate") to wait
        }
        // Nothing is installed without the user's OK: a probe run first; installs only with confirm_install + install_id.
        var (r, wait) = run(allowInstall = false)
        val missing = r?.stdout?.lineSequence()?.lastOrNull { it.startsWith(EbookTranslatePy.MARKER) }
            ?.let { runCatching { Json.parseToJsonElement(it.removePrefix(EbookTranslatePy.MARKER)).jsonObject }.getOrNull() }
            ?.let { InstallConsent.missingFrom(it) }
        if (missing != null) {
            when (InstallConsent.decide(args, name, missing)) {
                InstallConsent.Decision.Allow -> { val again = run(allowInstall = true); r = again.first; wait = again.second }
                InstallConsent.Decision.Deny -> return InstallConsent.denied(name, missing).toString()
                InstallConsent.Decision.Ask -> return InstallConsent.pending(name, name, missing, INSTALL_REASON + (if (confirmed) "" else
                    "; after installing, the call returns the translation estimate (ETA) for the user to confirm"),
                    InstallConsent.retryNote(args)).toString()
            }
        }
        if (r == null) return errorJson("Termux did not answer within $wait s. Check allow-external-apps / Set up Termux.")
        val line = r.stdout.lineSequence().lastOrNull { it.startsWith(EbookTranslatePy.MARKER) }
        if (line == null) {
            val hint = when {
                r.exitCode == 124 -> "timed out after $timeout s (re-run with resume=true to continue)"
                r.stderr.contains("No module named") || r.stdout.contains("Missing Python") ->
                    "Install the ebook-translate add-on in Settings > Tools"
                else -> "no result from translator"
            }
            return buildJsonObject {
                put("ok", false); put("error", "ebook_translate failed: $hint")
                put("exit_code", r.exitCode ?: -1)
                put("stderr", r.stderr.takeLast(3_000)); put("stdout", r.stdout.takeLast(2_000))
            }.toString()
        }
        val json = line.removePrefix(EbookTranslatePy.MARKER)
        if (confirmed) return json
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return json
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return json
        return withConfirmation(o).toString()
    }

    /** Adds the human ETA, a suggested timeout and the "ask the user first" instruction to the --estimate result. */
    internal fun withConfirmation(o: JsonObject): JsonObject {
        val mid = o["eta_seconds"]?.jsonPrimitive?.longOrNull ?: 0
        val lo = o["eta_min_seconds"]?.jsonPrimitive?.longOrNull ?: mid
        val hi = o["eta_max_seconds"]?.jsonPrimitive?.longOrNull ?: mid
        val suggested = (hi * 5 / 4 + 120).coerceIn(300, MAX_TIMEOUT.toLong())
        val runs = ((hi * 5 / 4 + 120 + MAX_TIMEOUT - 1) / MAX_TIMEOUT).coerceAtLeast(1)
        return buildJsonObject {
            o.forEach { (k, v) -> put(k, v) }
            put("needs_confirmation", true)
            put("eta", "${formatDuration(mid)} (range ${formatDuration(lo)}–${formatDuration(hi)})")
            put("suggested_timeout_seconds", suggested)
            put("message", "Nothing translated yet. Tell the user: ${o["chapters"]} chapters, ${o["remaining"]} chunks to translate" +
                (if ((o["done"]?.jsonPrimitive?.intOrNull ?: 0) > 0) " (resuming after ${o["done"]} done)" else "") +
                ", estimated ${formatDuration(mid)} (${formatDuration(lo)}–${formatDuration(hi)}) because of the rate-limit pauses; " +
                (if (runs > 1) "that is longer than one run (2 h max), so it will need about $runs runs with resume=true; " else "") +
                "the chat will wait while it runs. Ask them to confirm, then call ebook_translate again with the same " +
                "arguments plus confirmed=true and timeout_seconds=$suggested.")
        }
    }

    internal companion object {
        val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2,4})?$")
        const val MAX_TIMEOUT = 7200
        const val ESTIMATE_TIMEOUT = 300
        const val SETUP_ALLOWANCE = 1900
        const val INSTALL_REASON = "the Python packages ebook_translate needs in Termux (one-time; the ebook-translate " +
            "add-on in Settings > Tools does the same)"
        val FORMATS = mapOf("mobi" to "mobi", "azw" to "mobi", "azw3" to "mobi", "epub" to "epub", "pdf" to "pdf",
            "docx" to "docx", "txt" to "txt", "md" to "txt", "markdown" to "txt")

        fun formatDuration(s: Long): String = when {
            s < 60 -> "${s}s"
            s < 3600 -> "${(s + 30) / 60} min"
            else -> "${s / 3600} h ${(s % 3600 + 30) / 60} min"
        }
    }
}
