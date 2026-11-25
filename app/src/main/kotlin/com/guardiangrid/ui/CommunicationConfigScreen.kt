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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.communication.network.DefaultHttpTransport
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.telegram.TelegramEmergencyDispatcher
import com.guardiangrid.ui.permissions.PermissionManager
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.BrandPurple
import com.guardiangrid.ui.theme.CyberTeal
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import androidx.compose.ui.graphics.SolidColor
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

/**
 * Dedicated Emergency Communication Channels Configuration Screen.
 *
 * Implements:
 * - Telegram Bot API credential management anchored to hardware AndroidKeyStore (AES-256-GCM).
 * - Real-time Bot Token test verification against Telegram getMe API.
 * - Cellular SMS Fallback setup, permission diagnostics, and character budget explanation.
 * - Visual multi-channel dispatch priority chain.
 */
@Composable
fun CommunicationConfigScreen(
    onRequestPermissions: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val state by EmergencyStateHolder.state.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val existingCreds = TelegramConfigManager.getCredentials()
    var botTokenInput by remember { mutableStateOf(existingCreds?.botToken ?: "") }
    var defaultChatIdInput by remember { mutableStateOf(existingCreds?.defaultChatId ?: "") }
    var isTokenVisible by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var isConnectionTestSuccess by remember { mutableStateOf<Boolean?>(null) }
    var saveStatusMessage by remember { mutableStateOf("") }

    val isSmsGranted = state.isSmsPermissionGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    text = "COMMUNICATION CHANNELS",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Telegram, SMS Fallback & Dispatch Priority",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // =====================================================================
        // SECTION 1: DISPATCH PRIORITY PIPELINE
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "DISPATCH ESCALATION HIERARCHY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PriorityPill(step = "1", name = "Telegram Bot", status = if (TelegramConfigManager.isConfigured()) "CONFIGURED" else "PENDING", isConfigured = TelegramConfigManager.isConfigured())
                    Text(text = "→", color = TextTertiary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    PriorityPill(step = "2", name = "Cellular SMS", status = if (isSmsGranted) "READY" else "NEED PERM", isConfigured = isSmsGranted)
                    Text(text = "→", color = TextTertiary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    PriorityPill(step = "3", name = "BLE Relay", status = "STANDBY", isConfigured = true)
                }
            }
        }

        // =====================================================================
        // SECTION 2: TELEGRAM BOT CONFIGURATION (ENCRYPTED KEYSTORE)
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(InfoBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = "TELEGRAM BOT API", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(text = "High-priority instant emergency dispatches", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkSurfaceElevated)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "AES-256-GCM VAULT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SafeGreen)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bot Token Input
                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = {
                        botTokenInput = it
                        connectionTestResult = null
                    },
                    label = { Text("Bot API Token") },
                    placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ", color = TextDisabled) },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle token visibility",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Default Chat ID Input
                OutlinedTextField(
                    value = defaultChatIdInput,
                    onValueChange = {
                        defaultChatIdInput = it
                        connectionTestResult = null
                    },
                    label = { Text("Default Responder Chat ID / Channel") },
                    placeholder = { Text("-100123456789 or @channel", color = TextDisabled) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                // Connection Test Feedback
                if (connectionTestResult != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isConnectionTestSuccess == true) SafeGreen.copy(alpha = 0.15f) else EmergencyRed.copy(alpha = 0.15f))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isConnectionTestSuccess == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isConnectionTestSuccess == true) SafeGreen else EmergencyRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = connectionTestResult!!,
                                fontSize = 11.sp,
                                color = if (isConnectionTestSuccess == true) SafeGreen else EmergencyRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (saveStatusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = saveStatusMessage, fontSize = 11.sp, color = CyberTeal, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Actions: Test Connection & Save
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (botTokenInput.isBlank()) {
                                connectionTestResult = "Enter a Bot Token first"
                                isConnectionTestSuccess = false
                                return@OutlinedButton
                            }
                            isTestingConnection = true
                            connectionTestResult = null
                            coroutineScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    val dispatcher = TelegramEmergencyDispatcher(DefaultHttpTransport())
                                    dispatcher.verifyBotToken(botTokenInput)
                                }
                                isTestingConnection = false
                                if (result.isSuccess) {
                                    isConnectionTestSuccess = true
                                    connectionTestResult = "✓ Connected to Telegram Bot: @${result.botUsername ?: "verified"} (ID: ${result.botId ?: "ok"})"
                                } else {
                                    isConnectionTestSuccess = false
                                    connectionTestResult = result.errorMessage ?: "Verification failed"
                                }
                            }
                        },
                        enabled = !isTestingConnection,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BrandMagenta, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Bot", fontSize = 12.sp)
                        }
                    }

                    Button(
                        onClick = {
                            if (botTokenInput.isBlank()) {
                                saveStatusMessage = "Cannot save empty token"
                                return@Button
                            }
                            val creds = TelegramCredentials(
                                botToken = botTokenInput.trim(),
                                defaultChatId = defaultChatIdInput.trim().ifBlank { null }
                            )
                            if (!creds.isValid()) {
                                saveStatusMessage = "Invalid token format (<id>:<secret>)"
                                return@Button
                            }
                            TelegramConfigManager.setCredentials(creds)
                            saveStatusMessage = "✓ Credentials securely saved in Keystore Vault"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Vault", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (TelegramConfigManager.isConfigured()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            TelegramConfigManager.clear()
                            botTokenInput = ""
                            defaultChatIdInput = ""
                            connectionTestResult = null
                            saveStatusMessage = "Credentials cleared from Keystore"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
                    ) {
                        Text("Clear Stored Credentials", fontSize = 11.sp)
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 3: CELLULAR SMS FALLBACK CONFIGURATION
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SafeGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Sms, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = "CELLULAR SMS FALLBACK", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(text = "Zero-data carrier transmission", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSmsGranted) SafeGreen.copy(alpha = 0.2f) else WarningAmber.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isSmsGranted) "PERM GRANTED" else "PERMISSION REQUIRED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSmsGranted) SafeGreen else WarningAmber
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "When cellular data or Wi-Fi is unavailable, GuardianGrid falls back to direct carrier SMS. Dispatches GPS coordinates, incident timestamp, and distress cause to your emergency contacts.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .padding(10.dp)
                ) {
                    Column {
                        Text(text = "GSM-7 Character Budget: Strict 160 chars", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Sample payload:\nEMERGENCY: GuardianGrid SOS! Type: SCREAM. Time: 14:32:05Z. Loc: https://maps.google.com/?q=12.9716,77.5946. ID: INC-101",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary
                        )
                    }
                }

                if (!isSmsGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Grant SEND_SMS Permission", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 4: BLUETOOTH RELAY
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(BrandMagenta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, tint = BrandMagenta, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = "BLUETOOTH STORE & FORWARD", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Local offline mesh relay foundation", fontSize = 11.sp, color = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "If cellular and internet networks are both severed, GuardianGrid caches encrypted distress packets and broadcasts BLE advertising signals to relay emergency beacons to nearby participating GuardianGrid nodes.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun PriorityPill(
    step: String,
    name: String,
    status: String,
    isConfigured: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isConfigured) BrandMagenta else DarkSurfaceHighlight),
            contentAlignment = Alignment.Center
        ) {
            Text(text = step, color = if (isConfigured) Color.White else TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(text = status, fontSize = 9.sp, color = if (isConfigured) CyberTeal else TextTertiary)
    }
}
