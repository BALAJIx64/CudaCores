package com.guardiangrid.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.CyberTeal
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.InfoBlue
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Delivery Status & Audit Log Screen.
 *
 * Displays:
 * - Forensic transmission logs across Telegram, SMS, and Bluetooth.
 * - Delivery status, latencies, attempt counts, and error causes.
 * - Channel-specific filtering.
 * - Aggregate delivery performance metrics.
 */
@Composable
fun DeliveryStatusScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val comm = container?.communicator as? EmergencyCommunicatorImpl
    val auditLog = comm?.auditLog ?: emptyList()

    var selectedFilter by remember { mutableStateOf<DeliveryChannel?>(null) }

    val filteredLog = remember(auditLog, selectedFilter) {
        if (selectedFilter == null) auditLog else auditLog.filter { it.channel == selectedFilter }
    }

    val totalAttempts = auditLog.size
    val deliveredCount = auditLog.count { it.state == EmergencyDeliveryState.DELIVERED || it.state == EmergencyDeliveryState.ACKNOWLEDGED }
    val failedCount = auditLog.count { it.state == EmergencyDeliveryState.FAILED }
    val avgLatency = if (auditLog.isNotEmpty()) auditLog.map { it.latencyMs }.average().toLong() else 0L

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "TRANSMISSION & DELIVERY STATUS",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Real-Time Multi-Channel Dispatch Receipts",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // --- Metric Summary Row ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(modifier = Modifier.weight(1f), label = "DISPATCHES", value = "$totalAttempts", color = TextPrimary)
            MetricCard(modifier = Modifier.weight(1f), label = "DELIVERED", value = "$deliveredCount", color = SafeGreen)
            MetricCard(modifier = Modifier.weight(1f), label = "FAILED", value = "$failedCount", color = if (failedCount > 0) EmergencyRed else TextTertiary)
            MetricCard(modifier = Modifier.weight(1f), label = "AVG LATENCY", value = "${avgLatency}ms", color = CyberTeal)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Filter Chips ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("ALL ($totalAttempts)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BrandMagenta.copy(alpha = 0.2f),
                    selectedLabelColor = BrandMagenta
                )
            )
            FilterChip(
                selected = selectedFilter == DeliveryChannel.TELEGRAM,
                onClick = { selectedFilter = DeliveryChannel.TELEGRAM },
                label = { Text("Telegram", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = InfoBlue.copy(alpha = 0.2f),
                    selectedLabelColor = InfoBlue
                )
            )
            FilterChip(
                selected = selectedFilter == DeliveryChannel.SMS,
                onClick = { selectedFilter = DeliveryChannel.SMS },
                label = { Text("SMS", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SafeGreen.copy(alpha = 0.2f),
                    selectedLabelColor = SafeGreen
                )
            )
            FilterChip(
                selected = selectedFilter == DeliveryChannel.BLUETOOTH_RELAY,
                onClick = { selectedFilter = DeliveryChannel.BLUETOOTH_RELAY },
                label = { Text("BLE", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BrandMagenta.copy(alpha = 0.2f),
                    selectedLabelColor = BrandMagenta
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // --- Receipts List ---
        if (filteredLog.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Transmission Records",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No emergency alert dispatches have been executed in this session yet.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLog.reversed(), key = { it.recordId }) { record ->
                    AuditRecordCard(record = record, dateFormat = dateFormat)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextTertiary, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
private fun AuditRecordCard(
    record: CommunicationAuditRecord,
    dateFormat: SimpleDateFormat
) {
    val timeStr = remember(record.timestampEpochMs) { dateFormat.format(Date(record.timestampEpochMs)) }

    val (channelIcon, channelColor) = when (record.channel) {
        DeliveryChannel.TELEGRAM -> Icons.Default.Send to InfoBlue
        DeliveryChannel.SMS -> Icons.Default.Sms to SafeGreen
        DeliveryChannel.INTERNET_API -> Icons.Default.Send to InfoBlue
        DeliveryChannel.BLUETOOTH_RELAY -> Icons.Default.Bluetooth to BrandMagenta
    }

    val (stateColor, stateLabel) = when (record.state) {
        EmergencyDeliveryState.DELIVERED -> SafeGreen to "DELIVERED"
        EmergencyDeliveryState.ACKNOWLEDGED -> CyberTeal to "ACKNOWLEDGED"
        EmergencyDeliveryState.TRANSMISSION_ATTEMPTED -> WarningAmber to "ATTEMPTED"
        EmergencyDeliveryState.QUEUED -> InfoBlue to "QUEUED"
        EmergencyDeliveryState.FAILED -> EmergencyRed to "FAILED"
        EmergencyDeliveryState.GENERATED -> TextSecondary to "GENERATED"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Channel & State & Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(channelColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = channelIcon, contentDescription = null, tint = channelColor, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = record.channel.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(stateColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = stateLabel, fontSize = 9.sp, fontWeight = FontWeight.Black, color = stateColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = timeStr, fontSize = 11.sp, color = TextTertiary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body Row: Incident ID & Recipient
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Incident: ${record.incidentId}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )

                if (!record.recipient.isNullOrBlank()) {
                    Text(
                        text = "To: ${record.recipient}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // Latency & Error if present
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Latency: ${record.latencyMs}ms (Attempt ${record.attemptCount})",
                    fontSize = 10.sp,
                    color = TextTertiary
                )

                if (record.isDuplicateSuppressed) {
                    Text(text = "⚡ Duplicate Suppressed", fontSize = 10.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                }
            }

            if (!record.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(EmergencyRed.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = record.errorMessage,
                        fontSize = 10.sp,
                        color = EmergencyRed,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
