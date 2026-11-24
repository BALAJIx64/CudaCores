package com.guardiangrid.ui

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.DurableIncident
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioCaptureStatus
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.LiveAudioMetrics
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.service.GuardianForegroundService
import com.guardiangrid.ui.brand.GuardianGridWordmark
import com.guardiangrid.ui.brand.MonitoringStatusBadge
import com.guardiangrid.ui.permissions.PermissionManager
import com.guardiangrid.ui.permissions.PermissionOnboardingDialog
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.BrandMagentaDark
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.EmergencyDarkRed
import com.guardiangrid.ui.theme.EmergencyLightRed
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.InfoBlue
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Redesigned GuardianGrid Production Dashboard (Main Control Centre).
 *
 * Implements:
 * A. Header: Official GuardianGrid emblem & wordmark, real service monitoring badge, settings access.
 * B. Protection Status: Clear ACTIVE / INACTIVE representation tied to actual foreground service state.
 * C. Emergency SOS: Immediate tactile manual dispatch and discreet stealth SOS.
 * D. Subsystem Health Grid: Real microphone, motion, location, and communication/queue states.
 * E. Recent Activity: Latest durable incident with empty state handling and navigation to full history.
 */
@Composable
fun DashboardScreen(
    coordinator: EmergencyCoordinatorImpl,
    locationManager: GuardianLocationManager,
    audioModule: AudioModuleStub,
    motionDetector: MotionAnomalyDetectorImpl,
    communicator: EmergencyCommunicator,
    onRequestPermissions: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToIncidents: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val state by EmergencyStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Fetch latest incident from durable repository
    var latestIncident by remember { mutableStateOf<DurableIncident?>(null) }
    var pendingQueueCount by remember { mutableStateOf(0) }

    fun refreshDashboardData() {
        latestIncident = container?.incidentRepository?.getAllIncidents()
            ?.maxByOrNull { it.startTimeEpochMs }
        val commImpl = container?.communicator as? EmergencyCommunicatorImpl
        pendingQueueCount = commImpl?.queueRepository?.getAll()?.count {
            it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.FAILED_RETRYING
        } ?: 0
    }

    LaunchedEffect(state.activeIncident, state.incidentHistory.size) {
        refreshDashboardData()
    }

    if (showPermissionDialog) {
        PermissionOnboardingDialog(
            onDismissRequest = { showPermissionDialog = false },
            onRequestPermissions = {
                showPermissionDialog = false
                onRequestPermissions()
            }
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dashPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dashPulseScale"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- A. Header: Emblem, Wordmark, Real Badge, Settings ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GuardianGridWordmark(
                    emblemSize = 36.dp,
                    subtitle = "AUTONOMOUS SAFETY NET"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MonitoringStatusBadge(state = state.monitoringServiceState)

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // --- Permission Warning Banner (if required permissions missing) ---
        val missingPermissions = !state.isAudioPermissionGranted || !state.isLocationPermissionGranted
        if (missingPermissions) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPermissionDialog = true },
                    colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(WarningAmber.copy(alpha = 0.35f)))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Safety Permissions Required",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber
                            )
                            Text(
                                text = "Microphone & Location required for full background protection. Tap to grant.",
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // --- B. Protection Status (Large, immediately understandable ACTIVE/INACTIVE) ---
        item {
            val isServiceActive = state.monitoringServiceState == MonitoringServiceState.ACTIVE
            val isTransitioning = state.monitoringServiceState == MonitoringServiceState.STARTING ||
                    state.monitoringServiceState == MonitoringServiceState.STOPPING

            val (statusTitle, statusDescription, statusAccent) = when (state.monitoringServiceState) {
                MonitoringServiceState.ACTIVE -> Triple(
                    "PROTECTION ACTIVE",
                    "Acoustic scream & motion anomaly detection running in foreground service.",
                    SafeGreen
                )
                MonitoringServiceState.STARTING -> Triple(
                    "INITIALIZING SENSORS",
                    "Connecting hardware listeners and calibrating baseline...",
                    WarningAmber
                )
                MonitoringServiceState.STOPPING -> Triple(
                    "STOPPING SERVICE",
                    "Safely unregistering sensor listeners and flushing buffers...",
                    WarningAmber
                )
                MonitoringServiceState.PERMISSION_REQUIRED -> Triple(
                    "PERMISSION REQUIRED",
                    "Microphone permission is required to analyze ambient distress sounds.",
                    WarningAmber
                )
                MonitoringServiceState.ERROR -> Triple(
                    "MONITORING ERROR",
                    state.statusMessage.ifBlank { "Unable to activate hardware sensors. Check permissions." },
                    EmergencyRed
                )
                MonitoringServiceState.STOPPED -> Triple(
                    "PROTECTION INACTIVE",
                    "Continuous safety monitoring is paused. Tap below to activate background guard.",
                    TextDisabled
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(18.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (isServiceActive) SafeGreen.copy(alpha = 0.3f) else DarkBorder
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(statusAccent.copy(alpha = 0.15f))
                                .border(1.5.dp, statusAccent.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isServiceActive) Icons.Default.Security else Icons.Default.Security,
                                contentDescription = null,
                                tint = statusAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isServiceActive) TextPrimary else TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = statusDescription,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Protection Toggle Button (never fakes state)
                    Button(
                        onClick = {
                            if (isServiceActive) {
                                GuardianForegroundService.stop(context)
                            } else {
                                if (!PermissionManager.isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                                    EmergencyStateHolder.setMonitoringServiceState(
                                        MonitoringServiceState.PERMISSION_REQUIRED,
                                        "Microphone permission required for audio distress monitoring."
                                    )
                                    onRequestPermissions()
                                } else {
                                    GuardianForegroundService.start(context)
                                }
                            }
                        },
                        enabled = !isTransitioning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceActive) DarkSurfaceHighlight else BrandMagenta,
                            disabledContainerColor = DarkSurfaceHighlight
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = when (state.monitoringServiceState) {
                                MonitoringServiceState.ACTIVE -> "Stop Active Protection"
                                MonitoringServiceState.STARTING -> "Starting..."
                                MonitoringServiceState.STOPPING -> "Stopping..."
                                MonitoringServiceState.PERMISSION_REQUIRED -> "Grant Microphone Permission"
                                MonitoringServiceState.ERROR -> "Retry Activation"
                                MonitoringServiceState.STOPPED -> "Activate Protection"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isServiceActive) EmergencyLightRed else Color.White
                        )
                    }
                }
            }
        }

        // --- C. SOS Controls: Prominent Manual SOS & Discreet Stealth SOS ---
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Primary Manual SOS Button
                Button(
                    onClick = {
                        scope.launch {
                            val freshLoc = locationManager.acquireFreshLocation(3000L)
                            coordinator.triggerManualSos("Dashboard_SOS_Button", freshLoc)

                            val payload = AlertPayload(
                                incidentId = "MANUAL-${System.currentTimeMillis()}",
                                timestampEpochMs = System.currentTimeMillis(),
                                distressType = DistressType.MANUAL_SOS,
                                confidence = 1.0f,
                                emergencyMessage = "MANUAL SOS ACTIVATED: User requires urgent emergency assistance!",
                                location = freshLoc
                            )
                            val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                            receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                        }
                    },
                    modifier = Modifier
                        .size(165.dp)
                        .clip(CircleShape)
                        .border(4.dp, EmergencyLightRed.copy(alpha = 0.5f), CircleShape),
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = "SOS",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SOS",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "TAP FOR HELP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Discreet Stealth SOS Action
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val freshLoc = locationManager.acquireFreshLocation(2000L)
                            coordinator.triggerDiscreetSos("Stealth_Discreet_Button")
                            val payload = AlertPayload(
                                incidentId = "DISCREET-${System.currentTimeMillis()}",
                                timestampEpochMs = System.currentTimeMillis(),
                                distressType = DistressType.SILENT_ACTIVATION,
                                confidence = 1.0f,
                                emergencyMessage = "DISCREET SILENT SOS: User in distress, silent stealth activation.",
                                location = freshLoc
                            )
                            val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                            receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = WarningAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Discreet Stealth SOS (Silent)",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // --- D. Monitoring Health (Real Subsystem State Grid) ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MONITORING HEALTH",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )

                // 2x2 Grid of Subsystems
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MicrophoneTelemetryCard(
                        audioModule = audioModule,
                        isMonitoringRunning = state.isAudioMonitoringRunning,
                        hasAudioPermission = state.isAudioPermissionGranted,
                        modifier = Modifier.weight(1f)
                    )

                    val isMotionActive = state.isMotionMonitoringRunning
                    HealthIndicatorCard(
                        title = "Motion Sensors",
                        status = if (isMotionActive) "Active" else "Standby",
                        detail = if (isMotionActive) "Accel + Gyro" else "Idle",
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        isActive = isMotionActive,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasGps = state.currentLocation != null
                    val locationDetail = state.currentLocation?.let { "±${it.accuracyMeters.toInt()}m accuracy" } ?: "Searching..."
                    HealthIndicatorCard(
                        title = "Location GPS",
                        status = if (hasGps) "GPS Locked" else "No Fix",
                        detail = locationDetail,
                        icon = Icons.Default.LocationOn,
                        isActive = hasGps,
                        modifier = Modifier.weight(1f)
                    )

                    val isTelegramReady = TelegramConfigManager.isConfigured()
                    val commDetail = if (pendingQueueCount > 0) "$pendingQueueCount Pending" else if (isTelegramReady) "Ready" else "Not Configured"
                    HealthIndicatorCard(
                        title = "Emergency Net",
                        status = if (isTelegramReady) "Telegram Ready" else "Setup Needed",
                        detail = commDetail,
                        icon = Icons.Default.Sensors,
                        isActive = isTelegramReady,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // --- E. Recent Activity (Latest Real Incident or Empty State) ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT ACTIVITY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )

                    TextButton(onClick = onNavigateToIncidents) {
                        Text(
                            text = "View History",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandMagenta
                        )
                    }
                }

                if (latestIncident != null) {
                    val inc = latestIncident!!
                    val dateFormatted = remember(inc.startTimeEpochMs) {
                        SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(inc.startTimeEpochMs))
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToIncidents() },
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = inc.triggerType.name.replace("_", " "),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (inc.triggerType) {
                                            DistressType.MANUAL_SOS -> EmergencyLightRed
                                            DistressType.SCREAM -> WarningAmber
                                            else -> BrandMagenta
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = inc.incidentId,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$dateFormatted • Delivery: ${inc.deliveryStatus}",
                                    fontSize = 11.sp,
                                    color = TextTertiary
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SafeGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "No recent incidents. All safety systems standing by.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HealthIndicatorCard(
    title: String,
    status: String,
    detail: String,
    icon: ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isActive) SafeGreen.copy(alpha = 0.25f) else DarkBorder
            )
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) SafeGreen else TextDisabled,
                    modifier = Modifier.size(18.dp)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isActive) SafeGreen else TextDisabled)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Text(
                text = status,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) TextPrimary else TextDisabled
            )
            Text(
                text = detail,
                fontSize = 10.sp,
                color = TextTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MicrophoneTelemetryCard(
    audioModule: AudioModuleStub?,
    isMonitoringRunning: Boolean,
    hasAudioPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val audioMetrics by audioModule?.liveMetrics?.collectAsState() ?: remember { mutableStateOf(LiveAudioMetrics()) }
    val isRecording = audioMetrics.status == AudioCaptureStatus.RECORDING && isMonitoringRunning && hasAudioPermission
    val hasError = audioMetrics.status == AudioCaptureStatus.CAPTURE_ERROR || audioMetrics.status == AudioCaptureStatus.PERMISSION_DENIED

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isRecording) SafeGreen.copy(alpha = 0.3f) else if (hasError) EmergencyRed.copy(alpha = 0.3f) else DarkBorder
            )
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isRecording) SafeGreen else if (hasError) EmergencyRed else TextDisabled,
                    modifier = Modifier.size(18.dp)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) SafeGreen else if (hasError) EmergencyRed else TextDisabled)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Microphone",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Text(
                text = if (isRecording) "Listening" else if (hasError) "Error" else "Idle",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) TextPrimary else if (hasError) EmergencyLightRed else TextDisabled
            )

            if (isRecording) {
                val dbfsDisplay = if (audioMetrics.dbfs > -95f) "${audioMetrics.dbfs.toInt()} dBFS" else "-∞ dBFS"
                Text(
                    text = "${audioMetrics.activeSource} • $dbfsDisplay",
                    fontSize = 10.sp,
                    color = SafeGreen,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                val normalizedLevel = ((audioMetrics.dbfs + 60f) / 60f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { normalizedLevel },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = BrandMagenta,
                    trackColor = DarkSurfaceHighlight
                )
            } else {
                Text(
                    text = if (hasError) (audioMetrics.errorMessage ?: "Capture failed") else "Standby",
                    fontSize = 10.sp,
                    color = if (hasError) EmergencyRed else TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}
