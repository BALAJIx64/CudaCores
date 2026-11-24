package com.guardiangrid.ui

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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.state.DurableIncident
import com.guardiangrid.ui.brand.GuardianGridWordmark
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
 * Dedicated Incident History Screen.
 *
 * Provides a chronological audit log of all recorded emergency events:
 * - Real persistence via [IncidentRepository]
 * - Physical audio evidence verification on disk
 * - Granular incident inspection modal
 * - Safe individual and bulk deletion controls
 * - Authentic empty state (never populated with fake incidents)
 */
@Composable
fun IncidentHistoryScreen(
    onNavigateToHome: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val incidentRepo = container?.incidentRepository

    var incidents by remember { mutableStateOf<List<DurableIncident>>(emptyList()) }
    var selectedIncident by remember { mutableStateOf<DurableIncident?>(null) }
    var incidentToDelete by remember { mutableStateOf<DurableIncident?>(null) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }

    fun refreshIncidents() {
        incidents = incidentRepo?.getAllIncidents()?.sortedByDescending { it.startTimeEpochMs } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        refreshIncidents()
    }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // --- Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "INCIDENT AUDIT LOG",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceHighlight)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${incidents.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandMagenta
                        )
                    }
                }
                Text(
                    text = "Durable on-device records & forensic evidence",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            Row {
                IconButton(
                    onClick = { refreshIncidents() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (incidents.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearAllConfirmation = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear All",
                            tint = EmergencyLightRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // --- Content Area ---
        if (incidents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceHighlight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Standing By",
                                tint = SafeGreen,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Incidents Recorded",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Continuous safety monitoring logs emergency triggers, verification countdowns, and delivery receipts here.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(incidents, key = { it.incidentId }) { incident ->
                    IncidentCard(
                        incident = incident,
                        dateFormatter = dateFormatter,
                        onInspect = { selectedIncident = incident },
                        onDelete = { incidentToDelete = incident }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(64.dp))
                }
            }
        }
    }

    // --- Incident Details Modal Dialog ---
    selectedIncident?.let { incident ->
        IncidentDetailsDialog(
            incident = incident,
            dateFormatter = dateFormatter,
            onDismiss = { selectedIncident = null }
        )
    }

    // --- Delete Confirmation Dialog ---
    incidentToDelete?.let { incident ->
        AlertDialog(
            onDismissRequest = { incidentToDelete = null },
            title = {
                Text(
                    text = "Delete Incident Record?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "This will permanently remove incident ${incident.incidentId} from storage. If audio evidence exists on disk, it will also be deleted.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Delete audio file if present
                        incident.audioEvidencePath?.let { path ->
                            val f = File(path)
                            if (f.exists()) f.delete()
                        }
                        incidentRepo?.deleteIncident(incident.incidentId)
                        incidentToDelete = null
                        refreshIncidents()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { incidentToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // --- Clear All Confirmation Dialog ---
    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            title = {
                Text(
                    text = "Clear All Incident Records?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "This will permanently erase all stored incident records and their local audio evidence files. This action cannot be undone.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        incidents.forEach { inc ->
                            inc.audioEvidencePath?.let { path ->
                                val f = File(path)
                                if (f.exists()) f.delete()
                            }
                        }
                        incidentRepo?.clearAllIncidents()
                        showClearAllConfirmation = false
                        refreshIncidents()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text("Clear All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }
}

@Composable
private fun IncidentCard(
    incident: DurableIncident,
    dateFormatter: SimpleDateFormat,
    onInspect: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(incident.startTimeEpochMs) {
        dateFormatter.format(Date(incident.startTimeEpochMs))
    }

    val (triggerBadgeColor, triggerIcon) = when (incident.triggerType) {
        DistressType.MANUAL_SOS -> EmergencyRed to Icons.Default.Campaign
        DistressType.SILENT_ACTIVATION -> WarningAmber to Icons.Default.Security
        DistressType.SCREAM -> WarningAmber to Icons.Default.Mic
        DistressType.FALL_IMPACT -> BrandMagenta to Icons.Default.DirectionsRun
        DistressType.MOTION_ANOMALY -> BrandMagenta to Icons.Default.GraphicEq
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onInspect() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Trigger & ID & Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(triggerBadgeColor.copy(alpha = 0.15f))
                            .border(1.dp, triggerBadgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = triggerIcon,
                                contentDescription = null,
                                tint = triggerBadgeColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = incident.triggerType.name.replace("_", " "),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = triggerBadgeColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = incident.incidentId,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamp & Confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = TextTertiary
                )
                Text(
                    text = "Confidence: ${(incident.confidence * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status badges row: Location, Evidence, Delivery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Location badge
                val hasLocation = incident.location != null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (hasLocation) InfoBlue.copy(alpha = 0.12f) else DarkSurfaceHighlight)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (hasLocation) "GPS Linked" else "No GPS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasLocation) InfoBlue else TextDisabled
                    )
                }

                // Audio Evidence badge (checks physical file on disk)
                val hasEvidence = incident.hasValidEvidenceFile
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (hasEvidence) SafeGreen.copy(alpha = 0.12f) else DarkSurfaceHighlight)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (hasEvidence) "WAV on Disk" else "No Audio",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasEvidence) SafeGreen else TextDisabled
                    )
                }

                // Delivery Status
                val deliveryColor = when (incident.deliveryStatus) {
                    "DELIVERED" -> SafeGreen
                    "FAILED_RETRYING", "PENDING" -> WarningAmber
                    "FAILED", "ABANDONED" -> EmergencyRed
                    else -> TextSecondary
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(deliveryColor.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = incident.deliveryStatus,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = deliveryColor
                    )
                }
            }
        }
    }
}

@Composable
private fun IncidentDetailsDialog(
    incident: DurableIncident,
    dateFormatter: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    val dateStr = remember(incident.startTimeEpochMs) {
        dateFormatter.format(Date(incident.startTimeEpochMs))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = BrandMagenta,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Incident Audit Details",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("Incident ID", incident.incidentId, isMonospace = true)
                DetailRow("Timestamp", dateStr)
                DetailRow("Trigger Type", incident.triggerType.name)
                DetailRow("Confidence", "${(incident.confidence * 100).toInt()}%")
                DetailRow("Verification", incident.verificationOutcome)
                DetailRow("Delivery Status", "${incident.deliveryStatus} (${incident.deliveryAttempts} attempts)")
                DetailRow(
                    "Location",
                    incident.location?.let { "Lat: ${"%.5f".format(it.latitude)}, Lon: ${"%.5f".format(it.longitude)} (±${it.accuracyMeters.toInt()}m)" }
                        ?: "Unavailable"
                )
                DetailRow(
                    "Audio Evidence",
                    if (incident.hasValidEvidenceFile) {
                        val file = File(incident.audioEvidencePath!!)
                        "Available (${file.length() / 1024} KB)\n${file.name}"
                    } else {
                        "None / Deleted"
                    }
                )
                if (incident.notes.isNotBlank()) {
                    DetailRow("Notes", incident.notes)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = BrandMagenta, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkSurfaceElevated
    )
}

@Composable
private fun DetailRow(label: String, value: String, isMonospace: Boolean = false) {
    Column {
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextTertiary)
        Text(
            text = value,
            fontSize = 12.sp,
            color = TextPrimary,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}
