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
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farrow.app.domain.model.Task
import com.farrow.app.domain.model.TaskType

/**
 * Varied chat avatars (after v1.0.16): a stable shade of the theme green hashed from the chat id, and a glyph picked from the
 * chat's topic (keywords in the title/first prompt), else its type, else the title's first letter or emoji.
 * Pure logic here, unit-tested; [TaskAvatar] (Task overload) draws it.
 */
object ChatAvatarStyle {
    /** One circle shade: background + letter color (ARGB). */
    data class Shade(val bg: Int, val fg: Int)

    /**
     * v1.0.24: circles use ONLY shades of the app theme's green — the scheme's primary hue, and tertiary's hue when it is
     * within 30° of primary (else primary nudged +10°) — across a wide tone ladder, so light and dark circles differ a lot.
     * Light theme runs tone 88 → 24, dark theme tone 24 → 82. Letters: tone 10 on circles ≥ tone 52, white below.
     */
    val LIGHT_TONES = doubleArrayOf(88.0, 76.0, 66.0, 56.0, 46.0, 38.0, 30.0, 24.0)
    val DARK_TONES = doubleArrayOf(24.0, 32.0, 40.0, 48.0, 56.0, 64.0, 72.0, 82.0)
    val SHADE_COUNT = LIGHT_TONES.size

    fun shades(primary: Int, tertiary: Int, dark: Boolean): List<Shade> {
        val p = Hct.fromInt(primary)
        val t = Hct.fromInt(tertiary)
        val diff = Math.abs(((t.hue - p.hue) % 360.0 + 540.0) % 360.0 - 180.0)
        val altHue = if (diff <= 30.0) t.hue else (p.hue + 10.0) % 360.0
        val chroma = maxOf(p.chroma, 36.0)
        return (if (dark) DARK_TONES else LIGHT_TONES).mapIndexed { i, tone ->
            val hue = if (i % 2 == 0) p.hue else altHue
            val bg = Hct.from(hue, chroma, tone).toInt()
            val fg = Hct.from(hue, minOf(chroma, 24.0), if (tone >= 52.0) 10.0 else 100.0).toInt()
            Shade(bg, fg)
        }
    }

    /** Stable palette slot for a chat id (SplitMix64 finaliser, so neighbouring ids get different colors). */
    fun colorIndex(id: Long, n: Int = SHADE_COUNT): Int {
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
    val cs = MaterialTheme.colorScheme
    val shades = remember(cs.primary, cs.tertiary, dark) { ChatAvatarStyle.shades(cs.primary.toArgb(), cs.tertiary.toArgb(), dark) }
    val shade = shades[remember(task.id) { ChatAvatarStyle.colorIndex(task.id) }]
    val glyph = remember(task.title, task.prompt, task.type) { ChatAvatarStyle.glyph(task.title, task.prompt, task.type) }
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (ring) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier)
                .clip(CircleShape).background(Color(shade.bg)),
            contentAlignment = Alignment.Center,
        ) {
            if (ChatAvatarStyle.isLetter(glyph)) {
                Text(glyph, fontSize = (size.value * 0.40f).sp, fontWeight = FontWeight.SemiBold,
                    color = Color(shade.fg))
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
