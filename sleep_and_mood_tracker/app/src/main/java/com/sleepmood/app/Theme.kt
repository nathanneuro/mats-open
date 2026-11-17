package com.sleepmood.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always dark: it gets used at bedtime and on waking. Matches res/values/colors.xml. */
private val Colors = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0F1117),
    primaryContainer = Color(0xFF2B3A6B),
    onPrimaryContainer = Color(0xFFF1F3F8),
    secondaryContainer = Color(0xFF2A2F3D),
    onSecondaryContainer = Color(0xFFF1F3F8),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFF1F3F8),
    surface = Color(0xFF1A1D26),
    onSurface = Color(0xFFF1F3F8),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA4AAB8),
    surfaceContainer = Color(0xFF1A1D26),
    surfaceContainerHigh = Color(0xFF232733),
    surfaceContainerHighest = Color(0xFF2A2F3D),
    outline = Color(0xFF555B6B),
)

@Composable
fun SleepMoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
