package com.guardiangrid.ui

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.guardiangrid.ui.brand.rememberIsReducedMotion
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
import com.guardiangrid.ui.theme.InfoBlue
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ShieldButtonShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.50f, h * 0.04f)
    lineTo(w * 0.90f, h * 0.18f)
    lineTo(w * 0.90f, h * 0.48f)
    cubicTo(
        w * 0.90f, h * 0.74f,
        w * 0.70f, h * 0.89f,
        w * 0.50f, h * 0.97f
    )
    cubicTo(
        w * 0.30f, h * 0.89f,
        w * 0.10f, h * 0.74f,
        w * 0.10f, h * 0.48f
    )
    lineTo(w * 0.10f, h * 0.18f)
    close()
}

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
    val listState = rememberLazyListState()
    val isReducedMotion = rememberIsReducedMotion()
    var showPermissionDialog by remember { mutableStateOf(false) }
    var isLiveLocationExpanded by remember { mutableStateOf(false) }

    val liveShareManager = container?.liveLocationShareManager
    val liveShareState by liveShareManager?.state?.collectAsState()
        ?: remember { mutableStateOf(LiveLocationShareState()) }

    // Track confirmed success states to trigger brief, faint matching screen glows
    var activeScreenGlowColor by remember { mutableStateOf<Color?>(null) }
    val glowAlpha = remember { Animatable(0f) }

    var prevHasIncident by remember { mutableStateOf(state.activeIncident != null) }
    LaunchedEffect(state.activeIncident) {
        val hasIncident = state.activeIncident != null
        if (!prevHasIncident && hasIncident) {
            activeScreenGlowColor = EmergencyRed
        }
        prevHasIncident = hasIncident
    }

    var prevIsSharing by remember { mutableStateOf(liveShareState.isSharing) }
    LaunchedEffect(liveShareState.isSharing) {
        if (!prevIsSharing && liveShareState.isSharing) {
            activeScreenGlowColor = SafeGreen
        }
        prevIsSharing = liveShareState.isSharing
    }

    var prevMonitoringState by remember { mutableStateOf(state.monitoringServiceState) }
    LaunchedEffect(state.monitoringServiceState) {
        if (prevMonitoringState != MonitoringServiceState.ACTIVE &&
            state.monitoringServiceState == MonitoringServiceState.ACTIVE
        ) {
            activeScreenGlowColor = InfoBlue
        }
        prevMonitoringState = state.monitoringServiceState
    }

    LaunchedEffect(activeScreenGlowColor) {
        if (activeScreenGlowColor != null) {
            val peakAlpha = if (isReducedMotion) 0.10f else 0.16f
            val fadeInMs = if (isReducedMotion) 80 else 180
            val fadeOutMs = if (isReducedMotion) 220 else 720
            glowAlpha.snapTo(0f)
            glowAlpha.animateTo(peakAlpha, animationSpec = tween(fadeInMs, easing = FastOutSlowInEasing))
            glowAlpha.animateTo(0f, animationSpec = tween(fadeOutMs, easing = FastOutSlowInEasing))
            activeScreenGlowColor = null
        }
    }

    // Particle burst triggers (incremented on successful action activation)
    var sosBurstTrigger by remember { mutableIntStateOf(0) }
    var locationBurstTrigger by remember { mutableIntStateOf(0) }
    var protectionBurstTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeScreenGlowColor) {
        when (activeScreenGlowColor) {
            EmergencyRed -> sosBurstTrigger++
            SafeGreen -> locationBurstTrigger++
            InfoBlue -> protectionBurstTrigger++
        }
    }

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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
    val sosDiameter = minOf(
        maxWidth * 0.84f,
        (maxHeight - 250.dp).coerceAtLeast(180.dp)
    ).coerceAtLeast(180.dp)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- A. Compact Header + Clear Live Protection Status Line ---
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GuardianGridWordmark(
                        emblemSize = 30.dp,
                        subtitle = "Your Safety. Always Guarded."
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MonitoringStatusBadge(state = state.monitoringServiceState)

                        FilledTonalIconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.size(40.dp),
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

                val (statusLineText, statusLineColor) = when (state.monitoringServiceState) {
                    MonitoringServiceState.ACTIVE -> "Live Protection is active" to SafeGreen
                    MonitoringServiceState.STARTING -> "Live Protection is starting..." to WarningAmber
                    MonitoringServiceState.STOPPING -> "Live Protection is stopping..." to WarningAmber
                    MonitoringServiceState.PERMISSION_REQUIRED -> "Microphone permission needed for Live Protection" to WarningAmber
                    MonitoringServiceState.ERROR -> "Live Protection encountered an error" to EmergencyRed
                    MonitoringServiceState.STOPPED -> "Live Protection is off" to TextSecondary
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusLineColor)
                    )
                    Text(
                        text = statusLineText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = statusLineColor
                    )
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

        // --- B. Redesigned Home Actions: Centre Large SOS Circle + Bottom Action Row (Share Location | Live Protection) ---
        item {
            var sosHoldProgress by remember { mutableFloatStateOf(0f) }
            var sosPressed by remember { mutableStateOf(false) }
            var holdHintMessage by remember { mutableStateOf("") }

            val isServiceActive = state.monitoringServiceState == MonitoringServiceState.ACTIVE
            val isTransitioning = state.monitoringServiceState == MonitoringServiceState.STARTING ||
                state.monitoringServiceState == MonitoringServiceState.STOPPING

            val sosScale by animateFloatAsState(
                targetValue = if (sosPressed && !isReducedMotion) 0.96f else 1f,
                animationSpec = tween(durationMillis = 120),
                label = "sosPressScale"
            )

            val locationInteraction = remember { MutableInteractionSource() }
            val locationPressed by locationInteraction.collectIsPressedAsState()
            val locationScale by animateFloatAsState(
                targetValue = if (locationPressed && !isReducedMotion) 0.96f else 1f,
                animationSpec = tween(durationMillis = 120),
                label = "locationPressScale"
            )

            val protectionInteraction = remember { MutableInteractionSource() }
            val protectionPressed by protectionInteraction.collectIsPressedAsState()
            val protectionScale by animateFloatAsState(
                targetValue = if (protectionPressed && !isReducedMotion) 0.96f else 1f,
                animationSpec = tween(durationMillis = 120),
                label = "protectionPressScale"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Centre — Very large dynamically scaled circular Emergency SOS button (press-and-hold 0.8s)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(sosDiameter)
                            .aspectRatio(1f)
                            .scale(sosScale)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        holdHintMessage = ""
                                        sosPressed = true
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
                                            sosPressed = false
                                            holdJob.cancel()
                                            if (!triggered) {
                                                sosHoldProgress = 0f
                                                holdHintMessage = "Hold SOS briefly (0.8s) to dispatch alert"
                                            }
                                        }
                                    }
                                )
                            },
                        shape = CircleShape,
                        color = EmergencyRed,
                        shadowElevation = 12.dp,
                        border = BorderStroke(3.dp, BrandMagenta.copy(alpha = 0.85f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (sosHoldProgress > 0f) {
                                CircularProgressIndicator(
                                    progress = { sosHoldProgress },
                                    modifier = Modifier.size(sosDiameter * 0.92f),
                                    color = Color.White,
                                    strokeWidth = 7.dp,
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
                                    modifier = Modifier.size((sosDiameter * 0.28f).coerceIn(48.dp, 88.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "SOS",
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Emergency SOS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (holdHintMessage.isNotBlank()) holdHintMessage else "Hold briefly to alert your emergency contacts",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (holdHintMessage.isNotBlank()) WarningAmber else TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                // 2. Bottom Action Row — Two rectangular buttons side-by-side: Share Location | Live Protection
                val protectionShortLabel = when (state.monitoringServiceState) {
                    MonitoringServiceState.ACTIVE -> "Stop Protection"
                    MonitoringServiceState.STARTING -> "Starting..."
                    MonitoringServiceState.STOPPING -> "Stopping..."
                    MonitoringServiceState.PERMISSION_REQUIRED -> "Grant Mic"
                    MonitoringServiceState.ERROR -> "Retry"
                    MonitoringServiceState.STOPPED -> "Live Protection"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Share Location (Rectangular)
                    Surface(
                        onClick = {
                            isLiveLocationExpanded = !isLiveLocationExpanded
                            if (isLiveLocationExpanded) {
                                scope.launch { listState.animateScrollToItem(2) }
                            }
                        },
                        interactionSource = locationInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .scale(locationScale),
                        shape = RoundedCornerShape(16.dp),
                        color = SafeGreen,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.5.dp, Color(0xFF86EFAC))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = "Share Location",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (liveShareState.isSharing) "Sharing Active" else "Share Location",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Right: Activate Live Protection (Rectangular)
                    Surface(
                        onClick = {
                            if (!isTransitioning) {
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
                            }
                        },
                        interactionSource = protectionInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .scale(protectionScale),
                        shape = RoundedCornerShape(16.dp),
                        color = InfoBlue,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.5.dp, Color(0xFF93C5FD))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isTransitioning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = protectionShortLabel,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = protectionShortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Inline Live-Location Sharing Flow (expanded on tap or when active)
                AnimatedVisibility(visible = isLiveLocationExpanded || liveShareState.isSharing) {
                    ShareLocationSection(
                        liveLocationShareManager = liveShareManager,
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
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Faint matching screen glow overlay ONLY after confirmed action success
    val glowColor = activeScreenGlowColor
    if (glowColor != null && glowAlpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glowAlpha.value * 0.45f),
                            glowColor.copy(alpha = glowAlpha.value)
                        )
                    )
                )
        )
    }
    }
}

/**
 * Restrained, battery-friendly particle burst along the perimeter of an action button.
 * Fires once on confirmed activation and respects Android's reduced-motion setting.
 */
@Composable
private fun SubtleParticleBurst(
    triggerKey: Int,
    color: Color,
    isReducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    if (isReducedMotion || triggerKey <= 0) return

    val progress = remember { Animatable(0f) }
    LaunchedEffect(triggerKey) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
        )
    }

    val t = progress.value
    if (t <= 0f || t >= 1f) return

    Canvas(modifier = modifier) {
        val count = 8
        val alpha = (1f - t) * 0.42f
        val maxRadius = size.minDimension * 0.55f
        val dist = maxRadius * (0.35f + 0.65f * t)
        val dotRadius = 3.5.dp.toPx() * (1f - 0.35f * t)

        for (i in 0 until count) {
            val angle = (2.0 * PI * i / count)
            val cx = center.x + (cos(angle) * (size.width * 0.36f + dist * 0.35f)).toFloat()
            val cy = center.y + (sin(angle) * (size.height * 0.36f + dist * 0.35f)).toFloat()
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotRadius,
                center = Offset(cx, cy)
            )
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
