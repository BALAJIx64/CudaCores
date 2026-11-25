package com.guardiangrid.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.state.AudioEvidenceUploadProgress
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.EvidenceUploadStage
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.ui.theme.BrandMagenta
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Active Emergency Alert Screen.
 *
 * Displayed upon confirmed emergency escalation:
 * - Shows unique incident identifier & trigger details
 * - Real GPS telemetry coordinates and accuracy
 * - Physical file verification of audio evidence (never fakes availability)
 * - Actual Telegram delivery status receipts & retrying indicators
 * - Definite "Resolve Emergency" action returning system to standing protection
 */
@Composable
fun EmergencyAlertScreen(
    coordinator: EmergencyCoordinatorImpl,
    onResolved: () -> Unit = {}
) {
    val state by EmergencyStateHolder.state.collectAsState()
    val incident = state.activeIncident
    val receipts = state.recentDeliveryReceipts
    val audioSegments = incident?.audioSegments ?: emptyList()
    var showResolveConfirmDialog by remember { mutableStateOf(false) }

    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val startTimeStr = incident?.let { timeFormatter.format(Date(it.startTimeEpochMs)) } ?: "Just now"

    // Invariant: verify physical presence of audio file on disk
    val validEvidenceSegment = audioSegments.firstOrNull { segment ->
        val file = File(segment.filePath)
        file.exists() && file.length() > 0
    }

    val infiniteTransition = rememberInfiniteTransition(label = "alertPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertPulseScale"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- High-Urgency Header Banner ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = EmergencyDarkRed),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(EmergencyLightRed.copy(alpha = 0.3f))
                            .border(2.dp, EmergencyLightRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = "Emergency",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "EMERGENCY ACTIVE",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = if (incident?.isSilent == true) "DISCREET SOS DISPATCHED" else "DISTRESS SIGNALS TRANSMITTED",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f),
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = incident?.incidentId ?: "INC-ACTIVE",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // --- Dispatch Stage Stepper ---
        item {
            val anyDelivered = receipts.any { it.status == DeliveryStatus.DELIVERED }
            val anyAttempted = receipts.isNotEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlertStageItem("DETECTED", completed = true)
                Text("→", color = TextTertiary, fontSize = 11.sp)
                AlertStageItem("VERIFIED", completed = true)
                Text("→", color = TextTertiary, fontSize = 11.sp)
                AlertStageItem("DISPATCHED", completed = anyAttempted)
                Text("→", color = TextTertiary, fontSize = 11.sp)
                AlertStageItem("DELIVERED", completed = anyDelivered)
            }
        }

        // --- Incident Metadata & Location Card ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "TRIGGER TYPE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextTertiary
                            )
                            Text(
                                text = (incident?.distressType?.name ?: "MANUAL SOS").replace('_', ' '),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmergencyLightRed
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "STARTED AT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextTertiary
                            )
                            Text(
                                text = startTimeStr,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Location Info
                    val loc = incident?.location ?: state.currentLocation
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceElevated)
                            .padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = if (loc != null) SafeGreen else TextDisabled,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (loc != null) {
                                    "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
                                } else {
                                    "Location Telemetry Unavailable"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (loc != null) {
                                    "Accuracy: ±${loc.accuracyMeters.toInt()}m • Provider: ${loc.provider.uppercase()}"
                                } else {
                                    "GPS sensor active"
                                },
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    if (incident?.notes?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Notes: ${incident.notes}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // --- Forensic Audio Evidence Status (Active File Verification) ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Audio Evidence",
                                tint = if (validEvidenceSegment != null) SafeGreen else InfoBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Forensic Evidence",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (validEvidenceSegment != null) SafeGreen.copy(alpha = 0.15f) else DarkSurfaceHighlight)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (validEvidenceSegment != null) "VERIFIED ON DISK" else "RECORDING / NONE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (validEvidenceSegment != null) SafeGreen else TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (validEvidenceSegment != null) {
                            val f = File(validEvidenceSegment.filePath)
                            "Pre-Trigger + Sequential 10s WAVs (${f.length() / 1024} KB latest) stored privately on-device:\n${f.name}"
                        } else {
                            "Acoustic pre-incident buffer capturing. Stored in private app storage once stage one buffer flushes."
                        },
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )

                    val audioPipeline = state.audioUploadProgress.filter { it.incidentId == incident?.incidentId }
                    if (audioPipeline.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "AUDIO CHUNK DELIVERY STATUS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = TextTertiary,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            audioPipeline.forEach { entry ->
                                val (stageLabel, stageColor) = when (entry.stage) {
                                    EvidenceUploadStage.CAPTURED -> "Captured" to TextSecondary
                                    EvidenceUploadStage.QUEUED -> "Queued" to WarningAmber
                                    EvidenceUploadStage.UPLOADING -> "Uploading" to InfoBlue
                                    EvidenceUploadStage.DELIVERED -> "Delivered" to SafeGreen
                                    EvidenceUploadStage.FAILED -> "Failed" to EmergencyRed
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceElevated)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${entry.label} • ${"%.1f".format(Locale.US, entry.durationMs / 1000.0)}s",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        entry.detail?.let { det ->
                                            Text(text = det, fontSize = 10.sp, color = TextSecondary)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(stageColor.copy(alpha = 0.16f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = stageLabel.uppercase(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = stageColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Telegram & Outbound Dispatch Receipts ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISPATCH RECEIPTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${receipts.size} events",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        if (receipts.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Text(
                        text = "Transmitting alert payloads via Telegram Bot API...",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(receipts) { receipt ->
                val (receiptColor, receiptIcon) = when (receipt.status) {
                    DeliveryStatus.DELIVERED -> SafeGreen to Icons.Default.CheckCircle
                    DeliveryStatus.FAILED_RETRYING -> WarningAmber to Icons.Default.Warning
                    DeliveryStatus.PENDING, DeliveryStatus.IN_FLIGHT -> InfoBlue to Icons.AutoMirrored.Filled.Send
                    DeliveryStatus.ABANDONED -> EmergencyRed to Icons.Default.Warning
                }

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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(receiptColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = receiptIcon,
                                    contentDescription = receipt.channel.name,
                                    tint = receiptColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "${receipt.channel.name} • ${receipt.recipient ?: "Emergency Contact"}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = receipt.errorMessage ?: receipt.status.name,
                                    fontSize = 11.sp,
                                    color = if (receipt.status == DeliveryStatus.DELIVERED) SafeGreen else TextSecondary
                                )
                            }
                        }

                        Text(
                            text = timeFormatter.format(Date(receipt.timestampEpochMs)),
                            fontSize = 10.sp,
                            color = TextTertiary
                        )
                    }
                }
            }
        }

        // --- Resolve Emergency Action (Confirmation dialog prevents accidental dismissal) ---
        item {
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { showResolveConfirmDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Resolve Emergency",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RESOLVE EMERGENCY — SITUATION SAFE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showResolveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResolveConfirmDialog = false },
            containerColor = DarkSurfaceElevated,
            title = {
                Text("Confirm Emergency Resolved?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Mark this emergency incident as resolved and confirm you are safe. Incident evidence and delivery audit logs remain saved on-device.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResolveConfirmDialog = false
                        val wasServiceRunning = state.monitoringServiceState == MonitoringServiceState.ACTIVE
                        coordinator.resolveEmergency("Emergency situation resolved and verified safe by user")
                        if (wasServiceRunning) {
                            coordinator.resumeMonitoringAfterUserClearance()
                        }
                        onResolved()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Text("Yes, I Am Safe", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResolveConfirmDialog = false }) {
                    Text("Keep Alert Active", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun AlertStageItem(label: String, completed: Boolean) {
    Text(
        text = if (completed) "✓ $label" else label,
        fontSize = 9.sp,
        fontWeight = if (completed) FontWeight.Black else FontWeight.Medium,
        color = if (completed) SafeGreen else TextTertiary
    )
}
