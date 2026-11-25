package com.guardiangrid.ui.guardian

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.guardiangrid.modules.communication.guardian.PairingRole
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
import com.guardiangrid.ui.theme.TextDisabled
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
 * Login-free Host-to-Guardian Device Pairing, Relay Endpoint Configuration,
 * and Guardian Emergency Notification + Audio Receiving Flow.
 */
@Composable
fun GuardianPairingScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val connectivityManager = container?.guardianConnectivityManager
    val relayClient = container?.guardianRelayClient

    val guardianState = connectivityManager?.state?.collectAsState()?.value
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val audioPlayer = remember { WavAudioPlayer() }
    val playingClipId by audioPlayer.playingClipId.collectAsState()
    val playbackError by audioPlayer.playbackError.collectAsState()

    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Pairing & Setup, 1 = Guardian Receiver
    var deviceNameInput by remember(guardianState?.identity?.deviceName) {
        mutableStateOf(guardianState?.identity?.deviceName ?: "My Device")
    }
    var relayUrlInput by remember(guardianState?.relayServerUrl) {
        mutableStateOf(guardianState?.relayServerUrl ?: "")
    }
    var inviteInput by remember { mutableStateOf("") }
    var targetRoleIsGuardian by remember { mutableStateOf(false) } // false = pair scanned HOST, true = approve GUARDIAN
    var statusNotice by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    // Optional external QR scanner activity integration if installed on device
    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")
                ?: result.data?.dataString
            if (!scanned.isNullOrBlank()) {
                inviteInput = scanned
            }
        }
    }

    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
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
                    text = "GUARDIAN CONNECTIVITY",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Login-Free Device Pairing, Location & Live Audio Relay",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // --- Mode Tabs ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == 0) BrandMagenta else DarkSurfaceElevated
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pairing & Setup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == 1) BrandPurple else DarkSurfaceElevated
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val rxCount = guardianState?.receivedAlerts?.size ?: 0
                Text("Audio Receiver ($rxCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (!statusNotice.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(CyberTeal))
            ) {
                Text(
                    text = statusNotice!!,
                    color = CyberTeal,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (selectedTab == 0 && guardianState != null) {
            // =================================================================
            // TAB 0: DEVICE IDENTITY + QR INVITE + PAIR + RELAY CONFIG
            // =================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CRYPTOGRAPHIC DEVICE IDENTITY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = TextTertiary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = guardianState.identity.deviceId,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyberTeal
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SafeGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("NO LOGIN REQUIRED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SafeGreen)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = deviceNameInput,
                            onValueChange = { deviceNameInput = it },
                            label = { Text("Device Display Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = BrandMagenta,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                        OutlinedButton(
                            onClick = {
                                connectivityManager.setDeviceDisplayName(deviceNameInput)
                                statusNotice = "Device identity name updated."
                            }
                        ) {
                            Text("Rename", color = TextPrimary, fontSize = 11.sp)
                        }
                    }
                }
            }

            // --- Host QR Code Invitation Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(BrandMagenta.copy(alpha = 0.4f)))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "SHARE PAIRING INVITATION / QR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = BrandMagenta,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "Have your trusted Guardian scan this QR code or open the signed pairing link to authenticate without an account.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    QrCodeImage(
                        content = guardianState.activeInvitationUri,
                        modifier = Modifier.size(210.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager?.setPrimaryClip(
                                    ClipData.newPlainText("GuardianGrid Pairing Invite", guardianState.activeInvitationUri)
                                )
                                statusNotice = "Signed pairing invitation URI copied to clipboard."
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextPrimary)
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "GuardianGrid Device Pairing Invite")
                                    putExtra(Intent.EXTRA_TEXT, guardianState.activeInvitationUri)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share GuardianGrid Pairing Invite"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = InfoBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Share", fontSize = 11.sp, color = InfoBlue)
                        }

                        OutlinedButton(
                            onClick = {
                                connectivityManager.regeneratePairingInvitation()
                                statusNotice = "Generated fresh cryptographic pairing secret & QR."
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyberTeal)
                            Spacer(Modifier.width(4.dp))
                            Text("Rotate", fontSize = 11.sp, color = CyberTeal)
                        }
                    }
                }
            }

            // --- Accept Peer Invitation Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "PAIR WITH ANOTHER DEVICE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { targetRoleIsGuardian = false },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (!targetRoleIsGuardian) CyberTeal else DarkBorder
                            )
                        ) {
                            Text(
                                "I am Guardian (Watch Host)",
                                fontSize = 10.sp,
                                color = if (!targetRoleIsGuardian) CyberTeal else TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { targetRoleIsGuardian = true },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (targetRoleIsGuardian) BrandMagenta else DarkBorder
                            )
                        ) {
                            Text(
                                "Add as My Guardian",
                                fontSize = 10.sp,
                                color = if (targetRoleIsGuardian) BrandMagenta else TextSecondary
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it },
                        label = { Text("Paste guardiangrid://pair?... URI") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BrandMagenta,
                            unfocusedBorderColor = DarkBorder
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val scanIntent = Intent("com.google.zxing.client.android.SCAN")
                                try {
                                    qrScannerLauncher.launch(scanIntent)
                                } catch (_: Exception) {
                                    val clip = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!clip.isNullOrBlank()) {
                                        inviteInput = clip
                                        statusNotice = "No external QR app installed; pasted URI from clipboard."
                                    } else {
                                        statusNotice = "Paste a guardiangrid://pair?... URI from your Host/Guardian device."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan / Paste", fontSize = 11.sp, color = TextPrimary)
                        }

                        Button(
                            onClick = {
                                val role = if (targetRoleIsGuardian) PairingRole.GUARDIAN else PairingRole.HOST
                                val res = connectivityManager.acceptPairingInvitation(inviteInput, role)
                                if (res.isSuccess) {
                                    val peer = res.getOrThrow()
                                    statusNotice = "Paired ${peer.displayName} (${peer.deviceId}) as ${peer.role.name}."
                                    inviteInput = ""
                                } else {
                                    statusNotice = "Pairing rejected: ${res.exceptionOrNull()?.message}"
                                }
                            },
                            enabled = inviteInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                        ) {
                            Text("Pair Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // --- Paired Devices List ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "PAIRED DEVICES (${guardianState.pairedDevices.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )
                    if (guardianState.pairedDevices.isEmpty()) {
                        Text(
                            text = "No paired devices yet. Scan or paste a device's pairing invitation above.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        guardianState.pairedDevices.forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurfaceElevated)
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = null,
                                        tint = if (peer.role == PairingRole.GUARDIAN) BrandMagenta else CyberTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "${peer.displayName} [${peer.role.name}]",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = peer.deviceId,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        connectivityManager.removePairedDevice(peer.deviceId, peer.role)
                                        statusNotice = "Unpaired ${peer.displayName}."
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Unpair", tint = TextTertiary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // --- Relay Server Endpoint (Minimum External Infrastructure) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GUARDIAN RELAY TRANSPORT CONFIG",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (guardianState.relayServerUrl.isBlank()) {
                            "Minimum External Config Needed: Configure an HTTPS relay endpoint (/v1/alerts, /v1/audio, /v1/inbox/{id}, /v1/ack). While blank, Telegram handles authenticated audio/alert delivery."
                        } else {
                            "Connected Relay Endpoint: ${guardianState.relayServerUrl}"
                        },
                        fontSize = 11.sp,
                        color = if (guardianState.relayServerUrl.isBlank()) WarningAmber else SafeGreen
                    )
                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it },
                        label = { Text("Relay Server URL (HTTPS)") },
                        placeholder = { Text("https://relay.yourdomain.com", color = TextDisabled) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BrandMagenta,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Button(
                        onClick = {
                            val r = connectivityManager.setRelayServerUrl(relayUrlInput)
                            statusNotice = if (r.isSuccess) {
                                if (relayUrlInput.isBlank()) "Relay URL cleared." else "Relay URL saved: ${r.getOrThrow()}"
                            } else {
                                r.exceptionOrNull()?.message
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceHighlight)
                    ) {
                        Text("Save Relay URL", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (selectedTab == 1 && guardianState != null) {
            // =================================================================
            // TAB 1: GUARDIAN RECEIVER (INCOMING ALERTS, LOCATION & AUDIO)
            // =================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "INCOMING EMERGENCY STREAM",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = TextTertiary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (guardianState.pairedHosts.isEmpty()) {
                                    "Pair with a Host device to receive emergency alerts & audio"
                                } else {
                                    "Watching ${guardianState.pairedHosts.size} paired Host device(s)"
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Button(
                            onClick = {
                                if (relayClient == null || isSyncing) return@Button
                                isSyncing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        relayClient.pollIncomingAlerts()
                                    }
                                    isSyncing = false
                                    statusNotice = if (result.isSuccess) {
                                        val count = result.getOrThrow().size
                                        "Synced relay inbox ($count alert update(s))."
                                    } else {
                                        result.exceptionOrNull()?.message ?: "Sync failed"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Sync Alerts", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!playbackError.isNullOrBlank()) {
                        Text(text = playbackError!!, color = EmergencyRed, fontSize = 11.sp)
                    }
                }
            }

            if (guardianState.receivedAlerts.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(40.dp))
                        Text("No Incoming Alerts", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = "When a paired Host triggers SOS, scream, or motion emergency, their GPS location, 10s pre-trigger WAV, live 2s audio chunks, and 20s recording arrive here.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                guardianState.receivedAlerts.forEach { alert ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = SolidColor(if (alert.isAcknowledged) SafeGreen else EmergencyRed)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (alert.isAcknowledged) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (alert.isAcknowledged) SafeGreen else EmergencyRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = "${alert.hostDeviceName} • ${alert.distressType}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${alert.incidentId} • ${fmt.format(Date(alert.receivedAtEpochMs))}",
                                            fontSize = 10.sp,
                                            color = TextTertiary
                                        )
                                    }
                                }
                                Text(
                                    text = if (alert.isAcknowledged) "ACK SENT" else "${(alert.confidence * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (alert.isAcknowledged) SafeGreen else WarningAmber
                                )
                            }

                            Text(text = alert.emergencyMessage, fontSize = 12.sp, color = TextSecondary)

                            if (alert.latitude != null && alert.longitude != null) {
                                OutlinedButton(
                                    onClick = {
                                        val uri = Uri.parse("https://maps.google.com/?q=${alert.latitude},${alert.longitude}")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Open GPS Fix (${"%.5f".format(Locale.US, alert.latitude)}, ${"%.5f".format(Locale.US, alert.longitude)})",
                                        fontSize = 11.sp,
                                        color = InfoBlue
                                    )
                                }
                            }

                            if (alert.audioClips.isNotEmpty()) {
                                Text(
                                    text = "RECEIVED AUDIO EVIDENCE (${alert.audioClips.size} CLIPS)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = CyberTeal
                                )
                                alert.audioClips.forEach { clip ->
                                    val isPlaying = playingClipId == clip.clipId
                                    val label = when {
                                        clip.isPreTrigger -> "Pre-Trigger 10s Audio"
                                        clip.isFinal -> "Live Audio Chunk #${clip.chunkIndex} (Final)"
                                        clip.chunkIndex == 99 -> "Complete 20s Incident WAV"
                                        else -> "Live Audio Chunk #${clip.chunkIndex}"
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
                                            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text(
                                                text = "${clip.durationMs / 1000.0}s • ${clip.byteSize / 1024} KB",
                                                fontSize = 10.sp,
                                                color = TextSecondary
                                            )
                                        }
                                        IconButton(
                                            onClick = { audioPlayer.togglePlay(clip.clipId, clip.localFilePath) },
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(if (isPlaying) EmergencyRed.copy(alpha = 0.2f) else BrandMagenta.copy(alpha = 0.2f))
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Stop Audio" else "Play Audio",
                                                tint = if (isPlaying) EmergencyRed else BrandMagenta
                                            )
                                        }
                                    }
                                }
                            }

                            if (!alert.isAcknowledged) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val ackRes = withContext(Dispatchers.IO) {
                                                relayClient?.sendAcknowledgment(alert.hostDeviceId, alert.incidentId)
                                            }
                                            statusNotice = ackRes?.exceptionOrNull()?.message ?: "Emergency acknowledged to ${alert.hostDeviceName}."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                                ) {
                                    Text("Acknowledge Emergency Receipt", color = DarkBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
