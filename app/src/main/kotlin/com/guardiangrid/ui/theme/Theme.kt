package com.guardiangrid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GuardianGridColorScheme = darkColorScheme(
    primary = EmergencyRed,
    onPrimary = TextPrimary,
    primaryContainer = EmergencyDarkRed,
    onPrimaryContainer = TextPrimary,
    secondary = InfoBlue,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun GuardianGridTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GuardianGridColorScheme,
        content = content
    )
}
