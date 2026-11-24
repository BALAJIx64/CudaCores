package com.guardiangrid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GuardianGridColorScheme = darkColorScheme(
    primary = BrandMagenta,
    onPrimary = TextPrimary,
    primaryContainer = BrandMagentaDark,
    onPrimaryContainer = TextPrimary,
    secondary = InfoBlue,
    onSecondary = TextPrimary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = EmergencyRed,
    onError = TextPrimary,
    errorContainer = EmergencyDarkRed,
    onErrorContainer = TextPrimary
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
