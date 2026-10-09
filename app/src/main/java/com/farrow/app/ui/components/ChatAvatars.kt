package com.farrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farrow.app.domain.model.Task
import com.farrow.app.domain.model.TaskType

/**
 * Varied chat avatars (after v1.0.16): a stable soft tonal color hashed from the chat id, and a glyph picked from the
 * chat's topic (keywords in the title/first prompt), else its type, else the title's first letter or emoji.
 * Pure logic here, unit-tested; [TaskAvatar] (Task overload) draws it.
 */
object ChatAvatarStyle {
    /**
     * One pair per theme: circle background + letter color. v1.0.23: circles are mid-tone (light theme, ≥2:1 against the
     * light surface) and brighter (dark theme, ≥2.6:1 against the dark surface) so they stand out; letters stay ≥4.5:1.
     */
    data class Tone(val light: Long, val onLight: Long, val dark: Long, val onDark: Long)

    val PALETTE = listOf(
        Tone(0xFF99B6E6, 0xFF0B2E6B, 0xFF2958A3, 0xFFD3E3FD), // blue
        Tone(0xFF32C8AA, 0xFF00382F, 0xFF196657, 0xFFB8EFE4), // teal
        Tone(0xFF59CC33, 0xFF173A0E, 0xFF2D6619, 0xFFCDEBC0), // green
        Tone(0xFFB0BC2F, 0xFF2E3300, 0xFF585E17, 0xFFE3E8B2), // olive
        Tone(0xFFD5B058, 0xFF3F2A00, 0xFF6E551C, 0xFFFCE0A8), // amber
        Tone(0xFFE0A985, 0xFF4A1F00, 0xFF874A22, 0xFFFFD7BF), // orange
        Tone(0xFFE8A1AD, 0xFF4D0E1D, 0xFFA3293D, 0xFFFFD6DC), // rose
        Tone(0xFFE79DD0, 0xFF431339, 0xFF9B2778, 0xFFF7D4EE), // pink
        Tone(0xFFBDA9EA, 0xFF2A1260, 0xFF6437CD, 0xFFE3D9FF), // purple
        Tone(0xFFAAB5CF, 0xFF1C2433, 0xFF48587F, 0xFFD9DFEE), // slate
        Tone(0xFF74BFDC, 0xFF003549, 0xFF1F607A, 0xFFC6E7F5), // cyan
    )

    /** Stable palette slot for a chat id (SplitMix64 finaliser, so neighbouring ids get different colors). */
    fun colorIndex(id: Long, n: Int = PALETTE.size): Int {
        var z = id + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return Math.floorMod(z, n.toLong()).toInt()
    }

    /** Topic → emoji, first match wins (English + French keywords; matched on word starts). */
    private val TOPICS: List<Pair<Regex, String>> = listOf(
        "x\\.com|x_post|tweet|post on x|on x\\b|sur x\\b|twitter|publier|post (a|an|this|it)\\b" to "✍️",
        "facebook|instagram|tiktok|reddit|linkedin|social" to "👥",
        "trip|travel|flight|hotel|vacation|holiday|itinerar|voyage|vol\\b|vacances|séjour|airbnb|train to" to "✈️",
        "weather|forecast|météo|meteo" to "🌦️",
        "shop|buy|price|amazon|deal|cheapest|acheter|prix|achat" to "🛒",
        "recipe|cook|food|restaurant|dinner|recette|cuisine|dîner" to "🍳",
        "code|git|kotlin|python|bug|script|android|repo|compile|build" to "💻",
        "chart|graph|stats?\\b|statisti|compare|data|tableau|comparer" to "📊",
        "mail|email|inbox|courriel" to "✉️",
        "music|song|playlist|spotify|musique|chanson" to "🎵",
        "news|headline|actualit|journal" to "📰",
        "shell|termux|file|folder|system|workspace|fichier|dossier" to "⚙️",
        "search|find|look up|google|browse|website|http|www\\.|cherche|trouve|recherche" to "🔎",
    ).map { (re, e) -> Regex("(?<![\\p{L}\\p{N}])($re)", RegexOption.IGNORE_CASE) to e }

    private val TYPE_GLYPH = mapOf(TaskType.WEB to "🔎", TaskType.SOCIAL to "👥", TaskType.SYSTEM to "⚙️")

    /** Glyph: topic emoji, else the type's emoji (web/social/system), else the title's first emoji/letter, else 💬. */
    fun glyph(title: String, prompt: String, type: TaskType): String {
        val text = "$title\n${prompt.take(400)}"
        TOPICS.firstOrNull { it.first.containsMatchIn(text) }?.let { return it.second }
        TYPE_GLYPH[type]?.let { return it }
        return initial(title) ?: "💬"
    }

    /** First emoji (whole grapheme) or first letter/digit (uppercased) of the title; null if none. */
    fun initial(title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val cp = t.codePointAt(0)
        if (isEmoji(cp)) {
            val it = java.text.BreakIterator.getCharacterInstance().apply { setText(t) }
            return t.substring(0, it.next().coerceAtLeast(Character.charCount(cp)))
        }
        val i = t.indexOfFirst { it.isLetterOrDigit() }
        return if (i < 0) null else t[i].uppercase()
    }

    private fun isEmoji(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF

    /** True when the glyph is a letter/digit (drawn in the on-container color, bold). */
    fun isLetter(glyph: String) = glyph.length == 1 && glyph[0].isLetterOrDigit()
}

/** Varied avatar for a chat: hashed tonal color + topic glyph (see [ChatAvatarStyle]), status dot as before. */
@Composable
fun TaskAvatar(task: Task, size: Dp = 52.dp, ring: Boolean = false) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tone = ChatAvatarStyle.PALETTE[remember(task.id) { ChatAvatarStyle.colorIndex(task.id) }]
    val glyph = remember(task.title, task.prompt, task.type) { ChatAvatarStyle.glyph(task.title, task.prompt, task.type) }
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (ring) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier)
                .clip(CircleShape).background(Color(if (dark) tone.dark else tone.light)),
            contentAlignment = Alignment.Center,
        ) {
            if (ChatAvatarStyle.isLetter(glyph)) {
                Text(glyph, fontSize = (size.value * 0.40f).sp, fontWeight = FontWeight.SemiBold,
                    color = Color(if (dark) tone.onDark else tone.onLight))
            } else Text(glyph, fontSize = (size.value * 0.42f).sp)
        }
        task.status.dotColor()?.let { c ->
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.28f)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)
                    .clip(CircleShape).background(c)
            )
        }
    }
}
