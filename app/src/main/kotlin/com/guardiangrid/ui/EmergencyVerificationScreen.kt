package com.guardiangrid.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.EmergencyLightRed
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-urgency verification countdown screen.
 * Displayed when autonomous sensors (acoustic scream classifier or motion anomaly detector)
 * detect distress conditions requiring user verification before emergency escalation.
 *
 * Implements:
 * - Urgent countdown timer with visual progress ring
 * - Incident distress type & confidence percentage
 * - Captured GPS coordinates and accuracy
 * - Immediate "I AM SAFE" cancel alert action
 * - Immediate "DISPATCH NOW" bypass action
 */
@Composable
fun EmergencyVerificationScreen(
    coordinator: EmergencyCoordinatorImpl,
    onCancelled: () -> Unit = {},
    onDispatched: () -> Unit = {}
) {
    val state by EmergencyStateHolder.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val remainingMs = state.verificationCountdownRemainingMs
    val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val totalTimeoutMs = state.verificationTotalTimeoutMs.toFloat().coerceAtLeast(1000f)
    val progress = (remainingMs / totalTimeoutMs).coerceIn(0f, 1f)

    val activeIncident = state.activeIncident
    val lastSignal = activeIncident?.triggeringSignals?.firstOrNull() ?: state.lastDetectedSignal
    val distressType = lastSignal?.type?.name ?: activeIncident?.distressType?.name ?: "MULTIMODAL DISTRESS"
    val confidence = ((lastSignal?.confidence ?: 0.85f) * 100).toInt()
    val location = state.currentLocation ?: activeIncident?.location

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // --- Header Section ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(WarningAmber.copy(alpha = 0.2f))
                    .border(2.dp, WarningAmber, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = WarningAmber,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "DISTRESS VERIFICATION",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = WarningAmber,
                letterSpacing = 1.sp
            )

            Text(
                text = "Autonomous anomaly detected. Confirm safety or alert dispatches automatically.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3-Stage Lifecycle Clarification: Detection -> Verification -> Dispatch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VerificationStageChip(label = "1. DETECTED", isDone = true, isCurrent = false)
                Text("→", color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VerificationStageChip(label = "2. VERIFYING", isDone = false, isCurrent = true)
                Text("→", color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VerificationStageChip(label = "3. DISPATCH", isDone = false, isCurrent = false)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Ticking Countdown Ring ---
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(170.dp),
                color = if (remainingSeconds <= 3) EmergencyLightRed else WarningAmber,
                strokeWidth = 10.dp,
                trackColor = DarkSurface
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${remainingSeconds}s",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    color = if (remainingSeconds <= 3) EmergencyLightRed else TextPrimary
                )
                Text(
                    text = "UNTIL DISPATCH",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Incident & Sensor Anomaly Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = "Sensor",
                            tint = WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = distressType.replace('_', ' '),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(WarningAmber.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$confidence% CONFIDENCE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmber
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // GPS Location Capture Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Location",
                        tint = if (location != null) SafeGreen else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (location != null) {
                            "Lat: ${"%.5f".format(location.latitude)}, Lon: ${"%.5f".format(location.longitude)} (±${location.accuracyMeters.toInt()}m)"
                        } else {
                            "Acquiring high-accuracy GPS coordinates..."
                        },
                        fontSize = 11.sp,
                        color = if (location != null) TextPrimary else TextSecondary
                    )
                }

                if (lastSignal?.metadata?.isNotEmpty() == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Source: ${lastSignal.source}",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- Action Buttons (Hold-to-Cancel prevents accidental dismissal) ---
        var cancelHoldProgress by remember { mutableFloatStateOf(0f) }
        var cancelHint by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (cancelHint.isNotBlank()) {
                Text(
                    text = cancelHint,
                    fontSize = 11.sp,
                    color = WarningAmber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Hold-to-Cancel False Alarm (0.8s press prevents accidental pocket/cheek taps)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SafeGreen)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                cancelHint = ""
                                var cancelled = false
                                val holdJob = scope.launch {
                                    val steps = 16
                                    val totalMs = 800L
                                    for (i in 1..steps) {
                                        delay(totalMs / steps)
                                        cancelHoldProgress = i.toFloat() / steps
                                    }
                                    cancelled = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val wasServiceRunning = state.monitoringServiceState == MonitoringServiceState.ACTIVE
                                    coordinator.cancelEmergency("User confirmed safe on verification screen")
                                    if (wasServiceRunning) {
                                        coordinator.resumeMonitoringAfterUserClearance()
                                    }
                                    cancelHoldProgress = 0f
                                    onCancelled()
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    holdJob.cancel()
                                    if (!cancelled) {
                                        cancelHoldProgress = 0f
                                        cancelHint = "Press & hold 'I AM SAFE' for 0.8s to cancel alert"
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Hold to Cancel Alert",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (cancelHoldProgress > 0f) "CONFIRMING SAFE..." else "HOLD: I AM SAFE — CANCEL",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    if (cancelHoldProgress > 0f) {
                        Spacer(modifier = Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { cancelHoldProgress },
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                    }
                }
            }

            // Dispatch Immediately Button (Accelerate)
            OutlinedButton(
                onClick = {
                    coordinator.forceDispatchNow()
                    onDispatched()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                border = BorderStroke(1.dp, EmergencyRed),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "DISPATCH NOW (BYPASS TIMER)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmergencyLightRed
                )
            }
        }
    }
}

@Composable
private fun VerificationStageChip(label: String, isDone: Boolean, isCurrent: Boolean) {
    val color = when {
        isDone -> SafeGreen
        isCurrent -> WarningAmber
        else -> TextTertiary
    }
    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = if (isCurrent || isDone) FontWeight.Black else FontWeight.Medium,
        color = color
    )
}
