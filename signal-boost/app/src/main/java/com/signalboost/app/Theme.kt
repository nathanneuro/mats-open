package com.signalboost.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Forced dark Material3 theme for the whole app. Compose pulls its colours
 * from MaterialTheme.colorScheme, not from the XML theme — without an
 * explicit wrapper Compose silently uses lightColorScheme(), which is why
 * cards looked white over a dark XML window background. We never want
 * light mode here, so the dark palette is hard-wired and isSystemInDarkTheme()
 * is intentionally not consulted.
 *
 * Palette tracks the values in res/values/colors.xml so XML and Compose
 * surfaces look consistent.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE11D48),          // accent
    onPrimary = Color(0xFFF4F4F5),        // text_primary
    primaryContainer = Color(0xFF9F1239), // accent_dark
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFA1A1AA),        // text_secondary
    onSecondary = Color(0xFF1B1B1F),
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF52525B),
    outlineVariant = Color(0xFF3F3F46),
    error = Color(0xFFEF4444),
    onError = Color(0xFFF4F4F5),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

@Composable
fun SignalBoostTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
