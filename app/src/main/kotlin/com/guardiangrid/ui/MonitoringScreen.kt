package com.guardiangrid.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioCaptureStatus
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.LiveAudioMetrics
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.anomaly.MotionThresholdConfig
import com.guardiangrid.ui.brand.MonitoringStatusBadge
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Dedicated Monitoring Page.
 *
 * Organizes live subsystem health, detection sensitivity tuning, permissions,
 * audio evidence retention, and channel/system status into clear scrollable sections.
 */
@Composable
fun MonitoringScreen(
    coordinator: EmergencyCoordinatorImpl,
    locationManager: GuardianLocationManager,
    audioModule: AudioModuleStub,
    motionDetector: MotionAnomalyDetectorImpl,
    communicator: EmergencyCommunicator,
    onRequestPermissions: () -> Unit,
    onNavigateToCommunication: () -> Unit = {},
    onNavigateToOfflineQueue: () -> Unit = {},
    onNavigateToGuardianPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val state by EmergencyStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    var pendingQueueCount by remember { mutableIntStateOf(0) }
    var screamSensitivity by remember {
        mutableFloatStateOf(container?.settingsRepository?.getSettings()?.screamSensitivity ?: 0.55f)
    }
    var motionSensitivity by remember {
        mutableFloatStateOf(container?.settingsRepository?.getSettings()?.motionSensitivity ?: 1.0f)
    }
    var countdownSeconds by remember {
        mutableIntStateOf(container?.settingsRepository?.getSettings()?.countdownDurationSeconds ?: 10)
    }
    var motionCalibrationNotice by remember { mutableStateOf("") }
    var cleanupNotice by remember { mutableStateOf("") }
    var showPurgePersonalDataDialog by remember { mutableStateOf(false) }

    val audioDir = remember { File(context.filesDir, "audio_evidence") }
    var audioStorageSizeBytes by remember { mutableStateOf(0L) }
    var audioStorageCount by remember { mutableIntStateOf(0) }

    fun refreshAudioStorage() {
        if (audioDir.exists()) {
            val files = audioDir.listFiles() ?: emptyArray()
            audioStorageCount = files.size
            audioStorageSizeBytes = files.sumOf { it.length() }
        } else {
            audioStorageCount = 0
            audioStorageSizeBytes = 0L
        }
    }

    LaunchedEffect(state.recentDeliveryReceipts.size, state.activeIncident) {
        val commImpl = communicator as? EmergencyCommunicatorImpl
        pendingQueueCount = commImpl?.queueRepository?.getAll()?.count {
            it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.FAILED_RETRYING
        } ?: 0
        refreshAudioStorage()
    }

    val isTelegramSaved = remember(state.recentDeliveryReceipts) { TelegramConfigManager.isConfigured() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitoring",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Live health, detection tuning, permissions & system status",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                MonitoringStatusBadge(state = state.monitoringServiceState)
            }
        }

        // --- 1. Live Health & Status ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MONITORING HEALTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MonitoringMicrophoneCard(
                        audioModule = audioModule,
                        isMonitoringRunning = state.isAudioMonitoringRunning,
                        hasAudioPermission = state.isAudioPermissionGranted,
                        modifier = Modifier.weight(1f)
                    )

                    MonitoringMotionCard(
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
                    MonitoringHealthCard(
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
                                    onRequestPermissions()
                                } else {
                                    scope.launch { locationManager.acquireFreshLocation(3000L) }
                                }
                            }
                    )

                    val isSmsReady = state.isSmsPermissionGranted
                    val commReady = isTelegramSaved || isSmsReady
                    val commDetail = when {
                        pendingQueueCount > 0 -> "$pendingQueueCount Queued"
                        isTelegramSaved && isSmsReady -> "TG + SMS Ready"
                        isTelegramSaved -> "Telegram Ready"
                        isSmsReady -> "SMS Ready"
                        else -> "Configure Channels"
                    }
                    MonitoringHealthCard(
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

                // Guardian QR Pairing & Audio Receiver Status
                val pairedGuardiansCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.pairedGuardians?.size ?: 0
                val pairedHostsCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.pairedHosts?.size ?: 0
                val rxAlertsCount = container?.guardianConnectivityManager?.state?.collectAsState()?.value?.receivedAlerts?.size ?: 0
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToGuardianPairing() },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Guardian QR Pairing & Audio Receiver",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                                color = TextPrimary
                            )
                            Text(
                                text = "$pairedGuardiansCount Guardian(s) • $pairedHostsCount Host(s) • $rxAlertsCount Alert(s)",
                                style = MaterialTheme.typography.bodySmall,
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

        // --- 2. Adjustable Detection Settings ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DETECTION SENSITIVITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Acoustic Distress Sensitivity",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Controls vocal energy and scream classifier threshold",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "${(screamSensitivity * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandMagenta
                        )
                    }

                    Slider(
                        value = screamSensitivity,
                        onValueChange = {
                            screamSensitivity = it
                            audioModule.setSensitivity(it)
                            container?.settingsRepository?.setScreamSensitivity(it)
                        },
                        valueRange = 0.25f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = BrandMagenta,
                            activeTrackColor = BrandMagenta,
                            inactiveTrackColor = DarkSurfaceHighlight
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Motion & Fall Sensitivity",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Scales fall impact, snatch jerk, and shake detection",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "${"%.2f".format(motionSensitivity)}x",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandMagenta
                        )
                    }

                    Slider(
                        value = motionSensitivity,
                        onValueChange = {
                            motionSensitivity = it
                            motionDetector.updateConfig(MotionThresholdConfig(sensitivity = it))
                            container?.settingsRepository?.setMotionSensitivity(it)
                        },
                        valueRange = 0.50f..1.50f,
                        colors = SliderDefaults.colors(
                            thumbColor = BrandMagenta,
                            activeTrackColor = BrandMagenta,
                            inactiveTrackColor = DarkSurfaceHighlight
                        )
                    )

                    if (motionCalibrationNotice.isNotBlank()) {
                        Text(text = motionCalibrationNotice, fontSize = 11.sp, color = SafeGreen)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            motionDetector.calibrateBaseline()
                            motionCalibrationNotice = "Baseline gravity calibrated to resting orientation."
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Calibrate Motion Baseline", fontSize = 12.sp, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Verification Countdown",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Time allowed to cancel before automatic alert dispatch",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "${countdownSeconds}s",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmber
                        )
                    }

                    Slider(
                        value = countdownSeconds.toFloat(),
                        onValueChange = {
                            val secs = it.toInt().coerceIn(5, 30)
                            countdownSeconds = secs
                            coordinator.setVerificationTimeoutMs(secs * 1000L)
                            container?.settingsRepository?.setCountdownDuration(secs)
                        },
                        valueRange = 5f..30f,
                        steps = 4,
                        colors = SliderDefaults.colors(
                            thumbColor = WarningAmber,
                            activeTrackColor = WarningAmber,
                            inactiveTrackColor = DarkSurfaceHighlight
                        )
                    )
                }
            }
        }

        // --- 3. Permissions & Monitoring Requirements ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PERMISSIONS & MONITORING REQUIREMENTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    MonitoringPermissionRow("Microphone (Acoustic YAMNet Classifier)", state.isAudioPermissionGranted, Icons.Default.Mic)
                    MonitoringPermissionRow("Precise Location (Emergency Coordinates)", state.isLocationPermissionGranted, Icons.Default.LocationOn)
                    MonitoringPermissionRow("Foreground Service Notification", state.isNotificationPermissionGranted, Icons.Default.Notifications)
                    MonitoringPermissionRow("Emergency SMS Fallback", state.isSmsPermissionGranted, Icons.Default.Sms)

                    val allRequiredGranted = state.isAudioPermissionGranted && state.isLocationPermissionGranted && state.isNotificationPermissionGranted

                    if (!allRequiredGranted) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Grant Required Permissions", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("System Notification Settings", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }
        }

        // --- 4. Audio Evidence & Retention ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AUDIO EVIDENCE & RETENTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Storage: $audioStorageCount files • ${audioStorageSizeBytes / 1024} KB used in private sandbox.",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "Pre-incident rolling buffer (5s) is persisted only upon emergency confirmation.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    if (cleanupNotice.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = cleanupNotice, fontSize = 11.sp, color = SafeGreen)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            val dir = File(context.filesDir, "audio_evidence")
                            var count = 0
                            if (dir.exists()) {
                                dir.listFiles()?.filter { it.name.startsWith("preview_") || it.name.startsWith("temp_") }?.forEach {
                                    if (it.delete()) count++
                                }
                            }
                            refreshAudioStorage()
                            cleanupNotice = "Purged $count orphaned temporary previews."
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clean Temporary Previews", fontSize = 12.sp, color = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showPurgePersonalDataDialog = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyLightRed)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmergencyLightRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Erase All Incidents & Audio Evidence", fontSize = 12.sp, color = EmergencyLightRed)
                    }
                }
            }
        }

        // --- 5. Channel Status & System Information ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CHANNEL STATUS & SYSTEM INFORMATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    MonitoringChannelRow(
                        name = "Telegram Bot API",
                        status = if (isTelegramSaved) "Operational (Real HTTPS)" else "Unconfigured",
                        isOperational = isTelegramSaved,
                        icon = Icons.AutoMirrored.Filled.Send
                    )

                    MonitoringChannelRow(
                        name = "Cellular SMS",
                        status = if (state.isSmsPermissionGranted) "Ready (Android SmsManager)" else "Permission Required",
                        isOperational = state.isSmsPermissionGranted,
                        icon = Icons.Default.Sms
                    )

                    MonitoringChannelRow(
                        name = "BLE Mesh Relay",
                        status = "Offline Payload Foundation Ready",
                        isOperational = false,
                        icon = Icons.Default.Bluetooth
                    )

                    val guardianMgr = container?.guardianConnectivityManager
                    val gState = guardianMgr?.state?.collectAsState()?.value
                    val guardianRelayConfigured = container?.guardianRelayClient?.isRelayEndpointConfigured() == true
                    val pairedGuardiansCount = gState?.pairedGuardians?.size ?: 0
                    MonitoringChannelRow(
                        name = "Guardian Pairing Relay",
                        status = when {
                            pairedGuardiansCount > 0 && guardianRelayConfigured -> "$pairedGuardiansCount Guardian(s) Ready"
                            pairedGuardiansCount > 0 -> "$pairedGuardiansCount Paired (Set Relay URL)"
                            else -> "No Paired Guardians"
                        },
                        isOperational = pairedGuardiansCount > 0 && guardianRelayConfigured,
                        icon = Icons.Default.Devices
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "GuardianGrid v2.0.0 (Production Hardened)\nCore UI • Multimodal Acoustic & Motion Safety",
                        fontSize = 11.sp,
                        color = TextTertiary,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showPurgePersonalDataDialog) {
        AlertDialog(
            onDismissRequest = { showPurgePersonalDataDialog = false },
            containerColor = DarkSurfaceElevated,
            title = { Text("Erase All Incidents & Audio?", color = EmergencyLightRed, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "This permanently deletes all saved incident records and all recorded WAV audio evidence files from device storage.",
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        container?.incidentRepository?.clearAllIncidents()
                        if (audioDir.exists()) {
                            audioDir.listFiles()?.forEach { it.delete() }
                        }
                        refreshAudioStorage()
                        cleanupNotice = "All incident history and audio evidence permanently erased."
                        showPurgePersonalDataDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text("Erase Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPurgePersonalDataDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun MonitoringHealthCard(
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

            Text(text = title, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
private fun MonitoringMicrophoneCard(
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

            Text(text = "Microphone", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
private fun MonitoringMotionCard(
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

            Text(text = "Motion Sensors", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
                Text(text = "$gyroLabel • $magText", fontSize = 10.sp, color = SafeGreen, maxLines = 1)
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

@Composable
private fun MonitoringPermissionRow(name: String, isGranted: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) SafeGreen else WarningAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = name, fontSize = 12.sp, color = TextPrimary)
        }
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (isGranted) "Granted" else "Required",
            tint = if (isGranted) SafeGreen else WarningAmber,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun MonitoringChannelRow(name: String, status: String, isOperational: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isOperational) SafeGreen else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = name, fontSize = 12.sp, color = TextPrimary)
        }
        Text(
            text = status,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isOperational) SafeGreen else TextTertiary
        )
    }
}
