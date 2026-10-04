package com.farrow.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Syntax colors; [CodeColors.of] takes them from the theme's color roles. */
data class CodeColors(val key: Color, val string: Color, val number: Color, val literal: Color, val comment: Color) {
    companion object {
        val DEFAULT = CodeColors(Color(0xFF7D5260), Color(0xFF386A20), Color(0xFF0061A4), Color(0xFF8B5000), Color(0xFF73777F))
        fun of(s: androidx.compose.material3.ColorScheme) = CodeColors(s.tertiary, s.primary, s.secondary, s.error, s.onSurfaceVariant)
    }
}

/** Tiny regex-based highlighter: JSON (keys/strings/numbers/literals) and shell (keywords/flags/strings/comments). */
object CodeHighlighter {

    private val jsonToken = Regex("(\"(?:\\\\.|[^\"\\\\])*\")(\\s*:)?|(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)|\\b(true|false|null)\\b")
    private val shellKeywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "export", "cd", "ls", "echo", "git", "grep", "cat", "sudo", "rm", "cp", "mv", "mkdir", "curl", "chmod")
    private val shellToken = Regex("(#[^\\n]*)|(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')|(\\s--?[A-Za-z][\\w-]*)|\\b([A-Za-z_]+)\\b")

    fun looksLikeJson(text: String) = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    fun highlight(text: String, language: String? = null, colors: CodeColors = CodeColors.DEFAULT): AnnotatedString {
        val lang = language?.lowercase() ?: if (looksLikeJson(text)) "json" else "text"
        return when (lang) {
            "json" -> json(text, colors)
            "sh", "bash", "shell", "zsh" -> shell(text, colors)
            else -> AnnotatedString(text)
        }
    }

    fun json(text: String, c: CodeColors = CodeColors.DEFAULT) = build(text, jsonToken) { m ->
        when {
            m.groups[1] != null && m.groups[2] != null -> c.key
            m.groups[1] != null -> c.string
            m.groups[3] != null -> c.number
            m.groups[4] != null -> c.literal
            else -> null
        }
    }

    fun shell(text: String, c: CodeColors = CodeColors.DEFAULT) = build(text, shellToken) { m ->
        when {
            m.groups[1] != null -> c.comment
            m.groups[2] != null -> c.string
            m.groups[3] != null -> c.literal
            m.groups[4] != null && m.value in shellKeywords -> c.key
            else -> null
        }
    }

    private fun build(text: String, regex: Regex, colorFor: (MatchResult) -> Color?) = buildAnnotatedString {
        var last = 0
        for (m in regex.findAll(text)) {
            append(text.substring(last, m.range.first))
            val c = colorFor(m)
            if (c != null) withStyle(SpanStyle(color = c)) { append(m.value) } else append(m.value)
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
}
