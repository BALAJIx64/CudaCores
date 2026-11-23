package com.guardiangrid.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.core.interfaces.EmergencyCoordinator
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.ProtectionMode
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.ui.permissions.PermissionOnboardingDialog
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.EmergencyDarkRed
import com.guardiangrid.ui.theme.EmergencyLightRed
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.InfoBlue
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    coordinator: EmergencyCoordinatorImpl,
    locationManager: GuardianLocationManager,
    audioModule: AudioModuleStub,
    motionDetector: MotionAnomalyDetectorImpl,
    communicator: EmergencyCommunicator,
    onRequestPermissions: () -> Unit
) {
    val state by EmergencyStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showPermissionDialog by remember { mutableStateOf(false) }

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
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header & Connectivity Status Badge
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GuardianGrid",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Multimodal Distress & Resilient Emergency Net",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                // Connectivity / Mode Chip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isProtectionActive) SafeGreen.copy(alpha = 0.2f) else DarkSurfaceElevated
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (state.isProtectionActive) "PROTECTED" else "STANDBY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.isProtectionActive) SafeGreen else TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Permission Health Reminder Card (when permissions missing)
        val missingPermissions = !state.isAudioPermissionGranted || !state.isLocationPermissionGranted
        if (missingPermissions) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPermissionDialog = true },
                    colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                                text = "Microphone & Location required for full protection. Tap to configure.",
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Live Protection Status Banner
        item {
            StatusBanner(state.emergencyLevel, state.statusMessage)
        }

        // Active Incident Information Card (if emergency in progress)
        state.activeIncident?.let { incident ->
            item {
                ActiveIncidentCard(incident = incident, onCancel = {
                    coordinator.cancelEmergency("User Cancelled via Dashboard")
                })
            }
        }

        // Central Emergency SOS Buttons (Tactile Manual SOS & Discreet Stealth SOS)
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                                distressType = DistressType.MANUAL_SOS,
                                confidence = 1.0f,
                                location = freshLoc,
                                emergencyMessage = "MANUAL SOS ACTIVATED: User requires urgent emergency assistance!"
                            )
                            val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                            receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                        }
                    },
                    modifier = Modifier
                        .size(175.dp)
                        .clip(CircleShape)
                        .border(4.dp, EmergencyLightRed.copy(alpha = 0.7f), CircleShape),
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
                            modifier = Modifier.size(46.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SOS",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "PRESS FOR HELP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Discreet Stealth SOS Button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val freshLoc = locationManager.acquireFreshLocation(2000L)
                            coordinator.triggerDiscreetSos("Stealth_Discreet_Button")
                            val payload = AlertPayload(
                                incidentId = "DISCREET-${System.currentTimeMillis()}",
                                distressType = DistressType.SILENT_ACTIVATION,
                                confidence = 1.0f,
                                location = freshLoc,
                                emergencyMessage = "DISCREET SILENT SOS: User in distress, silent activation."
                            )
                            val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                            receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = WarningAmber
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Discreet Stealth SOS (Silent)",
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // Protection Controls Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (state.isProtectionActive) {
                            coordinator.stopProtection()
                            locationManager.stopLocationUpdates()
                        } else {
                            coordinator.startProtection()
                            locationManager.startLocationUpdates()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isProtectionActive) WarningAmber else SafeGreen
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (state.isProtectionActive) Icons.Default.Security else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isProtectionActive) "Stop Protection" else "Activate Protection",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = { showPermissionDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Permissions", fontSize = 12.sp)
                }
            }
        }

        // Live GPS Location Telemetry Card
        item {
            LocationTelemetryCard(
                location = state.currentLocation,
                isGpsEnabled = state.isGpsEnabled,
                onRefreshLocation = {
                    scope.launch {
                        locationManager.acquireFreshLocation(3000L)
                    }
                }
            )
        }

        // Subsystems Status Indicators Row
        item {
            Text(
                text = "Multimodal Subsystems",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubsystemIndicator(
                    title = "Audio Intelligence",
                    icon = Icons.Default.Mic,
                    isActive = state.isAudioMonitoringRunning,
                    owner = "Member 1",
                    modifier = Modifier.weight(1f)
                )
                SubsystemIndicator(
                    title = "Motion Anomaly",
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    isActive = state.isMotionMonitoringRunning,
                    owner = "Member 2",
                    modifier = Modifier.weight(1f)
                )
                SubsystemIndicator(
                    title = "Resilient Comm",
                    icon = Icons.Default.Sensors,
                    isActive = true,
                    owner = "Member 3",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Live Detection Diagnostics Section
        item {
            LiveDiagnosticsCard(state = state)
        }

        // Cross-Module Simulation & Verification Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = "Module Integration Simulation",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Simulate detection triggers to verify multimodal contracts and reactive state:",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    audioModule.emitSimulatedDistress(DistressType.SCREAM, 0.94f)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scream (M1)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    motionDetector.emitSimulatedAnomaly(0.89f, DistressType.FALL_IMPACT)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fall Impact (M2)", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val freshLoc = locationManager.getLastKnownLocation()
                                val payload = AlertPayload(
                                    incidentId = "SIM-${System.currentTimeMillis()}",
                                    distressType = DistressType.KEYWORD,
                                    confidence = 0.95f,
                                    location = freshLoc,
                                    emergencyMessage = "SIMULATION: Hotword 'Bachao' triggered emergency alert"
                                )
                                val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                                receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate Multi-Channel Alert (M3 Telegram/SMS)", fontSize = 11.sp)
                    }
                }
            }
        }

        // Delivery Audit Trail Section
        item {
            Text(
                text = "Recent Delivery Audit Trail",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        if (state.recentDeliveryReceipts.isEmpty()) {
            item {
                Text(
                    text = "No outbound alerts dispatched yet. Dispatchers standby.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        } else {
            items(state.recentDeliveryReceipts) { receipt ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Channel: ${receipt.channel.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = SafeGreen
                            )
                            Text(
                                text = "Recipient: ${receipt.recipient ?: "Emergency Broadcast"} (${receipt.latencyMs}ms)",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = receipt.status.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        // Incident History Log Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Incident History Log",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "${state.incidentHistory.size} recorded",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        if (state.incidentHistory.isEmpty()) {
            item {
                Text(
                    text = "Incident history is clean. No past incidents recorded.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        } else {
            items(state.incidentHistory) { incident ->
                IncidentHistoryCard(incident = incident)
            }
        }
    }
}

@Composable
fun LocationTelemetryCard(
    location: LocationData?,
    isGpsEnabled: Boolean,
    onRefreshLocation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = if (location != null) SafeGreen else WarningAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (location != null) "GPS: %.4f, %.4f".format(location.latitude, location.longitude)
                        else if (!isGpsEnabled) "GPS Disabled in Settings"
                        else "Acquiring GPS fix...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (location != null) "Accuracy: ±%.1fm (${location.provider})".format(location.accuracyMeters)
                        else "Location will attach to outbound alerts",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }

            IconButton(onClick = onRefreshLocation) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh GPS",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ActiveIncidentCard(
    incident: EmergencyIncident,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EmergencyDarkRed),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE INCIDENT: ${incident.incidentId}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = incident.level.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = incident.notes,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            incident.location?.let { loc ->
                Text(
                    text = "Coordinates: %.4f, %.4f (±%.1fm)".format(loc.latitude, loc.longitude, loc.accuracyMeters),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Cancel Alert / False Alarm", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun IncidentHistoryCard(incident: EmergencyIncident) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    val formattedDate = dateFormat.format(Date(incident.startTimeEpochMs))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = incident.incidentId,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = incident.level.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (incident.level) {
                        EmergencyLevel.CONFIRMED_EMERGENCY -> EmergencyLightRed
                        EmergencyLevel.CANCELLED -> WarningAmber
                        EmergencyLevel.RESOLVED -> SafeGreen
                        else -> TextSecondary
                    }
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$formattedDate • ${incident.notes}",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun StatusBanner(level: EmergencyLevel, message: String) {
    val (backgroundColor, textColor, icon) = when (level) {
        EmergencyLevel.CONFIRMED_EMERGENCY -> Triple(EmergencyDarkRed, Color.White, Icons.Default.Warning)
        EmergencyLevel.VERIFYING -> Triple(WarningAmber.copy(alpha = 0.2f), WarningAmber, Icons.Default.Warning)
        EmergencyLevel.MONITORING -> Triple(SafeGreen.copy(alpha = 0.15f), SafeGreen, Icons.Default.CheckCircle)
        EmergencyLevel.SUSPICIOUS -> Triple(WarningAmber.copy(alpha = 0.3f), WarningAmber, Icons.Default.Warning)
        else -> Triple(DarkSurfaceElevated, TextSecondary, Icons.Default.Security)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "STATUS: ${level.name}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = message,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
fun SubsystemIndicator(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    owner: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isActive) SafeGreen else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = TextPrimary
            )
            Text(
                text = owner,
                fontSize = 9.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isActive) "ACTIVE" else "STANDBY",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) SafeGreen else WarningAmber
            )
        }
    }
}

@Composable
fun LiveDiagnosticsCard(state: com.guardiangrid.core.state.EmergencyState) {
    val lastSignal = state.lastDetectedSignal
    val isSimulated = lastSignal?.metadata?.get("simulation") == "true" ||
            lastSignal?.source?.contains("Simulat", ignoreCase = true) == true ||
            lastSignal?.metadata?.containsKey("stub") == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Detection Diagnostics",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = state.emergencyLevel.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (state.emergencyLevel) {
                        EmergencyLevel.CONFIRMED_EMERGENCY -> EmergencyRed
                        EmergencyLevel.VERIFYING -> WarningAmber
                        EmergencyLevel.SUSPICIOUS -> WarningAmber
                        EmergencyLevel.MONITORING -> SafeGreen
                        else -> TextSecondary
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Subsystem Status Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticBadge(
                    label = "Audio: ${if (state.isAudioMonitoringRunning) "ACTIVE" else "STANDBY"}",
                    isActive = state.isAudioMonitoringRunning,
                    modifier = Modifier.weight(1f)
                )
                DiagnosticBadge(
                    label = "Motion: ${if (state.isMotionMonitoringRunning) "ACTIVE" else "STANDBY"}",
                    isActive = state.isMotionMonitoringRunning,
                    modifier = Modifier.weight(1f)
                )
            }

            // Verification Countdown Banner (Active when verifying)
            if (state.verificationCountdownRemainingMs > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(WarningAmber.copy(alpha = 0.15f))) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⏳ Verification Countdown Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WarningAmber
                        )
                        Text(
                            text = "${(state.verificationCountdownRemainingMs + 999) / 1000}s remaining",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Last Detected Event Info
            if (lastSignal != null) {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                val timeStr = timeFormat.format(Date(lastSignal.timestampEpochMs))
                val keyword = lastSignal.metadata["keyword"]

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Event: ${lastSignal.type.name}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (isSimulated) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "[SIMULATED]",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = WarningAmber
                                )
                            }
                        }
                        if (keyword != null) {
                            Text(
                                text = "Keyword: '$keyword'",
                                fontSize = 10.sp,
                                color = InfoBlue
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Confidence: ${(lastSignal.confidence * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lastSignal.confidence >= 0.80f) EmergencyLightRed else SafeGreen
                        )
                        Text(
                            text = "Time: $timeStr",
                            fontSize = 9.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                Text(
                    text = "No detection events recorded. Sensors in standby.",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun DiagnosticBadge(label: String, isActive: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(if (isActive) SafeGreen.copy(alpha = 0.15f) else DarkSurface), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) SafeGreen else TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 6.dp)
        )
    }
}
