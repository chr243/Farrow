package com.farrow.app.agent.tools

import com.farrow.app.data.ebook.EbookTranslatePy
import com.farrow.app.data.storage.SharedFolder
import com.farrow.app.data.termux.TermuxRunner
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
        "Chunked Google Translate with MyMemory fallback, language check, and resume if interrupted. " +
        "input_path is under Documents/Farrow (e.g. Input/book.mobi). The result is always written under Output/. " +
        "Needs the ebook-translate Termux add-on and termux-setup-storage. Timeout default 3600 s (max 7200)."
    override val parameters = schema(listOf("input_path", "dest_lang"),
        "input_path" to prop("string", "Path relative to Documents/Farrow, e.g. Input/novel.mobi"),
        "src_lang" to prop("string", "Source language code (default auto)"),
        "dest_lang" to prop("string", "Target language code, e.g. fr, de, es, zh-CN"),
        "output_name" to prop("string", "Optional file name under Output/ (default: <stem>.<dest>.txt or .docx)"),
        "format" to prop("string", "Force format: mobi, epub, pdf, docx, txt"),
        "resume" to prop("boolean", "Resume a previous run if a state file exists (default true)"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 3600, max 7200)"))

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
        val timeout = (args.int("timeout_seconds") ?: 3600).coerceIn(60, 7200)
        val resume = args.bool("resume") != false
        val fmt = args.str("format")?.trim()?.lowercase()
        val absIn = "${SharedFolder.DISPLAY_PATH}/${sandbox.relativePath(input)}"
        val absOut = "${SharedFolder.DISPLAY_PATH}/${sandbox.relativePath(output)}"
        val cli = buildList {
            add(EbookTranslatePy.FILE); add("--input"); add(absIn); add("--src"); add(src); add("--dest"); add(dest)
            add("--output"); add(absOut)
            if (fmt != null) { add("--format"); add(fmt) }
            if (!resume) add("--no-resume")
        }
        val storageGuard = "mkdir -p ${TermuxRunTool.shellQuote(File(absOut).parent)} 2>/dev/null; " +
            "if [ ! -w ${TermuxRunTool.shellQuote(SharedFolder.DISPLAY_PATH + "/Output")} ]; then " +
            "echo '${EbookTranslatePy.MARKER}{\"ok\":false,\"error\":\"Termux cannot write Documents/Farrow/Output. Run termux-setup-storage in Termux once.\"}'; exit 3; fi\n"
        val cmd = EbookTranslatePy.installCommand() + "\n" + storageGuard +
            "timeout -k 30 $timeout python3 " + cli.joinToString(" ") { TermuxRunTool.shellQuote(it) }
        val r = termux.runAndWait(cmd, "ebook-${System.nanoTime()}", (timeout + 45) * 1_000L, label = "Farrow: ebook_translate")
            ?: return errorJson("Termux did not answer within ${timeout + 45} s. Check allow-external-apps / Set up Termux.")
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
        return line.removePrefix(EbookTranslatePy.MARKER)
    }

    private companion object {
        val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2,4})?$")
    }
}
