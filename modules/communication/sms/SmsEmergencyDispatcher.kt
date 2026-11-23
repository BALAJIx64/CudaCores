package com.guardiangrid.modules.communication.sms

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.security.RecipientAuthorizer
import java.util.Locale
import java.util.UUID

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
 * ## Android SMS Feasibility & Architectural Assessment:
 * 1. **Underlying Android APIs:**
 *    - Android 12+ (API 31+): `context.getSystemService(android.telephony.SmsManager::class.java)`
 *    - Android 8.0 - 11 (API 26-30): `android.telephony.SmsManager.getDefault()`
 *    - Multi-part message support: `SmsManager.divideMessage(text)` and `SmsManager.sendMultipartTextMessage(...)`
 * 2. **Required Android Permissions:**
 *    - `android.permission.SEND_SMS` (Declared in AndroidManifest.xml and requested at runtime).
 *    - `android.permission.READ_PHONE_STATE` (Optional, required if querying multiple SIM slots on dual-SIM devices).
 * 3. **Google Play Store Policy Compliance:**
 *    - `SEND_SMS` is restricted under Google Play's "Permissions and APIs that Access Sensitive Information" policy.
 *    - GuardianGrid qualifies under the **Personal Safety Application** declared exemption category.
 * 4. **Character Budget Optimization:**
 *    - Standard 7-bit GSM SMS allows up to 160 characters per single message segment.
 *    - Our compact format is strictly engineered to stay within 160 characters to avoid multipart splitting delays
 *      and ensure instantaneous single-packet carrier delivery.
 */
class SmsEmergencyDispatcher(
    private val smsSender: SmsSender = MockSmsSender()
) {

    /**
     * Dispatches SMS emergency notifications to all emergency contacts with phone numbers.
     */
    fun dispatch(payload: AlertPayload): SmsDispatchResult {
        val startTime = System.currentTimeMillis()
        val contactsWithPhones = payload.recipientContacts.filter { it.phoneNumber.isNotBlank() }

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

            val sendResult = smsSender.sendSms(contact.phoneNumber, messageText)
            val latency = System.currentTimeMillis() - startTime

            if (sendResult.isSuccess) {
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
                        deliveredEpochMs = System.currentTimeMillis(),
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
     *
     * Example:
     * `EMERGENCY: GuardianGrid SOS! Loc: https://maps.google.com/?q=12.9716,77.5946 Type: SCREAM ID: INC-101`
     */
    fun formatCompactSms(payload: AlertPayload): String {
        val locStr = if (payload.location != null && (payload.location.latitude != 0.0 || payload.location.longitude != 0.0)) {
            val lat = String.format(Locale.US, "%.5f", payload.location.latitude)
            val lon = String.format(Locale.US, "%.5f", payload.location.longitude)
            "https://maps.google.com/?q=$lat,$lon"
        } else {
            "UNAVAILABLE"
        }

        val baseText = "EMERGENCY: GuardianGrid SOS! Type: ${payload.distressType.name}. Loc: $locStr. ID: ${payload.incidentId}"
        return if (baseText.length > 160) {
            baseText.take(157) + "..."
        } else {
            baseText
        }
    }
}
