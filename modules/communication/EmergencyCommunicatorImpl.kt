package com.guardiangrid.modules.communication

import com.guardiangrid.core.interfaces.BluetoothRelayService
import com.guardiangrid.core.interfaces.DeliveryQueueManager
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.guardian.GuardianRelayClient
import com.guardiangrid.modules.communication.guardian.HttpGuardianRelayClient
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.DefaultHttpTransport
import com.guardiangrid.modules.communication.network.HttpTransport
import com.guardiangrid.modules.communication.queue.DeliveryQueueManagerImpl
import com.guardiangrid.modules.communication.queue.EmergencyQueueRepository
import com.guardiangrid.modules.communication.queue.IdempotencyManager
import com.guardiangrid.modules.communication.queue.InMemoryQueueRepository
import com.guardiangrid.modules.communication.relay.BluetoothRelayServiceImpl
import com.guardiangrid.modules.communication.security.RecipientAuthorizer
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.communication.sms.SmsEmergencyDispatcher
import com.guardiangrid.modules.communication.sms.SmsSender
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import com.guardiangrid.modules.communication.telegram.TelegramEmergencyDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Production-ready implementation of GuardianGrid's EmergencyCommunicator.
 *
 * Coordinates:
 * - Multi-tier resilient dispatch (Telegram + Paired Guardian Relay -> SMS Text/Location Fallback -> BLE Relay).
 * - Immediate 10s pre-trigger + chunked 10s post-trigger WAV audio relay strictly over Telegram / Guardian Relay (NEVER SMS).
 * - Offline event queueing and exponential backoff retry via DeliveryQueueManager.
 * - Idempotent delivery to prevent duplicate transmissions.
 */
class EmergencyCommunicatorImpl(
    val transport: HttpTransport = DefaultHttpTransport(),
    val smsSender: SmsSender = MockSmsSender(),
    val queueRepository: EmergencyQueueRepository = InMemoryQueueRepository(),
    private val credentialsProvider: () -> TelegramCredentials? = { TelegramConfigManager.getCredentials() },
    val idempotencyManager: IdempotencyManager = IdempotencyManager(),
    val bluetoothRelay: BluetoothRelayServiceImpl = BluetoothRelayServiceImpl(),
    val guardianConnectivityManager: GuardianConnectivityManager? = null,
    val guardianRelayClient: GuardianRelayClient? = guardianConnectivityManager?.let {
        HttpGuardianRelayClient(it, transport)
    },
    val supabaseBackendClient: SupabaseEmergencyBackendClient? = guardianConnectivityManager?.let {
        SupabaseEmergencyBackendClient(it, transport)
    },
    var onOfflineRetryScheduled: (() -> Unit)? = null
) : EmergencyCommunicator {

    val telegramDispatcher = TelegramEmergencyDispatcher(transport, credentialsProvider)
    val smsDispatcher = SmsEmergencyDispatcher(smsSender)

    val queueManager: DeliveryQueueManagerImpl = DeliveryQueueManagerImpl(
        repository = queueRepository,
        dispatcherAction = { payload, channel -> dispatchSingleChannel(payload, channel) }
    )

    private val _auditLog = Collections.synchronizedList(mutableListOf<CommunicationAuditRecord>())
    val auditLog: List<CommunicationAuditRecord> get() = _auditLog.toList()

    private val _lastDeliveryState = MutableStateFlow<EmergencyDeliveryState>(EmergencyDeliveryState.GENERATED)
    val lastDeliveryState: StateFlow<EmergencyDeliveryState> = _lastDeliveryState.asStateFlow()

    override suspend fun dispatchAlert(
        payload: AlertPayload,
        channels: List<DeliveryChannel>
    ): List<DeliveryReceipt> {
        _lastDeliveryState.value = EmergencyDeliveryState.GENERATED
        val receipts = mutableListOf<DeliveryReceipt>()

        for (channel in channels) {
            val receipt = dispatchSingleChannel(payload, channel)
            receipts.add(receipt)
        }

        return receipts
    }

    private suspend fun dispatchSingleChannel(
        payload: AlertPayload,
        channel: DeliveryChannel
    ): DeliveryReceipt {
        // Hard invariant: audio payloads MUST NEVER be transmitted via SMS
        if (payload.isAudioRelay && channel == DeliveryChannel.SMS) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = "sms-blocked-${UUID.randomUUID()}",
                channel = DeliveryChannel.SMS,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Policy block: Audio relay is prohibited over SMS (SMS carries emergency text and location only)."
            )
            recordAudit(audit)
            return DeliveryReceipt(
                channel = DeliveryChannel.SMS,
                status = DeliveryStatus.ABANDONED,
                messageId = audit.eventId,
                errorMessage = audit.errorMessage
            )
        }

        // Dispatch queued/direct audio-only segments via Telegram or Paired Guardian Relay
        if (payload.isAudioRelay) {
            return dispatchAudioPayloadSingleChannel(payload, channel)
        }

        val idempotencyKey = idempotencyManager.buildKey(payload.incidentId, channel)

        // Step 1: Idempotency Check — Prevent duplicate alert deliveries
        if (idempotencyManager.isAlreadyDelivered(idempotencyKey)) {
            val existingReceipt = idempotencyManager.getExistingReceipt(idempotencyKey)!!
            val duplicateAudit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = existingReceipt.messageId ?: "dup",
                channel = channel,
                state = EmergencyDeliveryState.DELIVERED,
                attemptCount = 0,
                errorMessage = "Duplicate alert suppressed by IdempotencyManager",
                isDuplicateSuppressed = true
            )
            recordAudit(duplicateAudit)
            return existingReceipt
        }

        // Step 2: Enforce recipient authorization & privacy
        val primaryContact = payload.recipientContacts.firstOrNull { it.isPrimary }
        val sanitizedPayload = RecipientAuthorizer.sanitizePayloadForRecipient(payload, primaryContact)

        return when (channel) {
            DeliveryChannel.TELEGRAM -> {
                _lastDeliveryState.value = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED
                val result = telegramDispatcher.dispatch(sanitizedPayload)
                recordAudit(result.auditRecord)

                if (result.state == EmergencyDeliveryState.DELIVERED) {
                    _lastDeliveryState.value = EmergencyDeliveryState.DELIVERED
                    val receipt = result.auditRecord.toDeliveryReceipt()
                    idempotencyManager.recordDelivered(idempotencyKey, receipt)
                    receipt
                } else {
                    _lastDeliveryState.value = EmergencyDeliveryState.FAILED
                    // Enqueue for offline retry upon failure
                    queueManager.enqueue(payload, DeliveryChannel.TELEGRAM)
                    onOfflineRetryScheduled?.invoke()
                    result.auditRecord.toDeliveryReceipt()
                }
            }

            DeliveryChannel.SMS -> {
                _lastDeliveryState.value = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED
                // Enforce text + location only over SMS (strip any audio attachment references)
                val smsTextOnlyPayload = sanitizedPayload.copy(audioSegmentPath = null)
                val result = smsDispatcher.dispatch(smsTextOnlyPayload)
                result.auditRecords.forEach { recordAudit(it) }

                if (result.state == EmergencyDeliveryState.DELIVERED) {
                    _lastDeliveryState.value = EmergencyDeliveryState.DELIVERED
                    val firstAudit = result.auditRecords.firstOrNull { it.state == EmergencyDeliveryState.DELIVERED }
                    val receipt = firstAudit?.toDeliveryReceipt() ?: DeliveryReceipt(
                        channel = DeliveryChannel.SMS,
                        status = DeliveryStatus.DELIVERED,
                        messageId = "sms-${UUID.randomUUID()}"
                    )
                    idempotencyManager.recordDelivered(idempotencyKey, receipt)
                    receipt
                } else {
                    _lastDeliveryState.value = EmergencyDeliveryState.FAILED
                    queueManager.enqueue(smsTextOnlyPayload, DeliveryChannel.SMS)
                    val firstAudit = result.auditRecords.firstOrNull()
                    firstAudit?.toDeliveryReceipt() ?: DeliveryReceipt(
                        channel = DeliveryChannel.SMS,
                        status = DeliveryStatus.FAILED_RETRYING,
                        errorMessage = "SMS dispatch failed"
                    )
                }
            }

            DeliveryChannel.BLUETOOTH_RELAY -> {
                _lastDeliveryState.value = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED
                bluetoothRelay.startBroadcastingRelay(sanitizedPayload)
                val bleAudit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = payload.incidentId,
                    eventId = "ble-${UUID.randomUUID()}",
                    channel = DeliveryChannel.BLUETOOTH_RELAY,
                    state = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED,
                    attemptCount = 1,
                    timestampEpochMs = System.currentTimeMillis()
                )
                recordAudit(bleAudit)
                DeliveryReceipt(
                    channel = DeliveryChannel.BLUETOOTH_RELAY,
                    status = DeliveryStatus.IN_FLIGHT,
                    messageId = bleAudit.eventId,
                    latencyMs = 15L
                )
            }

            DeliveryChannel.INTERNET_API -> {
                val sb = supabaseBackendClient
                val client = guardianRelayClient
                if ((sb == null || !sb.isConfigured()) && client == null) {
                    DeliveryReceipt(
                        channel = DeliveryChannel.INTERNET_API,
                        status = DeliveryStatus.FAILED_RETRYING,
                        errorMessage = "No Supabase or Guardian Relay client initialized"
                    )
                } else {
                    _lastDeliveryState.value = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED
                    var delivered = false
                    var msgId: String? = null
                    var recipientStr: String? = null
                    var lastErr: String? = null

                    if (sb != null && sb.isConfigured()) {
                        val sbRes = sb.dispatchAlert(sanitizedPayload)
                        recordAudit(sbRes.auditRecord)
                        if (sbRes.state == EmergencyDeliveryState.DELIVERED) {
                            delivered = true
                            msgId = sbRes.messageId
                            recipientStr = "Supabase-Backend"
                        } else {
                            lastErr = sbRes.errorMessage
                        }
                    }

                    if (client != null && guardianConnectivityManager?.getActiveGuardians()?.isNotEmpty() == true) {
                        val res = client.sendAlertEnvelope(sanitizedPayload)
                        res.auditRecords.forEach { recordAudit(it) }
                        if (res.state == EmergencyDeliveryState.DELIVERED) {
                            delivered = true
                            if (msgId == null) msgId = res.messageId
                            recipientStr = listOfNotNull(recipientStr, res.deliveredDeviceIds.joinToString(",")).joinToString(",")
                        } else if (lastErr == null) {
                            lastErr = res.errorMessage
                        }
                    }

                    if (delivered) {
                        _lastDeliveryState.value = EmergencyDeliveryState.DELIVERED
                        val receipt = DeliveryReceipt(
                            channel = DeliveryChannel.INTERNET_API,
                            status = DeliveryStatus.DELIVERED,
                            messageId = msgId,
                            recipient = recipientStr
                        )
                        idempotencyManager.recordDelivered(idempotencyKey, receipt)
                        receipt
                    } else {
                        val shouldRetry = (sb?.isConfigured() == true) ||
                            (guardianConnectivityManager?.getActiveGuardians()?.isNotEmpty() == true)
                        if (shouldRetry) {
                            queueManager.enqueue(payload, DeliveryChannel.INTERNET_API)
                            onOfflineRetryScheduled?.invoke()
                        }
                        DeliveryReceipt(
                            channel = DeliveryChannel.INTERNET_API,
                            status = DeliveryStatus.FAILED_RETRYING,
                            errorMessage = lastErr ?: "Authenticated backend alert dispatch failed"
                        )
                    }
                }
            }
        }
    }

    /**
     * Dispatches pre-trigger 10s, continuous 10s chunks, or final partial WAV recordings strictly over
     * authenticated Supabase Backend, Paired Guardian Relay ([DeliveryChannel.INTERNET_API]),
     * and/or [DeliveryChannel.TELEGRAM]. Never transmits audio via SMS.
     */
    suspend fun dispatchAudioEvidence(
        incidentId: String,
        distressType: DistressType,
        confidence: Float,
        audioFilePath: String,
        caption: String? = null,
        chunkIndex: Int,
        isPreTrigger: Boolean,
        isFinal: Boolean,
        captureTimestampEpochMs: Long = System.currentTimeMillis(),
        durationMs: Long = 10_000L
    ): List<DeliveryReceipt> {
        val hasSupabase = supabaseBackendClient?.isConfigured() == true
        val hasDirectTelegram = credentialsProvider()?.isValid() == true
        val hasGuardians = guardianConnectivityManager?.getActiveGuardians()?.isNotEmpty() == true

        val channels = buildList {
            if (hasSupabase || (guardianRelayClient != null && hasGuardians)) {
                add(DeliveryChannel.INTERNET_API)
            }
            if (hasDirectTelegram) {
                add(DeliveryChannel.TELEGRAM)
            }
        }.ifEmpty {
            // Preserve audit / offline queue visibility even if configured later
            listOf(DeliveryChannel.TELEGRAM)
        }

        val resolvedCaption = caption ?: TelegramEmergencyDispatcher.formatAudioChunkCaption(
            incidentId = incidentId,
            chunkIndex = chunkIndex,
            isPreTrigger = isPreTrigger,
            isFinal = isFinal,
            captureTimestampEpochMs = captureTimestampEpochMs,
            durationMs = durationMs
        )

        val audioPayload = AlertPayload(
            incidentId = incidentId,
            timestampEpochMs = captureTimestampEpochMs,
            distressType = distressType,
            confidence = confidence,
            emergencyMessage = resolvedCaption,
            audioSegmentPath = audioFilePath,
            isAudioRelay = true,
            audioChunkIndex = chunkIndex,
            isFinalAudioChunk = isFinal
        )
        return dispatchAlert(audioPayload, channels)
    }

    /**
     * Dispatches final incident-completed notification when continuous emergency audio recording finishes.
     */
    fun dispatchIncidentCompletedNotification(
        incidentId: String,
        totalPostChunks: Int,
        totalDurationMs: Long,
        combinedFileName: String
    ): List<DeliveryReceipt> {
        val receipts = mutableListOf<DeliveryReceipt>()
        val durStr = String.format(java.util.Locale.US, "%.1fs", totalDurationMs / 1000.0)
        val summaryText = "Incident $incidentId recording completed: 1 pre-trigger + $totalPostChunks chunk(s), total duration $durStr ($combinedFileName)."

        val sb = supabaseBackendClient
        if (sb != null && sb.isConfigured()) {
            val sbRes = sb.notifyIncidentCompleted(incidentId, totalPostChunks, totalDurationMs, summaryText)
            recordAudit(sbRes.auditRecord)
            receipts.add(sbRes.auditRecord.toDeliveryReceipt())
        }

        if (credentialsProvider()?.isValid() == true) {
            val tgRes = telegramDispatcher.dispatchIncidentCompleted(
                incidentId = incidentId,
                totalPostChunks = totalPostChunks,
                totalDurationMs = totalDurationMs,
                combinedFileName = combinedFileName
            )
            recordAudit(tgRes.auditRecord)
            receipts.add(tgRes.auditRecord.toDeliveryReceipt())
        }

        return receipts
    }

    private suspend fun dispatchAudioPayloadSingleChannel(
        payload: AlertPayload,
        channel: DeliveryChannel
    ): DeliveryReceipt {
        val audioPath = payload.audioSegmentPath ?: return DeliveryReceipt(
            channel = channel,
            status = DeliveryStatus.FAILED_RETRYING,
            errorMessage = "Missing audio file path for relay"
        )
        val chunkIdx = payload.audioChunkIndex ?: 0
        val isPre = chunkIdx == 0
        val dedupeTag = "${payload.incidentId}:aud:$chunkIdx:${File(audioPath).name}"
        val idempotencyKey = idempotencyManager.buildKey(dedupeTag, channel)
        if (idempotencyManager.isAlreadyDelivered(idempotencyKey)) {
            return idempotencyManager.getExistingReceipt(idempotencyKey)!!
        }

        return when (channel) {
            DeliveryChannel.TELEGRAM -> {
                val res = telegramDispatcher.dispatchAudioFile(
                    incidentId = payload.incidentId,
                    filePath = audioPath,
                    caption = payload.emergencyMessage
                )
                recordAudit(res.auditRecord)
                if (res.state == EmergencyDeliveryState.DELIVERED) {
                    val receipt = res.auditRecord.toDeliveryReceipt()
                    idempotencyManager.recordDelivered(idempotencyKey, receipt)
                    receipt
                } else {
                    queueManager.enqueue(payload, DeliveryChannel.TELEGRAM)
                    onOfflineRetryScheduled?.invoke()
                    res.auditRecord.toDeliveryReceipt()
                }
            }
            DeliveryChannel.INTERNET_API -> {
                val file = File(audioPath)
                if (!file.exists()) {
                    return DeliveryReceipt(
                        channel = DeliveryChannel.INTERNET_API,
                        status = DeliveryStatus.FAILED_RETRYING,
                        errorMessage = "WAV evidence file missing for backend/guardian relay"
                    )
                }

                var delivered = false
                var deliveredMsgId: String? = null
                var deliveredRecipient: String? = null
                var lastErr: String? = null

                val sb = supabaseBackendClient
                if (sb != null && sb.isConfigured()) {
                    val durationEstMs = (((file.length() - 44L).coerceAtLeast(0L) / 2L) * 1000L) / 16000L
                    val sbRes = sb.uploadAudioChunk(
                        incidentId = payload.incidentId,
                        chunkSequence = chunkIdx,
                        isPreTrigger = isPre,
                        isFinal = payload.isFinalAudioChunk,
                        durationMs = durationEstMs,
                        capturedAtEpochMs = payload.timestampEpochMs,
                        caption = payload.emergencyMessage,
                        wavFile = file
                    )
                    recordAudit(sbRes.auditRecord)
                    if (sbRes.state == EmergencyDeliveryState.DELIVERED) {
                        delivered = true
                        deliveredMsgId = sbRes.messageId
                        deliveredRecipient = "Supabase-Backend"
                    } else {
                        lastErr = sbRes.errorMessage
                    }
                }

                val client = guardianRelayClient
                if (client != null && guardianConnectivityManager?.getActiveGuardians()?.isNotEmpty() == true) {
                    val res = client.sendAudioChunk(
                        incidentId = payload.incidentId,
                        chunkIndex = chunkIdx,
                        isPreTrigger = isPre,
                        isFinal = payload.isFinalAudioChunk,
                        fileName = file.name,
                        wavBytes = file.readBytes()
                    )
                    res.auditRecords.forEach { recordAudit(it) }
                    if (res.state == EmergencyDeliveryState.DELIVERED) {
                        delivered = true
                        if (deliveredMsgId == null) deliveredMsgId = res.messageId
                        deliveredRecipient = listOfNotNull(deliveredRecipient, res.deliveredDeviceIds.joinToString(",")).joinToString(",")
                    } else if (lastErr == null) {
                        lastErr = res.errorMessage
                    }
                }

                if (delivered) {
                    val receipt = DeliveryReceipt(
                        channel = DeliveryChannel.INTERNET_API,
                        status = DeliveryStatus.DELIVERED,
                        messageId = deliveredMsgId,
                        recipient = deliveredRecipient
                    )
                    idempotencyManager.recordDelivered(idempotencyKey, receipt)
                    receipt
                } else {
                    if (sb?.isConfigured() == true || guardianConnectivityManager?.getActiveGuardians()?.isNotEmpty() == true) {
                        queueManager.enqueue(payload, DeliveryChannel.INTERNET_API)
                        onOfflineRetryScheduled?.invoke()
                    }
                    DeliveryReceipt(
                        channel = DeliveryChannel.INTERNET_API,
                        status = DeliveryStatus.FAILED_RETRYING,
                        errorMessage = lastErr ?: "Authenticated audio relay failed"
                    )
                }
            }
            else -> DeliveryReceipt(
                channel = channel,
                status = DeliveryStatus.ABANDONED,
                errorMessage = "Channel $channel does not support authenticated audio relay"
            )
        }
    }

    override suspend fun checkChannelAvailability(channel: DeliveryChannel): Boolean {
        return when (channel) {
            DeliveryChannel.TELEGRAM -> TelegramConfigManager.isConfigured() || supabaseBackendClient?.isConfigured() == true
            DeliveryChannel.SMS -> smsSender.isAvailable()
            DeliveryChannel.BLUETOOTH_RELAY -> true
            DeliveryChannel.INTERNET_API -> (guardianRelayClient?.isRelayEndpointConfigured() == true) || (supabaseBackendClient?.isConfigured() == true)
        }
    }

    /**
     * Checks Telegram Bot API for user acknowledgment of an incident.
     * When detected, transitions state to ACKNOWLEDGED and updates audit trail.
     */
    fun pollForTelegramAcknowledgment(incidentId: String): Boolean {
        val ack = telegramDispatcher.checkAcknowledgment(incidentId)
        return if (ack != null) {
            _lastDeliveryState.value = EmergencyDeliveryState.ACKNOWLEDGED
            val ackAudit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = "ack-${ack.updateId}",
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.ACKNOWLEDGED,
                attemptCount = 1,
                acknowledgedEpochMs = ack.timestampEpochMs,
                acknowledgedBy = ack.acknowledgedByUserName,
                timestampEpochMs = ack.timestampEpochMs
            )
            recordAudit(ackAudit)
            true
        } else {
            false
        }
    }

    /**
     * Simulates or registers a human acknowledgment directly (e.g. from UI, SMS reply, or peer relay).
     */
    fun recordHumanAcknowledgment(incidentId: String, channel: DeliveryChannel, contactName: String) {
        _lastDeliveryState.value = EmergencyDeliveryState.ACKNOWLEDGED
        val ackAudit = CommunicationAuditRecord(
            recordId = UUID.randomUUID().toString(),
            incidentId = incidentId,
            eventId = "ack-${UUID.randomUUID()}",
            channel = channel,
            state = EmergencyDeliveryState.ACKNOWLEDGED,
            attemptCount = 1,
            acknowledgedEpochMs = System.currentTimeMillis(),
            acknowledgedBy = contactName
        )
        recordAudit(ackAudit)
    }

    /**
     * Invoked when network connectivity is restored (e.g. from NetworkCallback).
     * Triggers immediate flush and retry of offline queued events.
     */
    suspend fun onNetworkRestored(): List<DeliveryReceipt> {
        return queueManager.retryPendingEvents()
    }

    private fun recordAudit(record: CommunicationAuditRecord) {
        _auditLog.add(record)
    }
}
