package com.guardiangrid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single consistent GuardianGrid Material 3 ColorScheme matching the provided palette:
 * Midnight Navy Background (#182430), Slate Card (#202D3A), Muted (#21252B),
 * Border (#3C5165), Primary Gold (#FBE39E), Secondary Blush (#EE9FB0),
 * Accent Mauve (#C97390), and Vibrant Rose (#EC539B).
 */
private val GuardianGridColorScheme = darkColorScheme(
    primary = BrandPrimaryGold,
    onPrimary = DarkBackground,
    primaryContainer = BrandAccentMauve,
    onPrimaryContainer = TextPrimary,
    secondary = BrandSecondaryBlush,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceHighlight,
    onSecondaryContainer = TextPrimary,
    tertiary = BrandAccentMauve,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = DarkBackground,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceHighlight,
    outline = DarkBorder,
    outlineVariant = DarkBorderSubtle,
    error = BrandMagenta,
    onError = Color.White,
    errorContainer = EmergencyDarkRed,
    onErrorContainer = TextPrimary
)

val GuardianGridTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        color = TextSecondary
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
        color = TextTertiary
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.8.sp
    )
)

val GuardianGridShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun GuardianGridTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GuardianGridColorScheme,
        typography = GuardianGridTypography,
        shapes = GuardianGridShapes,
        content = content
    )
}
