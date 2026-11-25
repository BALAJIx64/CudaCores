package com.guardiangrid.ui

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.DurableIncident
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.location.LiveLocationShareManager
import com.guardiangrid.location.LiveLocationShareState
import com.guardiangrid.modules.audio.AudioCaptureStatus
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.LiveAudioMetrics
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.motion.anomaly.LiveMotionMetrics
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.service.GuardianForegroundService
import com.guardiangrid.ui.brand.GuardianGridWordmark
import com.guardiangrid.ui.brand.MonitoringStatusBadge
import com.guardiangrid.ui.permissions.PermissionManager
import com.guardiangrid.ui.permissions.PermissionOnboardingDialog
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.EmergencyLightRed
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.delay
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
    onNavigateToIncidents: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onNavigateToCommunication: () -> Unit = {},
    onNavigateToOfflineQueue: () -> Unit = {},
    onNavigateToGuardianPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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
        val commImpl = communicator as? EmergencyCommunicatorImpl
        pendingQueueCount = commImpl?.queueRepository?.getAll()?.count {
            it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.FAILED_RETRYING
        } ?: 0
    }

    LaunchedEffect(state.activeIncident, state.incidentHistory.size, state.recentDeliveryReceipts.size) {
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
                    subtitle = "YOUR SAFETY. ALWAYS GUARDED."
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MonitoringStatusBadge(state = state.monitoringServiceState)

                    FilledTonalIconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = TextSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp)
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

        // --- Contacts Setup Banner (if no responders added yet) ---
        if (state.trustedContacts.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToContacts() },
                    colors = CardDefaults.cardColors(containerColor = BrandMagenta.copy(alpha = 0.10f)),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BrandMagenta.copy(alpha = 0.35f))
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add Emergency Contacts",
                            tint = BrandMagenta,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add Primary Emergency Contact",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Configure at least one trusted responder to receive SOS alerts.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = BrandMagenta
                        )
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (isServiceActive) SafeGreen.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline
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
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(statusAccent.copy(alpha = 0.14f))
                                .border(1.dp, statusAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = statusAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = if (isServiceActive) TextPrimary else TextSecondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = statusDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
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
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceActive) DarkSurfaceHighlight else BrandMagenta,
                            disabledContainerColor = DarkSurfaceHighlight
                        ),
                        shape = RoundedCornerShape(14.dp)
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
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isServiceActive) EmergencyLightRed else Color.White
                        )
                    }
                }
            }
        }

        // --- C. SOS Controls: Hold-Protected Manual SOS & Discreet Stealth SOS ---
        item {
            var sosHoldProgress by remember { mutableFloatStateOf(0f) }
            var discreetHoldProgress by remember { mutableFloatStateOf(0f) }
            var holdHintMessage by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Primary Manual SOS Control (Hold 0.8s prevents accidental pocket/scroll taps)
                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .clip(CircleShape)
                        .background(EmergencyRed.copy(alpha = 0.14f))
                        .border(1.5.dp, EmergencyRed.copy(alpha = 0.30f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(164.dp)
                            .clip(CircleShape)
                            .background(EmergencyRed)
                            .border(3.dp, EmergencyLightRed.copy(alpha = 0.65f), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        holdHintMessage = ""
                                        var triggered = false
                                        val holdJob = scope.launch {
                                            val steps = 16
                                            val totalMs = 800L
                                            for (i in 1..steps) {
                                                delay(totalMs / steps)
                                                sosHoldProgress = i.toFloat() / steps
                                            }
                                            triggered = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val freshLoc = locationManager.acquireFreshLocation(2500L)
                                            coordinator.triggerManualSos("Dashboard_SOS_Button", freshLoc)
                                            sosHoldProgress = 0f
                                        }
                                        try {
                                            awaitRelease()
                                        } finally {
                                            holdJob.cancel()
                                            if (!triggered) {
                                                sosHoldProgress = 0f
                                                holdHintMessage = "Hold SOS button briefly (0.8s) to dispatch alert"
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (sosHoldProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { sosHoldProgress },
                                modifier = Modifier.size(152.dp),
                                color = Color.White,
                                strokeWidth = 6.dp,
                                trackColor = Color.Transparent
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "SOS Emergency Trigger",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "SOS",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = if (sosHoldProgress > 0f) "KEEP HOLDING..." else "HOLD FOR HELP",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                    }
                }

                if (holdHintMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = holdHintMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = WarningAmber,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Discreet Stealth SOS Action (Hold 0.6s)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DarkSurfaceElevated)
                        .border(BorderStroke(1.dp, DarkBorder), RoundedCornerShape(20.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    holdHintMessage = ""
                                    var triggered = false
                                    val holdJob = scope.launch {
                                        val steps = 12
                                        val totalMs = 600L
                                        for (i in 1..steps) {
                                            delay(totalMs / steps)
                                            discreetHoldProgress = i.toFloat() / steps
                                        }
                                        triggered = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val freshLoc = locationManager.acquireFreshLocation(2000L)
                                        coordinator.triggerDiscreetSos("Stealth_Discreet_Button", freshLoc)
                                        discreetHoldProgress = 0f
                                    }
                                    try {
                                        awaitRelease()
                                    } finally {
                                        holdJob.cancel()
                                        if (!triggered) {
                                            discreetHoldProgress = 0f
                                            holdHintMessage = "Hold Discreet SOS (0.6s) to activate silent mode"
                                        }
                                    }
                                }
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Discreet Stealth SOS",
                                modifier = Modifier.size(16.dp),
                                tint = WarningAmber
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (discreetHoldProgress > 0f) "Activating Silent SOS..." else "Hold: Discreet Stealth SOS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        if (discreetHoldProgress > 0f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { discreetHoldProgress },
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = WarningAmber,
                                trackColor = DarkSurfaceHighlight
                            )
                        }
                    }
                }
            }
        }

        // --- C2. Share Your Location (Optional, Standalone Live Location Sharing below SOS) ---
        item {
            ShareLocationSection(
                liveLocationShareManager = container?.liveLocationShareManager,
                locationManager = locationManager,
                currentLocation = state.currentLocation,
                isLocationPermissionGranted = state.isLocationPermissionGranted,
                isSmsPermissionGranted = state.isSmsPermissionGranted,
                isGpsEnabled = state.isGpsEnabled,
                trustedContacts = state.trustedContacts,
                onRequestPermissions = { showPermissionDialog = true },
                onNavigateToContacts = onNavigateToContacts
            )
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

                    MotionTelemetryCard(
                        motionDetector = motionDetector,
                        isMotionRunning = state.isMotionMonitoringRunning,
                        isAccelAvailable = state.isAccelerometerAvailable,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasGps = state.currentLocation != null
                    val locationDetail = when {
                        !state.isLocationPermissionGranted -> "Permission needed"
                        state.currentLocation != null -> "±${state.currentLocation!!.accuracyMeters.toInt()}m accuracy"
                        state.isGpsEnabled -> "Searching fix..."
                        else -> "GPS disabled"
                    }
                    HealthIndicatorCard(
                        title = "Location GPS",
                        status = when {
                            !state.isLocationPermissionGranted -> "Denied"
                            hasGps -> "GPS Locked"
                            else -> "No Fix"
                        },
                        detail = locationDetail,
                        icon = Icons.Default.LocationOn,
                        isActive = hasGps,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (!state.isLocationPermissionGranted) {
                                    showPermissionDialog = true
                                } else {
                                    scope.launch { locationManager.acquireFreshLocation(3000L) }
                                }
                            }
                    )

                    val isTelegramReady = TelegramConfigManager.isConfigured()
                    val isSmsReady = state.isSmsPermissionGranted
                    val commReady = isTelegramReady || isSmsReady
                    val commDetail = when {
                        pendingQueueCount > 0 -> "$pendingQueueCount Queued"
                        isTelegramReady && isSmsReady -> "TG + SMS Ready"
                        isTelegramReady -> "Telegram Ready"
                        isSmsReady -> "SMS Ready"
                        else -> "Configure Channels"
                    }
                    HealthIndicatorCard(
                        title = "Emergency Net",
                        status = if (commReady) "Configured" else "Setup Needed",
                        detail = commDetail,
                        icon = Icons.Default.Sensors,
                        isActive = commReady,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (pendingQueueCount > 0) onNavigateToOfflineQueue() else onNavigateToCommunication()
                            }
                    )
                }

                // Quick-Access Guardian-to-Guardian Pairing & Audio Receiver Bar
                val pairedGuardiansCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.pairedGuardians?.size ?: 0
                val pairedHostsCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.pairedHosts?.size ?: 0
                val rxAlertsCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.receivedAlerts?.size ?: 0
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToGuardianPairing() },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Guardian QR Pairing & Audio Receiver",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "$pairedGuardiansCount Guardian(s) • $pairedHostsCount Host(s) • $rxAlertsCount Alert(s)",
                                fontSize = 10.sp,
                                color = if (pairedGuardiansCount > 0 || pairedHostsCount > 0) SafeGreen else TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open Guardian Pairing",
                            tint = BrandMagenta
                        )
                    }
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
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "View Incident History",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isActive) SafeGreen.copy(alpha = 0.30f) else MaterialTheme.colorScheme.outline
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) SafeGreen.copy(alpha = 0.14f) else DarkSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) SafeGreen else TextDisabled,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) SafeGreen else TextDisabled)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = if (isActive) TextPrimary else TextDisabled
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
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
    val isPhysicalMicActive = audioMetrics.status == AudioCaptureStatus.RECORDING &&
            audioMetrics.isPhysicalRecord &&
            !audioMetrics.isUsingMockCapture &&
            isMonitoringRunning &&
            hasAudioPermission
    val hasError = audioMetrics.status == AudioCaptureStatus.CAPTURE_ERROR ||
            audioMetrics.status == AudioCaptureStatus.PERMISSION_DENIED ||
            audioMetrics.isUsingMockCapture

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isPhysicalMicActive) SafeGreen.copy(alpha = 0.35f) else if (hasError) EmergencyRed.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isPhysicalMicActive) SafeGreen.copy(alpha = 0.14f)
                            else if (hasError) EmergencyRed.copy(alpha = 0.14f)
                            else DarkSurfaceElevated
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isPhysicalMicActive) SafeGreen else if (hasError) EmergencyRed else TextDisabled,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isPhysicalMicActive) SafeGreen else if (hasError) EmergencyRed else TextDisabled)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Microphone",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = if (isPhysicalMicActive) "Listening" else if (audioMetrics.isUsingMockCapture) "Simulated" else if (hasError) "Mic Error" else "Idle",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = if (isPhysicalMicActive) TextPrimary else if (hasError) EmergencyLightRed else TextDisabled
            )

            if (isPhysicalMicActive) {
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

@Composable
private fun MotionTelemetryCard(
    motionDetector: MotionAnomalyDetectorImpl,
    isMotionRunning: Boolean,
    isAccelAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    val metrics by motionDetector.liveMetrics.collectAsState()
    val isActive = isMotionRunning && isAccelAvailable

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isActive) SafeGreen.copy(alpha = 0.35f)
                else if (!isAccelAvailable) EmergencyRed.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outline
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) SafeGreen.copy(alpha = 0.14f)
                            else if (!isAccelAvailable) EmergencyRed.copy(alpha = 0.14f)
                            else DarkSurfaceElevated
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                        contentDescription = "Motion Sensors",
                        tint = if (isActive) SafeGreen else if (!isAccelAvailable) EmergencyRed else TextDisabled,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) SafeGreen else if (!isAccelAvailable) EmergencyRed else TextDisabled)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Motion Sensors",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = when {
                    !isAccelAvailable -> "Unavailable"
                    isActive -> "Active"
                    else -> "Standby"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = when {
                    !isAccelAvailable -> EmergencyLightRed
                    isActive -> TextPrimary
                    else -> TextDisabled
                }
            )

            if (isActive) {
                val gyroLabel = if (metrics.hasGyroscope) "Accel+Gyro" else "Accel Only"
                val magText = "%.1f m/s²".format(Locale.US, metrics.magnitude)
                Text(
                    text = "$gyroLabel • $magText",
                    fontSize = 10.sp,
                    color = SafeGreen,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                val dynamicProgress = (metrics.dynamicAcceleration / 15f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { dynamicProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = BrandMagenta,
                    trackColor = DarkSurfaceHighlight
                )
            } else {
                Text(
                    text = if (!isAccelAvailable) "No Accelerometer" else "Idle",
                    fontSize = 10.sp,
                    color = if (!isAccelAvailable) EmergencyRed else TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Standalone Material 3 "Share your location" section (completely separate from SOS).
 * Lets the user view current location status, pick one or more trusted contacts,
 * choose a duration (e.g. 1 hour or 8 hours), send a live auto-expiring link via SMS,
 * and stop sharing early at any time.
 */
@Composable
private fun ShareLocationSection(
    liveLocationShareManager: LiveLocationShareManager?,
    locationManager: GuardianLocationManager,
    currentLocation: LocationData?,
    isLocationPermissionGranted: Boolean,
    isSmsPermissionGranted: Boolean,
    isGpsEnabled: Boolean,
    trustedContacts: List<EmergencyContact>,
    onRequestPermissions: () -> Unit,
    onNavigateToContacts: () -> Unit
) {
    val shareState by liveLocationShareManager?.state?.collectAsState()
        ?: remember { mutableStateOf(LiveLocationShareState()) }
    val scope = rememberCoroutineScope()

    var isLoadingLocation by remember { mutableStateOf(false) }
    var selectedContactIds by remember(trustedContacts) {
        mutableStateOf(
            trustedContacts.filter { it.isPrimary }.map { it.id }.toSet()
        )
    }
    var selectedDurationMinutes by remember { mutableIntStateOf(60) } // Default: 1 hour
    var localErrorMessage by remember { mutableStateOf<String?>(null) }

    // Attempt initial fix when permission is granted and location is not yet loaded
    LaunchedEffect(isLocationPermissionGranted, currentLocation == null) {
        if (isLocationPermissionGranted && currentLocation == null && isGpsEnabled) {
            isLoadingLocation = true
            locationManager.acquireFreshLocation(2500L)
            isLoadingLocation = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (shareState.isSharing) SafeGreen.copy(alpha = 0.40f) else MaterialTheme.colorScheme.outline
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (shareState.isSharing) SafeGreen.copy(alpha = 0.14f)
                                else BrandMagenta.copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShareLocation,
                            contentDescription = null,
                            tint = if (shareState.isSharing) SafeGreen else BrandMagenta,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Share your location",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Optional live link via SMS • Separate from SOS",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                if (shareState.isSharing) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SafeGreen.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = SafeGreen,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Current Location Display (Loading, Permission Denied, Unavailable, or Active Fix)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DarkSurfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isLoadingLocation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = BrandMagenta
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = when {
                                    !isLocationPermissionGranted -> WarningAmber
                                    currentLocation != null -> SafeGreen
                                    else -> EmergencyLightRed
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = when {
                                    !isLocationPermissionGranted -> "Location permission denied"
                                    isLoadingLocation -> "Acquiring current location..."
                                    currentLocation != null -> "%.5f, %.5f".format(
                                        Locale.US,
                                        currentLocation.latitude,
                                        currentLocation.longitude
                                    )
                                    !isGpsEnabled -> "Location services turned off"
                                    else -> "Current location unavailable"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = when {
                                    !isLocationPermissionGranted -> "Grant location access to share live updates."
                                    isLoadingLocation -> "Waiting for GPS or network fix..."
                                    currentLocation != null -> "Accuracy ±${currentLocation.accuracyMeters.toInt()}m (${currentLocation.provider})"
                                    else -> "Enable GPS and tap Refresh to retry."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    if (!isLocationPermissionGranted) {
                        TextButton(onClick = onRequestPermissions) {
                            Text("Grant", color = WarningAmber, fontWeight = FontWeight.Bold)
                        }
                    } else if (!isLoadingLocation) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isLoadingLocation = true
                                    locationManager.acquireFreshLocation(2500L)
                                    isLoadingLocation = false
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Location",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (shareState.isSharing) {
                // Active Live Sharing Status + Stop Sharing Early
                val remainingMinutes = ((shareState.expiresAtEpochMs - System.currentTimeMillis()) / 60_000L)
                    .coerceAtLeast(0L)
                val lastUpdateText = if (shareState.lastUpdatedEpochMs > 0L) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(shareState.lastUpdatedEpochMs))
                } else {
                    "Just now"
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = SafeGreen.copy(alpha = 0.08f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Sharing with ${shareState.recipientNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Time left: ${remainingMinutes}m • Last update: $lastUpdateText",
                            style = MaterialTheme.typography.bodySmall,
                            color = SafeGreen
                        )
                        shareState.shareUrl?.let { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Button(
                    onClick = { liveLocationShareManager?.stopSharing() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.StopCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Sharing Early",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            } else {
                // Contact Selection
                Text(
                    text = "Select contacts",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )

                if (trustedContacts.isEmpty()) {
                    OutlinedButton(
                        onClick = onNavigateToContacts,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = BrandMagenta,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Trusted Contacts", color = BrandMagenta)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        trustedContacts.forEach { contact ->
                            val isSelected = selectedContactIds.contains(contact.id)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedContactIds = if (isSelected) {
                                            selectedContactIds - contact.id
                                        } else {
                                            selectedContactIds + contact.id
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) BrandMagenta.copy(alpha = 0.12f) else DarkSurfaceElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) BrandMagenta.copy(alpha = 0.45f) else DarkBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedContactIds = if (checked) {
                                                selectedContactIds + contact.id
                                            } else {
                                                selectedContactIds - contact.id
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = BrandMagenta,
                                            uncheckedColor = TextSecondary
                                        )
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${contact.phoneNumber} • ${contact.relationship}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Duration Selection (1 hour, 8 hours, etc.)
                Text(
                    text = "Sharing duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val durations = listOf(
                        30 to "30 min",
                        60 to "1 hour",
                        480 to "8 hours"
                    )
                    durations.forEach { (mins, label) ->
                        FilterChip(
                            selected = selectedDurationMinutes == mins,
                            onClick = { selectedDurationMinutes = mins },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandMagenta.copy(alpha = 0.20f),
                                selectedLabelColor = BrandMagenta
                            )
                        )
                    }
                }

                // Clear Pre-Send Disclosure
                val durationDisplay = when (selectedDurationMinutes) {
                    60 -> "1 hour"
                    480 -> "8 hours"
                    else -> "$selectedDurationMinutes minutes"
                }
                Text(
                    text = "Selected contacts will receive an SMS with a private, auto-expiring link showing your live location for $durationDisplay. Updates stop automatically when the timer ends or when you tap Stop Sharing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                if (!isSmsPermissionGranted) {
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Grant SMS Permission to Send Link", color = WarningAmber)
                    }
                }

                val displayError = localErrorMessage ?: shareState.errorMessage
                if (!displayError.isNullOrBlank()) {
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmergencyLightRed
                    )
                }

                val selectedContacts = trustedContacts.filter { selectedContactIds.contains(it.id) }
                Button(
                    onClick = {
                        localErrorMessage = null
                        val mgr = liveLocationShareManager
                        if (mgr == null) {
                            localErrorMessage = "Live location manager unavailable."
                        } else {
                            scope.launch {
                                mgr.startSharing(selectedContacts, selectedDurationMinutes)
                            }
                        }
                    },
                    enabled = selectedContacts.isNotEmpty() &&
                        isLocationPermissionGranted &&
                        currentLocation != null &&
                        isSmsPermissionGranted &&
                        !shareState.isStarting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandMagenta,
                        disabledContainerColor = DarkSurfaceHighlight
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (shareState.isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (shareState.isStarting) "Starting Live Share..." else "Continue",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}
