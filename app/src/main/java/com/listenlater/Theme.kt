package com.listenlater

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF1B1B1E)
val Card = Color(0xFF26262D)
val Card2 = Color(0xFF2E2E36)
val NavBg = Color(0xFF232328)
val TextC = Color(0xFFF5F5F7)
val Muted = Color(0xFF9696A0)
/** Default accent: muted sage. The accent is the user's pick from [Swatches] (see [UiPrefs]). */
const val SAGE_ARGB: Long = 0xFF6F9C7E
val Sage: Color get() = UiPrefs.accent
/** Accent lightened toward white: the top of accent gradients and the label color on tinted pills. */
val SageL: Color get() = lighten(UiPrefs.accent, 0.30f)
/** Label color on accent fills: reads at least 4.5:1 on every swatch. */
val OnSage = Color(0xFF0C0C0E)
val Red = Color(0xFFE5484D)
val TrackGray = Color(0xFF4A4A52)

private fun lighten(c: Color, f: Float) = Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f)

/** Accent choices, same list as BumpLog's picker minus BingeMote's bright orange. */
val Swatches: List<Color> = listOf(
    SAGE_ARGB,
    0xFFCC5500, // burnt orange
    0xFF7BAFDB, // dusty blue
    0xFF83A2A4, // gray-green
    0xFFB8735C, // terracotta
    0xFFB3838F, // mauve
    0xFF8C8FC0, // indigo
    0xFFA89A8C, // taupe
).map { Color(it) }

/** Spoken names for [Swatches], same order, so a screen reader can tell the colours apart. */
val SwatchNames: List<String> = listOf("Sage", "Burnt orange", "Dusty blue", "Gray-green", "Terracotta", "Mauve", "Indigo", "Taupe")

/** Saved looks. Plain preferences (not the encrypted store) so reading them is instant and can never block a tap. */
object UiPrefs {
    private var prefs: android.content.SharedPreferences? = null
    var accent: Color by mutableStateOf(Color(SAGE_ARGB))
        private set
    var haptics: Boolean by mutableStateOf(true)
        private set

    internal fun resetForTest() { prefs = null; accent = Color(SAGE_ARGB); haptics = true }

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        prefs = p
        haptics = p.getBoolean("haptics", true)
        accent = resolve(if (p.contains("accent")) p.getInt("accent", 0) else null)
    }

    /** A saved colour the picker no longer offers falls back to sage. */
    fun resolve(argb: Int?): Color =
        Swatches.firstOrNull { argb != null && it.toArgb() == argb } ?: Color(SAGE_ARGB)

    fun chooseAccent(c: Color) { accent = c; prefs?.edit()?.putInt("accent", c.toArgb())?.apply() }
    fun chooseHaptics(on: Boolean) { haptics = on; prefs?.edit()?.putBoolean("haptics", on)?.apply() }
}

/** Approved redesign surfaces: subtle vertical ombré on cards and filled tiles. */
val CardGradient: Brush = Brush.verticalGradient(listOf(Card2, Card))
val SageGradient: Brush get() = Brush.verticalGradient(listOf(SageL, Sage))
val DarkTileGradient: Brush = Brush.verticalGradient(listOf(Color(0xFF383842), Card2))
val Yellow = Color(0xFFFFBE3C)
/** Pill label colors: the same hues, lightened so text on the 12% tint stays at or above 4.5:1 (worst case over Card2 2E2E36: red 4.81, sage 5.43, muted 5.15, amber 6.24). */
val RedText = Color(0xFFFF8A8E)
val SageText: Color get() = SageL
val MutedText = Color(0xFFB4B4BE)

@Composable
fun ListenLaterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sage,
            onPrimary = OnSage,
            background = Bg,
            onBackground = TextC,
            surface = Card,
            onSurface = TextC,
            surfaceVariant = Card2,
            onSurfaceVariant = Muted
        ),
        content = content
    )
}
