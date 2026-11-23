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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.EmergencyCommunicatorStub
import com.guardiangrid.modules.motion.MotionModuleStub
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.EmergencyDarkRed
import com.guardiangrid.ui.theme.EmergencyLightRed
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    coordinator: EmergencyCoordinator,
    audioStub: AudioModuleStub,
    motionStub: MotionModuleStub,
    commStub: EmergencyCommunicatorStub
) {
    val state by EmergencyStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title and Subheading
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = "GuardianGrid",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Multimodal Distress Detection & Resilient Alert System",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        // Live Protection Status Banner
        item {
            StatusBanner(state.emergencyLevel, state.statusMessage)
        }

        // Central Emergency SOS Activation Button
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        coordinator.triggerDiscreetSos("Dashboard_SOS_Button")
                        scope.launch {
                            val payload = AlertPayload(
                                incidentId = "MANUAL-${System.currentTimeMillis()}",
                                distressType = DistressType.MANUAL_SOS,
                                confidence = 1.0f,
                                location = state.currentLocation,
                                emergencyMessage = "MANUAL SOS: Emergency assistance urgently required!"
                            )
                            val receipts = commStub.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                            receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                        }
                    },
                    modifier = Modifier
                        .size(170.dp)
                        .clip(CircleShape)
                        .border(4.dp, EmergencyLightRed.copy(alpha = 0.6f), CircleShape),
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
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SOS",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "PRESS FOR HELP",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // Protection Activation Toggle
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (state.isProtectionActive) coordinator.stopProtection()
                        else coordinator.startProtection()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isProtectionActive) WarningAmber else SafeGreen
                    )
                ) {
                    Icon(
                        imageVector = if (state.isProtectionActive) Icons.Default.Security else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isProtectionActive) "Stop Protection" else "Activate Protection",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.emergencyLevel == EmergencyLevel.CONFIRMED_EMERGENCY || state.emergencyLevel == EmergencyLevel.VERIFYING) {
                    OutlinedButton(
                        onClick = { coordinator.cancelEmergency("User Cancelled via Dashboard") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel Alert", color = EmergencyLightRed)
                    }
                }
            }
        }

        // Subsystems Status Row
        item {
            Text(
                text = "Multimodal Subsystems",
                fontSize = 16.sp,
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

        // Four-Member Test Simulation Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = "Module Integration Simulation",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Verify cross-module contracts and reactive state handling:",
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
                                    audioStub.emitSimulatedDistress(DistressType.SCREAM, 0.94f)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Simulate Scream (M1)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    motionStub.emitSimulatedMotionAnomaly(0.89f, DistressType.FALL_IMPACT)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Simulate Fall (M2)", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val payload = AlertPayload(
                                    incidentId = "SIM-${System.currentTimeMillis()}",
                                    distressType = DistressType.KEYWORD,
                                    confidence = 0.95f,
                                    location = null,
                                    emergencyMessage = "SIMULATION: Keyword 'Bachao' triggered dispatch"
                                )
                                val receipts = commStub.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                                receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate Alert Dispatch (M3 Telegram/SMS)", fontSize = 11.sp)
                    }
                }
            }
        }

        // Recent Audit Trail / Receipts
        item {
            Text(
                text = "Recent Delivery Audit Trail",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        if (state.recentDeliveryReceipts.isEmpty()) {
            item {
                Text(
                    text = "No alerts dispatched yet. Subsystems ready.",
                    fontSize = 12.sp,
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
                        Column {
                            Text(
                                text = "Channel: ${receipt.channel.name}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SafeGreen
                            )
                            Text(
                                text = "Recipient: ${receipt.recipient ?: "Default Channel"} | ID: ${receipt.messageId?.take(16)}...",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = receipt.status.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }
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
