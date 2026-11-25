package com.guardiangrid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GuardianGridColorScheme = darkColorScheme(
    primary = BrandMagenta,
    onPrimary = TextPrimary,
    primaryContainer = BrandMagentaDark,
    onPrimaryContainer = TextPrimary,
    secondary = InfoBlue,
    onSecondary = TextPrimary,
    secondaryContainer = DarkSurfaceHighlight,
    onSecondaryContainer = TextPrimary,
    tertiary = SafeGreen,
    onTertiary = TextPrimary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceHighlight,
    outline = DarkBorder,
    outlineVariant = DarkBorderSubtle,
    error = EmergencyRed,
    onError = TextPrimary,
    errorContainer = EmergencyDarkRed,
    onErrorContainer = TextPrimary
)

private val GuardianGridLightColorScheme = lightColorScheme(
    primary = BrandMagenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = InfoBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = SafeGreen,
    onTertiary = Color.White,
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    error = EmergencyRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
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
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GuardianGridColorScheme else GuardianGridLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = GuardianGridTypography,
        shapes = GuardianGridShapes,
        content = content
    )
}
