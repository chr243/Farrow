package com.farrow.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.blend.Blend
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Seed of the default palette (Messenger Blue). */
val MessengerBlue = Color(0xFF0084FF)

/**
 * Status/semantic accents. Not part of Material's scheme, so they are harmonised towards the current primary at
 * runtime via [StatusColors] (LocalStatusColors); these constants are only the fallbacks for previews/tests.
 */
val StatusGreen = Color(0xFF31A24C)
val StatusYellow = Color(0xFFF7B928)
val StatusRed = Color(0xFFE41E3F)

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Each palette is generated from one seed with Material 3's tonal-palette algorithm (see [Palettes.fromSeed]). */
enum class Palette(val label: String, val description: String, val seed: Long) {
    MESSENGER("Messenger Blue", "Bright blue", 0xFF0084FF),
    DYNAMIC("Dynamic", "Material You colors from your wallpaper (Android 12+)", 0xFF0084FF),
    MIDNIGHT("Midnight", "Pure black surfaces in dark mode (AMOLED)", 0xFF0084FF),
    FOREST("Forest", "The default: Farrow's icon green", 0xFF3D5A3A),
    SUNSET("Sunset", "Warm oranges", 0xFFF4511E),
    PURPLE("Purple", "Violet tones", 0xFF7E57C2),
    ROSE("Rose", "Soft pinks", 0xFFD81B60),
    OCEAN("Ocean", "Teal and sea blue", 0xFF00897B),
}

/** Chat bubble colors that follow the theme. */
@Immutable
data class BubbleColors(val user: Color, val onUser: Color, val agent: Color, val onAgent: Color)

val LocalBubbleColors = staticCompositionLocalOf { Palettes.bubbles(Palettes.scheme(Palette.FOREST, false)) }

/** Running / waiting / failed accents, blended towards the theme's primary so they sit with the palette. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val error: Color, val onAccent: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(StatusGreen, StatusYellow, StatusRed, Color.White) }

/** Theme choice, saved in SharedPreferences; one process-wide flow so every Compose root (app, bubbles, chat head) updates at once. */
class ThemeStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(ThemeMode.SYSTEM))
    private val _palette = MutableStateFlow(runCatching { Palette.valueOf(prefs.getString("palette", null)!!) }.getOrDefault(Palette.FOREST))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()
    val palette: StateFlow<Palette> = _palette.asStateFlow()

    fun setMode(m: ThemeMode) { prefs.edit().putString("mode", m.name).apply(); _mode.value = m }
    fun setPalette(p: Palette) { prefs.edit().putString("palette", p.name).apply(); _palette.value = p }

    companion object {
        @Volatile private var instance: ThemeStore? = null
        fun get(context: Context): ThemeStore = instance ?: synchronized(this) {
            instance ?: ThemeStore(context.applicationContext).also { instance = it }
        }
    }
}

object Palettes {
    private val roles = MaterialDynamicColors()

    /**
     * Full Material 3 scheme from one seed: [SchemeContent] keeps the seed's own hue/chroma for primary (brand colors stay
     * recognisable) and derives secondary, tertiary, error, neutral surfaces, the surface-container ladder and outlines
     * from the same HCT tonal palettes, so light and dark are harmonised.
     */
    fun fromSeed(seed: Long, dark: Boolean, contrast: Double = 0.0): ColorScheme {
        val s: DynamicScheme = SchemeContent(Hct.fromInt(seed.toInt()), dark, contrast)
        fun c(d: com.materialkolor.dynamiccolor.DynamicColor) = Color(d.getArgb(s))
        val args = SchemeRoles(
            primary = c(roles.primary()), onPrimary = c(roles.onPrimary()),
            primaryContainer = c(roles.primaryContainer()), onPrimaryContainer = c(roles.onPrimaryContainer()),
            inversePrimary = c(roles.inversePrimary()),
            secondary = c(roles.secondary()), onSecondary = c(roles.onSecondary()),
            secondaryContainer = c(roles.secondaryContainer()), onSecondaryContainer = c(roles.onSecondaryContainer()),
            tertiary = c(roles.tertiary()), onTertiary = c(roles.onTertiary()),
            tertiaryContainer = c(roles.tertiaryContainer()), onTertiaryContainer = c(roles.onTertiaryContainer()),
            background = c(roles.background()), onBackground = c(roles.onBackground()),
            surface = c(roles.surface()), onSurface = c(roles.onSurface()),
            surfaceVariant = c(roles.surfaceVariant()), onSurfaceVariant = c(roles.onSurfaceVariant()),
            surfaceTint = c(roles.surfaceTint()), inverseSurface = c(roles.inverseSurface()), inverseOnSurface = c(roles.inverseOnSurface()),
            error = c(roles.error()), onError = c(roles.onError()), errorContainer = c(roles.errorContainer()), onErrorContainer = c(roles.onErrorContainer()),
            outline = c(roles.outline()), outlineVariant = c(roles.outlineVariant()), scrim = c(roles.scrim()),
            surfaceBright = c(roles.surfaceBright()), surfaceDim = c(roles.surfaceDim()),
            surfaceContainerLowest = c(roles.surfaceContainerLowest()), surfaceContainerLow = c(roles.surfaceContainerLow()),
            surfaceContainer = c(roles.surfaceContainer()), surfaceContainerHigh = c(roles.surfaceContainerHigh()),
            surfaceContainerHighest = c(roles.surfaceContainerHighest()),
        )
        return args.toScheme(dark)
    }

    fun scheme(p: Palette, dark: Boolean, context: Context? = null): ColorScheme = when (p) {
        Palette.DYNAMIC -> if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else fromSeed(p.seed, dark)
        // Midnight: the seed's dark scheme on pure black; containers step up in neutral greys so cards stay visible.
        Palette.MIDNIGHT -> if (dark) fromSeed(p.seed, true).copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1C1C1C),
            surfaceContainerHighest = Color(0xFF262626), surfaceBright = Color(0xFF2C2C2C), surfaceVariant = Color(0xFF1C1C1C),
        ) else fromSeed(p.seed, false)
        else -> fromSeed(p.seed, dark)
    }

    /** Sent = primary / onPrimary, received = surfaceContainerHigh / onSurface — for every palette. */
    fun bubbles(s: ColorScheme): BubbleColors = BubbleColors(s.primary, s.onPrimary, s.surfaceContainerHigh, s.onSurface)

    @Deprecated("Bubbles no longer depend on the palette", ReplaceWith("bubbles(s)"))
    fun bubbles(@Suppress("UNUSED_PARAMETER") p: Palette, @Suppress("UNUSED_PARAMETER") dark: Boolean, s: ColorScheme): BubbleColors = bubbles(s)

    /** Status accents harmonised (hue shifted ≤ 15°) towards the scheme's primary, tone chosen for the light/dark surface. */
    fun status(s: ColorScheme, dark: Boolean): StatusColors {
        val primary = s.primary.toArgb()
        fun accent(base: Color): Color {
            val h = Hct.fromInt(Blend.harmonize(base.toArgb(), primary))
            return Color(Hct.from(h.hue, maxOf(h.chroma, 48.0), if (dark) 70.0 else 45.0).toInt())
        }
        return StatusColors(accent(StatusGreen), accent(StatusYellow).let { y ->
            // Yellow reads as "waiting" only when light enough.
            val h = Hct.fromInt(y.toArgb()); Color(Hct.from(h.hue, h.chroma, if (dark) 80.0 else 70.0).toInt())
        }, accent(StatusRed), if (dark) Color.Black else Color.White)
    }
}

/** All roles; [toScheme] passes them to the Material 3 builders (so new roles default sensibly). */
private data class SchemeRoles(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color, val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val background: Color, val onBackground: Color, val surface: Color, val onSurface: Color,
    val surfaceVariant: Color, val onSurfaceVariant: Color, val surfaceTint: Color, val inverseSurface: Color, val inverseOnSurface: Color,
    val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color,
    val outline: Color, val outlineVariant: Color, val scrim: Color,
    val surfaceBright: Color, val surfaceDim: Color, val surfaceContainerLowest: Color, val surfaceContainerLow: Color,
    val surfaceContainer: Color, val surfaceContainerHigh: Color, val surfaceContainerHighest: Color,
) {
    fun toScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface,
        onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, surfaceTint = surfaceTint,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer, outline = outline, outlineVariant = outlineVariant,
        scrim = scrim, surfaceBright = surfaceBright, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest, surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest, surfaceDim = surfaceDim,
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface,
        onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, surfaceTint = surfaceTint,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer, outline = outline, outlineVariant = outlineVariant,
        scrim = scrim, surfaceBright = surfaceBright, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest, surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest, surfaceDim = surfaceDim,
    )
}

/** Applies the saved mode + palette; changes in the Theme screen apply instantly everywhere. */
@Composable
fun FarrowTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { ThemeStore.get(context) }
    val mode by store.mode.collectAsState()
    val palette by store.palette.collectAsState()
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val scheme = remember(palette, dark) { Palettes.scheme(palette, dark, context) }
    val status = remember(scheme, dark) { Palettes.status(scheme, dark) }
    SystemBarsEffect(scheme, dark)
    CompositionLocalProvider(LocalBubbleColors provides Palettes.bubbles(scheme), LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Status/nav bars follow the theme: edge-to-edge draws the app's own surface behind them, so only the icon contrast
 * (light/dark icons) has to match the theme's dark flag — not the system setting.
 */
@Composable
private fun SystemBarsEffect(scheme: ColorScheme, dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
        // Bars are transparent (enableEdgeToEdge); the theme's background/surfaceContainer (bottom bar) shows through.
        window.decorView.setBackgroundColor(scheme.background.toArgb())
    }
}
