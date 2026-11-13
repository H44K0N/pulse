package dev.tischbein.pulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFEF5350),
        onPrimary = Color(0xFF1B1B1F),
        secondary = Color(0xFF80CBC4),
        background = Color(0xFF121212),
        surface = Color(0xFF121212),
    )

/** Dark theme by default (brief). A light scheme can be added here later. */
@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
