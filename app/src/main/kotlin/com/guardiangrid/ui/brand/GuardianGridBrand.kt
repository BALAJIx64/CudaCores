package com.guardiangrid.ui.brand

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.R
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.BrandMagentaDark
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.GuardianGridTheme
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val GUARDIANGRID_TAGLINE = "Your Safety. Always Guarded."

/**
 * Checks Android's system reduced-motion preference (`ANIMATOR_DURATION_SCALE == 0f`).
 * Safely falls back to `false` in preview or non-Android test environments.
 */
@Composable
fun rememberIsReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Scalable GuardianGrid Brand Emblem.
 * Renders the official geometric winged guardian logo vector drawable.
 */
@Composable
fun GuardianGridEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Image(
        painter = painterResource(id = R.drawable.ic_guardiangrid_emblem),
        contentDescription = "GuardianGrid Logo",
        modifier = modifier.size(size)
    )
}

/**
 * Official Horizontal GuardianGrid Wordmark.
 * Features the main logo inside a subtle Material 3 tonal badge and stylized "GUARDIANGRID" typography.
 */
@Composable
fun GuardianGridWordmark(
    modifier: Modifier = Modifier,
    emblemSize: Dp = 36.dp,
    subtitle: String? = GUARDIANGRID_TAGLINE
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(emblemSize + 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                GuardianGridEmblem(size = emblemSize)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GUARDIAN",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 1.1.sp
                    ),
                    color = TextPrimary
                )
                Text(
                    text = "GRID",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 1.1.sp
                    ),
                    color = BrandMagenta
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        letterSpacing = 0.9.sp
                    ),
                    color = TextTertiary
                )
            }
        }
    }
}

/**
 * Material 3 pill badge reflecting real foreground service monitoring state.
 */
@Composable
fun MonitoringStatusBadge(
    state: MonitoringServiceState,
    modifier: Modifier = Modifier
) {
    val (statusText, dotColor, containerColor) = when (state) {
        MonitoringServiceState.ACTIVE -> Triple("ACTIVE", SafeGreen, SafeGreen.copy(alpha = 0.14f))
        MonitoringServiceState.STARTING -> Triple("STARTING", WarningAmber, WarningAmber.copy(alpha = 0.14f))
        MonitoringServiceState.STOPPING -> Triple("STOPPING", WarningAmber, WarningAmber.copy(alpha = 0.14f))
        MonitoringServiceState.STOPPED -> Triple("INACTIVE", TextDisabled, DarkSurfaceElevated)
        MonitoringServiceState.PERMISSION_REQUIRED -> Triple("PERMISSION", WarningAmber, WarningAmber.copy(alpha = 0.14f))
        MonitoringServiceState.ERROR -> Triple("ERROR", EmergencyRed, EmergencyRed.copy(alpha = 0.14f))
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(1.dp, dotColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            ),
            color = dotColor
        )
    }
}

/**
 * Polished GuardianGrid Loading / Splash Sequence.
 *
 * Uses the exact supplied logo split into undistorted person + wing layers so:
 * 1. The central guardian figure gently fades in and rises into place.
 * 2. The left and right wing blades smoothly unfold outward from the shoulders.
 * 3. A subtle protective halo and the GUARDIANGRID wordmark settle in.
 * 4. Supports both Light & Dark Material 3 themes, Android reduced-motion settings,
 *    and tap-to-skip so app access is never delayed.
 */
@Composable
fun GuardianGridSplashScreen(
    reducedMotionOverride: Boolean? = null,
    onSkip: (() -> Unit)? = null
) {
    val systemReducedMotion = rememberIsReducedMotion()
    val isReducedMotion = reducedMotionOverride ?: systemReducedMotion

    val personProgress = remember { Animatable(if (isReducedMotion) 1f else 0f) }
    val wingsProgress = remember { Animatable(if (isReducedMotion) 1f else 0f) }
    val haloProgress = remember { Animatable(if (isReducedMotion) 1f else 0f) }
    val textProgress = remember { Animatable(if (isReducedMotion) 1f else 0f) }

    LaunchedEffect(isReducedMotion) {
        if (isReducedMotion) {
            personProgress.snapTo(1f)
            wingsProgress.snapTo(1f)
            haloProgress.snapTo(1f)
            textProgress.snapTo(1f)
        } else {
            val easing = FastOutSlowInEasing
            // Phase 1: Central guardian figure gently appears
            launch {
                personProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 340,
                        easing = easing
                    )
                )
            }
            // Phase 2: Wing shapes unfold smoothly from the shoulders
            launch {
                delay(140L)
                wingsProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 480,
                        easing = easing
                    )
                )
            }
            // Phase 3: Protective halo & wordmark settle in
            launch {
                delay(240L)
                haloProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 520,
                        easing = easing
                    )
                )
            }
            launch {
                delay(320L)
                textProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 420,
                        easing = easing
                    )
                )
            }
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .let { mod ->
                if (onSkip != null) {
                    mod.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip
                    )
                } else {
                    mod
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Emblem & Protective Halo Container
            Box(
                modifier = Modifier.size(144.dp),
                contentAlignment = Alignment.Center
            ) {
                // Soft protective halo ring
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .graphicsLayer {
                            val s = 0.86f + (0.14f * haloProgress.value)
                            scaleX = s
                            scaleY = s
                            alpha = haloProgress.value
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BrandMagenta.copy(alpha = 0.14f),
                                    BrandMagentaDark.copy(alpha = 0.04f)
                                )
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            color = BrandMagenta.copy(alpha = 0.35f * haloProgress.value),
                            shape = CircleShape
                        )
                )

                // Exact supplied logo composed from undistorted vector layers
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Left Wing unfolding from left shoulder
                    Image(
                        painter = painterResource(id = R.drawable.ic_guardiangrid_logo_left_wing),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val w = wingsProgress.value
                                val scale = 0.65f + (0.35f * w)
                                scaleX = scale
                                scaleY = 0.90f + (0.10f * w)
                                transformOrigin = TransformOrigin(0.46f, 0.45f)
                                translationX = (1f - w) * 6.dp.toPx()
                                alpha = w
                            }
                    )

                    // Right Wing unfolding from right shoulder
                    Image(
                        painter = painterResource(id = R.drawable.ic_guardiangrid_logo_right_wing),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val w = wingsProgress.value
                                val scale = 0.65f + (0.35f * w)
                                scaleX = scale
                                scaleY = 0.90f + (0.10f * w)
                                transformOrigin = TransformOrigin(0.54f, 0.45f)
                                translationX = -(1f - w) * 6.dp.toPx()
                                alpha = w
                            }
                    )

                    // Central Guardian Person gently appearing
                    Image(
                        painter = painterResource(id = R.drawable.ic_guardiangrid_logo_person),
                        contentDescription = "GuardianGrid Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = personProgress.value
                                val scale = 0.92f + (0.08f * p)
                                scaleX = scale
                                scaleY = scale
                                translationY = (1f - p) * 8.dp.toPx()
                                alpha = p
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Wordmark & Tagline
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(textProgress.value)
                    .graphicsLayer {
                        translationY = (1f - textProgress.value) * 6.dp.toPx()
                    }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "GUARDIAN",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        ),
                        color = onBackgroundColor
                    )
                    Text(
                        text = "GRID",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        ),
                        color = BrandMagenta
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = GUARDIANGRID_TAGLINE,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp
                    ),
                    color = secondaryTextColor
                )
            }
        }
    }
}

@Preview(
    name = "Splash Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun GuardianGridSplashLightPreview() {
    GuardianGridTheme {
        GuardianGridSplashScreen()
    }
}

@Preview(
    name = "Splash Reduced Motion",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun GuardianGridSplashReducedMotionPreview() {
    GuardianGridTheme {
        GuardianGridSplashScreen(reducedMotionOverride = true)
    }
}
