package com.guardiangrid.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.anomaly.MotionThresholdConfig
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.CyberTeal
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
import java.io.File
import java.util.UUID

/**
 * Production Settings & Security Configuration Screen.
 *
 * Organized into 8 distinct sections:
 * 1. Trusted Emergency Contacts (with phone normalization & persistence)
 * 2. Telegram Bot Configuration (Android Keystore AES-256-GCM vault)
 * 3. Protection Sensitivity (Acoustic scream & motion thresholds)
 * 4. Discreet Mode (Silent stealth emergency dispatch)
 * 5. Permissions & Monitoring Requirements
 * 6. Audio Evidence & Storage Retention (WAV cleanup & privacy)
 * 7. Privacy Information (100% on-device AI processing guarantee)
 * 8. App Information & Operational Channel Disclosures (Honest SMS/BLE status)
 */
@Composable
fun SettingsScreen(
    audioModule: AudioModuleStub,
    motionDetector: MotionAnomalyDetectorImpl? = null,
    coordinator: EmergencyCoordinatorImpl? = null,
    onRequestPermissions: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToContacts: () -> Unit = {},
    onNavigateToCommunication: () -> Unit = {},
    onNavigateToDeliveryStatus: () -> Unit = {},
    onNavigateToOfflineQueue: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val state by EmergencyStateHolder.state.collectAsState()
    val scrollState = rememberScrollState()

    var screamSensitivity by remember {
        mutableFloatStateOf(container?.settingsRepository?.getSettings()?.screamSensitivity ?: 0.80f)
    }
    var motionSensitivity by remember {
        mutableFloatStateOf(container?.settingsRepository?.getSettings()?.motionSensitivity ?: 1.0f)
    }
    var countdownSeconds by remember {
        mutableIntStateOf(container?.settingsRepository?.getSettings()?.countdownDurationSeconds ?: 10)
    }
    var motionCalibrationNotice by remember { mutableStateOf("") }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showPurgePersonalDataDialog by remember { mutableStateOf(false) }

    // Telegram Bot API Configuration State
    val existingCreds = TelegramConfigManager.getCredentials()
    var botTokenInput by remember { mutableStateOf(existingCreds?.botToken ?: "") }
    var defaultChatIdInput by remember { mutableStateOf(existingCreds?.defaultChatId ?: "") }
    var isTelegramSaved by remember { mutableStateOf(TelegramConfigManager.isConfigured()) }
    var telegramStatusMessage by remember { mutableStateOf("") }

    // Audio storage statistics
    val audioDir = remember { File(context.filesDir, "audio_evidence") }
    var audioStorageSizeBytes by remember { mutableStateOf(0L) }
    var audioStorageCount by remember { mutableStateOf(0) }
    var cleanupNotice by remember { mutableStateOf("") }

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

    LaunchedEffect(Unit) {
        refreshAudioStorage()
    }

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
                    text = "SETTINGS & CONFIGURATION",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Security, Contacts & Operational Credentials",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // =====================================================================
        // COMMUNICATION & EMERGENCY PIPELINES NAVIGATION
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "EMERGENCY COMMUNICATIONS & DISPATCH",
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
                    SettingsNavCard(
                        modifier = Modifier.weight(1f),
                        title = "Contacts",
                        subtitle = "${state.trustedContacts.size} Responders",
                        icon = Icons.Default.Person,
                        accentColor = BrandMagenta,
                        onClick = onNavigateToContacts
                    )
                    SettingsNavCard(
                        modifier = Modifier.weight(1f),
                        title = "Channels",
                        subtitle = "Telegram & SMS",
                        icon = Icons.AutoMirrored.Filled.Send,
                        accentColor = InfoBlue,
                        onClick = onNavigateToCommunication
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsNavCard(
                        modifier = Modifier.weight(1f),
                        title = "Status Log",
                        subtitle = "Forensic Audit",
                        icon = Icons.Default.History,
                        accentColor = SafeGreen,
                        onClick = onNavigateToDeliveryStatus
                    )
                    SettingsNavCard(
                        modifier = Modifier.weight(1f),
                        title = "Offline Queue",
                        subtitle = "Retry Engine",
                        icon = Icons.Default.GraphicEq,
                        accentColor = CyberTeal,
                        onClick = onNavigateToOfflineQueue
                    )
                }
            }
        }

        // =====================================================================
        // SECTION 1: TRUSTED EMERGENCY CONTACTS
        // =====================================================================
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
                    Column {
                        Text(
                            text = "TRUSTED EMERGENCY CONTACTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = TextTertiary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Recipients alerted upon confirmed emergency",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    IconButton(
                        onClick = { showAddContactDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Contact",
                            tint = BrandMagenta
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val contacts = state.trustedContacts
                if (contacts.isEmpty()) {
                    Text(
                        text = "No emergency contacts configured yet. Tap '+' to register your first responder.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else {
                    contacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceElevated)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(BrandMagenta.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = BrandMagenta,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = contact.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${contact.phoneNumber} • ${contact.telegramChatId ?: "Contact"}",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    container?.settingsRepository?.deleteContact(contact.id)
                                    container?.settingsRepository?.syncToStateHolder()
                                },
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
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 2: TELEGRAM CONFIGURATION (Keystore Vault)
        // =====================================================================
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
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Telegram",
                            tint = InfoBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TELEGRAM BOT INTEGRATION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = TextTertiary,
                            letterSpacing = 1.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isTelegramSaved) SafeGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isTelegramSaved) "ENCRYPTED" else "UNCONFIGURED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTelegramSaved) SafeGreen else WarningAmber
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Encrypted with AES-256-GCM via Android Keystore. Dispatches real emergency alerts over HTTPS.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = { botTokenInput = it },
                    label = { Text("Bot API Token") },
                    placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = defaultChatIdInput,
                    onValueChange = { defaultChatIdInput = it },
                    label = { Text("Default Chat ID (Numeric)") },
                    placeholder = { Text("e.g. 987654321 or -100123456789") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                if (telegramStatusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = telegramStatusMessage,
                        fontSize = 11.sp,
                        color = if (isTelegramSaved) SafeGreen else WarningAmber
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val token = botTokenInput.trim()
                            val chatId = defaultChatIdInput.trim()
                            if (token.isNotBlank()) {
                                val creds = TelegramCredentials(token, if (chatId.isBlank()) null else chatId)
                                if (creds.isValid()) {
                                    TelegramConfigManager.setCredentials(creds)
                                    isTelegramSaved = true
                                    telegramStatusMessage = "Credentials stored in Android Keystore (${creds.maskedToken()})"
                                } else {
                                    telegramStatusMessage = "Invalid bot token format. Must be <id>:<secret>"
                                }
                            } else {
                                telegramStatusMessage = "Bot token cannot be empty."
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandMagenta),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Credentials", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    if (isTelegramSaved) {
                        OutlinedButton(
                            onClick = {
                                TelegramConfigManager.clear()
                                botTokenInput = ""
                                defaultChatIdInput = ""
                                isTelegramSaved = false
                                telegramStatusMessage = "Telegram credentials erased from vault."
                            },
                            modifier = Modifier.height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp, color = EmergencyLightRed)
                        }
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 3: PROTECTION SENSITIVITY
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DETECTION SENSITIVITY",
                    fontSize = 11.sp,
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
                    Column {
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
                    valueRange = 0.50f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandMagenta,
                        activeTrackColor = BrandMagenta,
                        inactiveTrackColor = DarkSurfaceHighlight
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Motion Sensitivity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
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
                        motionDetector?.updateConfig(MotionThresholdConfig(sensitivity = it))
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
                        motionDetector?.calibrateBaseline()
                        motionCalibrationNotice = "Baseline gravity calibrated to resting orientation."
                    },
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Calibrate Motion Baseline", fontSize = 12.sp, color = TextPrimary)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Verification Countdown Duration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
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
                        coordinator?.setVerificationTimeoutMs(secs * 1000L)
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

        // =====================================================================
        // SECTION 4: DISCREET MODE
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Discreet Stealth Mode",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Suppresses screen flashing and sirens during emergency dispatch",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = state.isDiscreetModeEnabled,
                    onCheckedChange = {
                        EmergencyStateHolder.setDiscreetModeEnabled(it)
                        container?.settingsRepository?.setDiscreetMode(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrandMagenta,
                        checkedTrackColor = BrandMagenta.copy(alpha = 0.4f)
                    )
                )
            }
        }

        // =====================================================================
        // SECTION 5: PERMISSIONS AND MONITORING REQUIREMENTS
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PERMISSIONS & MONITORING REQUIREMENTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionRow("Microphone (Acoustic YAMNet Classifier)", state.isAudioPermissionGranted, Icons.Default.Mic)
                PermissionRow("Precise Location (Emergency Coordinates)", state.isLocationPermissionGranted, Icons.Default.LocationOn)
                PermissionRow("Foreground Service Notification", state.isNotificationPermissionGranted, Icons.Default.Notifications)
                PermissionRow("Emergency SMS Fallback", state.isSmsPermissionGranted, Icons.Default.Sms)

                val allRequiredGranted = state.isAudioPermissionGranted && state.isLocationPermissionGranted && state.isNotificationPermissionGranted

                if (!allRequiredGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Grant Required Permissions", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
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
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("System Notification Settings", fontSize = 12.sp, color = TextPrimary)
                }
            }
        }

        // =====================================================================
        // SECTION 6: AUDIO EVIDENCE AND RETENTION
        // =====================================================================
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
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Storage: ${audioStorageCount} files • ${audioStorageSizeBytes / 1024} KB used in private sandbox.",
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

        // =====================================================================
        // SECTION 7: PRIVACY INFORMATION
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ON-DEVICE PRIVACY GUARANTEE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextTertiary,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "• 100% On-Device Inferences: YAMNet acoustic classification and sensor fusion run entirely local via TensorFlow Lite.\n" +
                            "• Zero Silent Audio Uploads: Normal ambient sounds are never streamed to any cloud or external server.\n" +
                            "• Keystore-Guarded Credentials: Bot tokens are sealed behind hardware-backed AES-256 encryption.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 17.sp
                )
            }
        }

        // =====================================================================
        // SECTION 8: APP INFORMATION & OPERATIONAL CHANNEL DISCLOSURES
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CHANNEL STATUS & SYSTEM INFORMATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Channel Status Rows
                ChannelStatusRow(
                    name = "Telegram Bot API",
                    status = if (isTelegramSaved) "Operational (Real HTTPS)" else "Unconfigured",
                    isOperational = isTelegramSaved,
                    icon = Icons.AutoMirrored.Filled.Send
                )

                ChannelStatusRow(
                    name = "Cellular SMS",
                    status = if (state.isSmsPermissionGranted) "Ready (Android SmsManager)" else "Permission Required",
                    isOperational = state.isSmsPermissionGranted,
                    icon = Icons.Default.Sms
                )

                ChannelStatusRow(
                    name = "BLE Mesh Relay",
                    status = "Offline Payload Foundation Ready",
                    isOperational = false,
                    icon = Icons.Default.Bluetooth
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

        Spacer(modifier = Modifier.height(36.dp))
    }

    // --- Add Contact Dialog ---
    if (showAddContactDialog) {
        var nameInput by remember { mutableStateOf("") }
        var phoneInput by remember { mutableStateOf("") }
        var relationInput by remember { mutableStateOf("") }
        var inputError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = {
                Text(text = "Add Emergency Contact", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("+1234567890") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = relationInput,
                        onValueChange = { relationInput = it },
                        label = { Text("Relationship (e.g. Spouse, Parent)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (inputError.isNotBlank()) {
                        Text(text = inputError, color = EmergencyLightRed, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = nameInput.trim()
                        val phone = phoneInput.trim()
                        val rel = relationInput.trim()
                        if (name.isBlank() || phone.isBlank()) {
                            inputError = "Name and phone number are required."
                            return@Button
                        }
                        val newContact = EmergencyContact(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            phoneNumber = phone,
                            telegramChatId = null,
                            relationship = rel.ifBlank { "Emergency Contact" }
                        )
                        val result = container?.settingsRepository?.addContact(newContact)
                        if (result?.isSuccess == true) {
                            container.settingsRepository.syncToStateHolder()
                            showAddContactDialog = false
                        } else {
                            inputError = result?.exceptionOrNull()?.message ?: "Failed to save contact."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandMagenta)
                ) {
                    Text("Save Contact", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    if (showPurgePersonalDataDialog) {
        AlertDialog(
            onDismissRequest = { showPurgePersonalDataDialog = false },
            containerColor = DarkSurfaceElevated,
            title = {
                Text("Erase Stored Evidence & History?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "This permanently deletes all saved incident records and all recorded WAV audio evidence files from device storage.",
                    color = TextSecondary,
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
private fun PermissionRow(name: String, isGranted: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) SafeGreen else TextDisabled,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = name,
                fontSize = 12.sp,
                color = if (isGranted) TextPrimary else TextSecondary
            )
        }
        Text(
            text = if (isGranted) "GRANTED" else "REQUIRED",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isGranted) SafeGreen else WarningAmber
        )
    }
}

@Composable
private fun ChannelStatusRow(name: String, status: String, isOperational: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isOperational) SafeGreen else TextDisabled,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = name,
                fontSize = 12.sp,
                color = TextPrimary
            )
        }
        Text(
            text = status,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isOperational) SafeGreen else TextDisabled
        )
    }
}

@Composable
private fun SettingsNavCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceElevated),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
