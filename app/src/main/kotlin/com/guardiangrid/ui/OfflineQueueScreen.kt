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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.QueuedEmergencyEvent
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.BrandPurple
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Offline Emergency Delivery Queue Screen.
 *
 * Implements:
 * - Real-time observation of persistent offline queue.
 * - Manual and automatic retry orchestration.
 * - Queue inspection: Pending, Retrying, Delivered, and Abandoned alerts.
 * - Individual event deletion and safe queue clearing.
 */
@Composable
fun OfflineQueueScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val comm = container?.communicator as? EmergencyCommunicatorImpl
    val coroutineScope = rememberCoroutineScope()

    val queuedEvents by (comm?.queueManager?.observeQueue()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) })

    var isRetrying by remember { mutableStateOf(false) }
    var retryResultFeedback by remember { mutableStateOf<String?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val pendingCount = queuedEvents.count { it.status == DeliveryStatus.PENDING }
    val retryingCount = queuedEvents.count { it.status == DeliveryStatus.FAILED_RETRYING }
    val deliveredCount = queuedEvents.count { it.status == DeliveryStatus.DELIVERED }
    val abandonedCount = queuedEvents.count { it.status == DeliveryStatus.ABANDONED }

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
                    text = "OFFLINE EMERGENCY QUEUE",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Persistent Queue & WorkManager Retry Engine",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // --- Queue Summary Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "QUEUE STATUS & WORKMANAGER RETRY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QueueStatusPill(modifier = Modifier.weight(1f), label = "PENDING", count = "$pendingCount", color = if (pendingCount > 0) WarningAmber else TextTertiary)
                    QueueStatusPill(modifier = Modifier.weight(1f), label = "RETRYING", count = "$retryingCount", color = if (retryingCount > 0) InfoBlue else TextTertiary)
                    QueueStatusPill(modifier = Modifier.weight(1f), label = "DELIVERED", count = "$deliveredCount", color = SafeGreen)
                    QueueStatusPill(modifier = Modifier.weight(1f), label = "ABANDONED", count = "$abandonedCount", color = if (abandonedCount > 0) EmergencyRed else TextTertiary)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions: Retry All & Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isRetrying = true
                            retryResultFeedback = null
                            coroutineScope.launch {
                                val receipts = withContext(Dispatchers.IO) {
                                    comm?.queueManager?.retryPendingEvents() ?: emptyList()
                                }
                                isRetrying = false
                                val delivered = receipts.count { it.status == DeliveryStatus.DELIVERED }
                                retryResultFeedback = "Processed ${receipts.size} events ($delivered delivered)"
                            }
                        },
                        enabled = !isRetrying && (pendingCount > 0 || retryingCount > 0),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isRetrying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Pending Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (queuedEvents.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.weight(0.6f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
                        ) {
                            Text("Clear Queue", fontSize = 11.sp)
                        }
                    }
                }

                if (retryResultFeedback != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = retryResultFeedback!!, fontSize = 11.sp, color = CyberTeal, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- Queued Events List ---
        if (queuedEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = SafeGreen,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Offline Queue is Empty",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All emergency dispatches have been transmitted or no alerts are queued for offline retry.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(queuedEvents.reversed(), key = { it.queueId }) { event ->
                    QueuedEventCard(
                        event = event,
                        dateFormat = dateFormat,
                        onDelete = {
                            coroutineScope.launch {
                                comm?.queueManager?.deleteQueuedItem(event.queueId)
                            }
                        }
                    )
                }
            }
        }
    }

    // --- Clear Queue Confirmation Dialog ---
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = DarkSurfaceElevated,
            title = {
                Text(text = "Clear Offline Queue?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Are you sure you want to remove all ${queuedEvents.size} queued emergency events from persistent storage? Pending dispatches will be lost.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            comm?.queueManager?.clearQueue()
                        }
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text(text = "Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(text = "Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun QueueStatusPill(
    modifier: Modifier = Modifier,
    label: String,
    count: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextTertiary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = count, fontSize = 14.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
private fun QueuedEventCard(
    event: QueuedEmergencyEvent,
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit
) {
    val createdTime = remember(event.createdAtEpochMs) { dateFormat.format(Date(event.createdAtEpochMs)) }

    val (channelIcon, channelColor) = when (event.targetChannel) {
        DeliveryChannel.TELEGRAM -> Icons.Default.Send to InfoBlue
        DeliveryChannel.SMS -> Icons.Default.Sms to SafeGreen
        DeliveryChannel.INTERNET_API -> Icons.Default.Send to InfoBlue
        DeliveryChannel.BLUETOOTH_RELAY -> Icons.Default.Refresh to BrandMagenta
    }

    val (statusColor, statusLabel) = when (event.status) {
        DeliveryStatus.PENDING -> WarningAmber to "PENDING"
        DeliveryStatus.FAILED_RETRYING -> InfoBlue to "RETRYING"
        DeliveryStatus.DELIVERED -> SafeGreen to "DELIVERED"
        DeliveryStatus.ABANDONED -> EmergencyRed to "ABANDONED"
        DeliveryStatus.IN_FLIGHT -> WarningAmber to "IN_FLIGHT"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Channel & Status & Time
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
                    Text(text = event.targetChannel.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = statusLabel, fontSize = 9.sp, fontWeight = FontWeight.Black, color = statusColor)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body Row: Incident ID & Retry info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Incident: ${event.payload.incidentId}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                Text(
                    text = "Created: $createdTime",
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Retry Count: ${event.retryCount} / 5",
                    fontSize = 10.sp,
                    color = CyberTeal,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Type: ${event.payload.distressType.name}",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
