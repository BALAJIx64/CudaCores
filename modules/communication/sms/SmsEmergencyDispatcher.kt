package com.guardiangrid.modules.communication.sms

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.security.RecipientAuthorizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of SMS alert dispatch across one or more contacts.
 */
data class SmsDispatchResult(
    val state: EmergencyDeliveryState,
    val recipientsCount: Int,
    val sentRecipients: List<String>,
    val failedRecipients: List<String>,
    val latencyMs: Long,
    val auditRecords: List<CommunicationAuditRecord>
)

/**
 * Emergency SMS fallback dispatcher.
 *
 * Implements:
 * - Prioritized dispatch (Primary emergency contact first, then secondary).
 * - Compact GSM-7 compliant 160-character budget with incident ID, timestamp, distress type, and Google Maps URL.
 * - In-memory duplicate suppression cache within 60s cooldown window per contact.
 * - Detailed communication audit records with masked phone numbers.
 */
class SmsEmergencyDispatcher(
    private val smsSender: SmsSender = MockSmsSender()
) {

    // Tracks recently dispatched alert hashes to prevent duplicate transmissions
    private val recentDispatches = ConcurrentHashMap<String, Long>()
    private val duplicateCooldownMs = 60_000L

    /**
     * Dispatches SMS emergency notifications to all emergency contacts with phone numbers.
     */
    fun dispatch(payload: AlertPayload): SmsDispatchResult {
        val startTime = System.currentTimeMillis()
        val contactsWithPhones = payload.recipientContacts
            .filter { it.phoneNumber.isNotBlank() }
            .sortedByDescending { it.isPrimary }

        if (contactsWithPhones.isEmpty()) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = "sms-${UUID.randomUUID()}",
                channel = DeliveryChannel.SMS,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "No recipient contacts with configured phone numbers",
                latencyMs = latency
            )
            return SmsDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                recipientsCount = 0,
                sentRecipients = emptyList(),
                failedRecipients = emptyList(),
                latencyMs = latency,
                auditRecords = listOf(audit)
            )
        }

        val messageText = formatCompactSms(payload)
        val audits = mutableListOf<CommunicationAuditRecord>()
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (contact in contactsWithPhones) {
            val eventId = "sms-${UUID.randomUUID()}"
            val maskedPhone = RecipientAuthorizer.maskPhoneNumber(contact.phoneNumber)
            val dedupeKey = "${payload.incidentId}:${contact.phoneNumber}"

            val lastSent = recentDispatches[dedupeKey]
            val now = System.currentTimeMillis()

            if (lastSent != null && (now - lastSent) < duplicateCooldownMs) {
                // Duplicate suppressed
                val duplicateAudit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = payload.incidentId,
                    eventId = eventId,
                    channel = DeliveryChannel.SMS,
                    state = EmergencyDeliveryState.DELIVERED,
                    attemptCount = 0,
                    recipient = maskedPhone,
                    errorMessage = "Duplicate SMS suppressed within cooldown period",
                    isDuplicateSuppressed = true,
                    deliveredEpochMs = lastSent,
                    latencyMs = 0L
                )
                audits.add(duplicateAudit)
                sent.add(contact.phoneNumber)
                continue
            }

            val sendResult = smsSender.sendSms(contact.phoneNumber, messageText)
            val latency = System.currentTimeMillis() - startTime

            if (sendResult.isSuccess) {
                recentDispatches[dedupeKey] = now
                sent.add(contact.phoneNumber)
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = payload.incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.SMS,
                        state = EmergencyDeliveryState.DELIVERED,
                        attemptCount = 1,
                        recipient = maskedPhone,
                        messageId = eventId,
                        deliveredEpochMs = now,
                        latencyMs = latency
                    )
                )
            } else {
                failed.add(contact.phoneNumber)
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = payload.incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.SMS,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        recipient = maskedPhone,
                        errorMessage = sendResult.errorMessage ?: "SMS delivery failed",
                        latencyMs = latency
                    )
                )
            }
        }

        val overallState = when {
            sent.isNotEmpty() -> EmergencyDeliveryState.DELIVERED
            else -> EmergencyDeliveryState.FAILED
        }

        val totalLatency = System.currentTimeMillis() - startTime
        return SmsDispatchResult(
            state = overallState,
            recipientsCount = contactsWithPhones.size,
            sentRecipients = sent,
            failedRecipients = failed,
            latencyMs = totalLatency,
            auditRecords = audits
        )
    }

    /**
     * Formats a high-density, compact SMS text designed to fit within the 160-character GSM limit.
     * Includes incident ID, timestamp, distress type, and location.
     */
    fun formatCompactSms(payload: AlertPayload): String {
        val timeFormat = SimpleDateFormat("HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timeStr = timeFormat.format(Date(payload.timestampEpochMs))

        val locStr = if (payload.location != null && (payload.location.latitude != 0.0 || payload.location.longitude != 0.0)) {
            val lat = String.format(Locale.US, "%.5f", payload.location.latitude)
            val lon = String.format(Locale.US, "%.5f", payload.location.longitude)
            "https://maps.google.com/?q=$lat,$lon"
        } else {
            "UNAVAILABLE"
        }

        val baseText = "EMERGENCY: GuardianGrid SOS! Type: ${payload.distressType.name}. Time: $timeStr. Loc: $locStr. ID: ${payload.incidentId}"
        return if (baseText.length > 160) {
            baseText.take(157) + "..."
        } else {
            baseText
        }
    }
}
