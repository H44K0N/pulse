package dev.tischbein.pulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.R

/*
 * Pulse design tokens: one dark "sports-tech" theme.
 *
 * - Near-black background, a few grey surface steps, three text greys.
 * - One strong accent per zone state: cool blue (below), vivid green (in), warm orange (above).
 *   All three are bright on black (contrast 7:1 to 12:1) and differ in lightness too, and the
 *   UI always adds an arrow/word, so color is never the only cue.
 * - Inter (SIL Open Font License 1.1, bundled as one variable font: res/font/inter.ttf,
 *   license in app/licenses/Inter-OFL.txt). Numbers use tabular figures ("tnum") so a
 *   ticking timer or HR does not jitter sideways; big numbers use Inter's display optical size.
 */

/** Raw palette. Screens use [Pulse] (semantic names), not these. */
object PulsePalette {
    val Background = Color(0xFF0A0B0E)
    val Surface = Color(0xFF13151A)
    val SurfaceRaised = Color(0xFF1B1E25)
    val Stroke = Color(0xFF262A32)
    val TextHigh = Color(0xFFF3F5F8)
    val TextMid = Color(0xFFA1A8B3)
    val TextLow = Color(0xFF656D79)
    val Blue = Color(0xFF4CA6FF)
    val Green = Color(0xFF2BE38F)
    val Orange = Color(0xFFFF6A3D)
    val Red = Color(0xFFFF4D5E)
    val Amber = Color(0xFFFFC14D)
}

@Immutable
data class ZoneColor(val accent: Color, val onAccent: Color)

/** Semantic colors used by the screens. */
object Pulse {
    val bg = PulsePalette.Background
    val surface = PulsePalette.Surface
    val surfaceRaised = PulsePalette.SurfaceRaised
    val stroke = PulsePalette.Stroke
    val text = PulsePalette.TextHigh
    val textMid = PulsePalette.TextMid
    val textLow = PulsePalette.TextLow
    val accent = PulsePalette.Green
    val danger = PulsePalette.Red
    val warning = PulsePalette.Amber

    val below = ZoneColor(PulsePalette.Blue, PulsePalette.Background)
    val inZone = ZoneColor(PulsePalette.Green, PulsePalette.Background)
    val above = ZoneColor(PulsePalette.Orange, PulsePalette.Background)
    val neutral = ZoneColor(PulsePalette.TextMid, PulsePalette.Background)

    /** Chart line, and the fill under it fades from this to transparent. */
    val chartLine = PulsePalette.TextHigh
    val chartGrid = Color(0x14FFFFFF)
}

private fun inter(
    weight: Int,
    opticalSize: Float,
) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

/** Inter at text optical size (labels, body). */
val InterText = FontFamily(inter(400, TEXT_OPSZ), inter(500, TEXT_OPSZ), inter(600, TEXT_OPSZ), inter(700, TEXT_OPSZ))

/** Inter at display optical size: tighter, for big numbers and headings. */
val InterDisplay = FontFamily(inter(500, DISPLAY_OPSZ), inter(600, DISPLAY_OPSZ), inter(700, DISPLAY_OPSZ), inter(800, DISPLAY_OPSZ))

private const val TEXT_OPSZ = 14f
private const val DISPLAY_OPSZ = 32f

/** Tabular figures: every digit the same width. */
const val TABULAR = "tnum"

/** Text styles beyond Material's: the big numbers. */
object PulseType {
    /**
     * The live heart rate. Sized by the caller. Proportional figures on purpose: Inter's
     * tabular "1" has a slab foot that looks clumsy at this size, and a centered number
     * may change width a little.
     */
    val hero = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.04).em)
    val bigNumber = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, fontSize = 40.sp, letterSpacing = (-0.03).em)
    val number = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, fontSize = 24.sp, letterSpacing = (-0.02).em)
    val smallNumber = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR, fontSize = 15.sp)

    /** Small all-caps label above a value ("AVG HR"). */
    val overline = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em)
}

private val PulseTypography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
            displayMedium = displayMedium.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
            displaySmall = displaySmall.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
            headlineLarge = headlineLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            headlineMedium = headlineMedium.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            headlineSmall = headlineSmall.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontFamily = InterText, fontWeight = FontWeight.SemiBold),
            titleSmall = titleSmall.copy(fontFamily = InterText, fontWeight = FontWeight.SemiBold),
            bodyLarge = bodyLarge.copy(fontFamily = InterText),
            bodyMedium = bodyMedium.copy(fontFamily = InterText),
            bodySmall = bodySmall.copy(fontFamily = InterText),
            labelLarge = labelLarge.copy(fontFamily = InterText, fontWeight = FontWeight.SemiBold),
            labelMedium = labelMedium.copy(fontFamily = InterText, fontWeight = FontWeight.Medium),
            labelSmall = labelSmall.copy(fontFamily = InterText, fontWeight = FontWeight.Medium),
        )
    }

private val DarkColors =
    darkColorScheme(
        primary = Pulse.accent,
        onPrimary = PulsePalette.Background,
        secondary = PulsePalette.Blue,
        onSecondary = PulsePalette.Background,
        background = Pulse.bg,
        onBackground = Pulse.text,
        surface = Pulse.bg,
        onSurface = Pulse.text,
        surfaceVariant = Pulse.surfaceRaised,
        onSurfaceVariant = Pulse.textMid,
        surfaceContainerLowest = Pulse.bg,
        surfaceContainerLow = Pulse.surface,
        surfaceContainer = Pulse.surface,
        surfaceContainerHigh = Pulse.surfaceRaised,
        surfaceContainerHighest = Pulse.surfaceRaised,
        outline = Pulse.stroke,
        outlineVariant = Pulse.stroke,
        error = Pulse.danger,
        errorContainer = Color(0xFF2A1418),
        onErrorContainer = Color(0xFFFFB3BA),
    )

/** Dark theme only (brief). */
@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = PulseTypography, content = content)
}
