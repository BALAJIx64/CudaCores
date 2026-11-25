package com.guardiangrid.ui.brand

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.R
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber

const val GUARDIANGRID_TAGLINE = "Your Safety. Always Guarded."

/**
 * Scalable GuardianGrid Brand Emblem.
 * Symmetrical winged human figure vector rendered from Android vector drawable.
 */
@Composable
fun GuardianGridEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Image(
        painter = painterResource(id = R.drawable.ic_guardiangrid_emblem),
        contentDescription = "GuardianGrid Emblem",
        modifier = modifier.size(size)
    )
}

/**
 * Official Horizontal GuardianGrid Wordmark.
 * Features the emblem and stylized "GUARDIANGRID" typography.
 */
@Composable
fun GuardianGridWordmark(
    modifier: Modifier = Modifier,
    emblemSize: Dp = 32.dp,
    subtitle: String? = GUARDIANGRID_TAGLINE
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuardianGridEmblem(size = emblemSize)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GUARDIAN",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "GRID",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = BrandMagenta,
                    letterSpacing = 1.2.sp
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

/**
 * Compact pill badge reflecting real foreground service monitoring state.
 */
@Composable
fun MonitoringStatusBadge(
    state: MonitoringServiceState,
    modifier: Modifier = Modifier
) {
    val (statusText, dotColor, containerColor) = when (state) {
        MonitoringServiceState.ACTIVE -> Triple("ACTIVE", SafeGreen, SafeGreen.copy(alpha = 0.15f))
        MonitoringServiceState.STARTING -> Triple("STARTING", WarningAmber, WarningAmber.copy(alpha = 0.15f))
        MonitoringServiceState.STOPPING -> Triple("STOPPING", WarningAmber, WarningAmber.copy(alpha = 0.15f))
        MonitoringServiceState.STOPPED -> Triple("INACTIVE", TextDisabled, DarkSurfaceElevated)
        MonitoringServiceState.PERMISSION_REQUIRED -> Triple("NEEDS PERMISSION", WarningAmber, WarningAmber.copy(alpha = 0.15f))
        MonitoringServiceState.ERROR -> Triple("ERROR", EmergencyRed, EmergencyRed.copy(alpha = 0.15f))
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(1.dp, dotColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = dotColor,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Short branded GuardianGrid Splash Screen displaying the official logo and tagline:
 * "Your Safety. Always Guarded."
 */
@Composable
fun GuardianGridSplashScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.88f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "splashScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "splashAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .scale(scale)
                .alpha(alpha)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(CircleShape)
                    .background(BrandMagenta.copy(alpha = 0.12f))
                    .border(1.5.dp, BrandMagenta.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                GuardianGridEmblem(size = 76.dp)
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GUARDIAN",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "GRID",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = BrandMagenta,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = GUARDIANGRID_TAGLINE,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                letterSpacing = 0.6.sp
            )
        }
    }
}
