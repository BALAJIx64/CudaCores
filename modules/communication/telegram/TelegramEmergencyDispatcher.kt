package com.guardiangrid.modules.communication.telegram

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.models.TelegramAcknowledgment
import com.guardiangrid.modules.communication.models.TelegramInlineKeyboardButton
import com.guardiangrid.modules.communication.models.TelegramInlineKeyboardMarkup
import com.guardiangrid.modules.communication.models.TelegramSendMessagePayload
import com.guardiangrid.modules.communication.network.DefaultHttpTransport
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.HttpTransport
import com.guardiangrid.modules.communication.security.RecipientAuthorizer
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Result of a Telegram alert dispatch operation.
 */
data class TelegramDispatchResult(
    val state: EmergencyDeliveryState,
    val messageId: String? = null,
    val recipientChatId: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val auditRecord: CommunicationAuditRecord
)

/**
 * Dispatcher handling Telegram Bot API integration for GuardianGrid.
 *
 * Capabilities:
 * 1. Formats high-priority emergency distress alerts with location links and metadata.
 * 2. Transmits alert via HTTP POST to Telegram Bot API `sendMessage`.
 * 3. Appends an inline acknowledgment button for human responders.
 * 4. Distinguishes platform acceptance (DELIVERED) from active human acknowledgment (ACKNOWLEDGED).
 * 5. Queries `getUpdates` to track human confirmation receipts.
 */
class TelegramEmergencyDispatcher(
    private val transport: HttpTransport = DefaultHttpTransport(),
    private val credentialsProvider: () -> TelegramCredentials? = { TelegramConfigManager.getCredentials() }
) {

    companion object {
        private const val TELEGRAM_API_BASE = "https://api.telegram.org"
    }

    /**
     * Dispatches an emergency alert payload to Telegram.
     *
     * @param payload The emergency alert data model.
     * @param targetChatId Optional explicit chat ID (overrides default).
     * @return Result containing delivery state, message ID, and audit record.
     */
    fun dispatch(
        payload: AlertPayload,
        targetChatId: String? = null
    ): TelegramDispatchResult {
        val startTime = System.currentTimeMillis()
        val eventId = "tg-${UUID.randomUUID()}"
        val creds = credentialsProvider()

        // Step 1: Validate credentials
        if (creds == null || !creds.isValid()) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Telegram bot token is not configured or is invalid",
                latencyMs = latency
            )
            return TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = audit.errorMessage,
                latencyMs = latency,
                auditRecord = audit
            )
        }

        // Step 2: Resolve target chat ID
        val resolvedChatId = targetChatId
            ?: creds.defaultChatId
            ?: payload.recipientContacts.firstOrNull { it.telegramChatId != null }?.telegramChatId

        if (resolvedChatId.isNullOrBlank()) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "No valid Telegram chat ID or channel specified for emergency alert",
                latencyMs = latency
            )
            return TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = audit.errorMessage,
                latencyMs = latency,
                auditRecord = audit
            )
        }

        // Step 3: Format emergency alert message text
        val messageText = formatEmergencyMessage(payload)

        // Step 4: Construct inline keyboard markup for human acknowledgment
        val replyMarkup = TelegramInlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    TelegramInlineKeyboardButton(
                        text = "✅ Acknowledge Receipt (Help on the way)",
                        callbackData = "ack:${payload.incidentId}"
                    )
                )
            )
        )

        val sendPayload = TelegramSendMessagePayload(
            chatId = resolvedChatId,
            text = messageText,
            parseMode = "Markdown",
            disableWebPagePreview = false,
            replyMarkup = replyMarkup
        )

        val endpointUrl = "$TELEGRAM_API_BASE/bot${creds.botToken}/sendMessage"

        // State: TRANSMISSION_ATTEMPTED
        val attemptAudit = CommunicationAuditRecord(
            recordId = UUID.randomUUID().toString(),
            incidentId = payload.incidentId,
            eventId = eventId,
            channel = DeliveryChannel.TELEGRAM,
            state = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED,
            attemptCount = 1,
            recipient = RecipientAuthorizer.maskChatId(resolvedChatId),
            timestampEpochMs = System.currentTimeMillis()
        )

        // Step 5: Execute HTTP POST
        return try {
            val response: HttpResponse = transport.postJson(
                url = endpointUrl,
                jsonBody = sendPayload.toJson()
            )
            val latency = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                // Parse message ID from Telegram response
                val messageId = parseMessageIdFromResponse(response.body)
                val deliveredAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.DELIVERED,
                    messageId = messageId,
                    deliveredEpochMs = System.currentTimeMillis(),
                    latencyMs = latency
                )

                TelegramDispatchResult(
                    state = EmergencyDeliveryState.DELIVERED,
                    messageId = messageId,
                    recipientChatId = resolvedChatId,
                    latencyMs = latency,
                    auditRecord = deliveredAudit
                )
            } else {
                val errorMsg = parseErrorMessageFromResponse(response.statusCode, response.body)
                val failedAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.FAILED,
                    errorMessage = errorMsg,
                    latencyMs = latency
                )

                TelegramDispatchResult(
                    state = EmergencyDeliveryState.FAILED,
                    recipientChatId = resolvedChatId,
                    errorMessage = errorMsg,
                    latencyMs = latency,
                    auditRecord = failedAudit
                )
            }
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - startTime
            val networkError = "Network error communicating with Telegram API: ${e.message ?: "Connection failed"}"
            val failedAudit = attemptAudit.copy(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = networkError,
                latencyMs = latency
            )

            TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                recipientChatId = resolvedChatId,
                errorMessage = networkError,
                latencyMs = latency,
                auditRecord = failedAudit
            )
        }
    }

    /**
     * Polls the Telegram Bot API for incoming user acknowledgments (via button callbacks or replies).
     *
     * @param incidentId Incident to match.
     * @param offset Telegram update offset.
     * @return Found acknowledgment or null if not yet acknowledged.
     */
    fun checkAcknowledgment(
        incidentId: String,
        offset: Long = 0
    ): TelegramAcknowledgment? {
        val creds = credentialsProvider() ?: return null
        if (!creds.isValid()) return null

        val endpointUrl = "$TELEGRAM_API_BASE/bot${creds.botToken}/getUpdates?offset=$offset&allowed_updates=[\"callback_query\",\"message\"]"

        return try {
            val response = transport.getJson(endpointUrl)
            if (!response.isSuccessful) return null

            parseAcknowledgmentFromUpdates(response.body, incidentId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats the Markdown text for the emergency alert.
     */
    fun formatEmergencyMessage(payload: AlertPayload): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val formattedTime = dateFormat.format(Date(payload.timestampEpochMs))
        val confidencePct = (payload.confidence * 100).toInt().coerceIn(0, 100)

        val locationText = if (payload.location != null && (payload.location.latitude != 0.0 || payload.location.longitude != 0.0)) {
            val lat = String.format(Locale.US, "%.6f", payload.location.latitude)
            val lon = String.format(Locale.US, "%.6f", payload.location.longitude)
            val mapUrl = "https://maps.google.com/?q=$lat,$lon"
            """
            *Location:*
            [Open in Google Maps]($mapUrl)
            `Coordinates:` `$lat, $lon`
            `Accuracy:` `±${payload.location.accuracyMeters}m`
            """.trimIndent()
        } else {
            """
            *Location:*
            *UNAVAILABLE* (GPS fix not acquired / indoor dead-zone)
            """.trimIndent()
        }

        val audioEvidenceText = if (!payload.audioSegmentPath.isNullOrBlank()) {
            val fileName = File(payload.audioSegmentPath).name
            "\n*Audio Evidence:*\nRecorded ($fileName)\n"
        } else {
            ""
        }

        return """
🚨 *GUARDIANGRID EMERGENCY ALERT* 🚨

*Incident ID:* `${payload.incidentId}`
*Distress Type:* *${payload.distressType.name}* (Confidence: `$confidencePct%`)
*Triggered At:* `$formattedTime`

$locationText
$audioEvidenceText
*Message:*
${payload.emergencyMessage}

⚠️ *Action Required:* Please verify victim safety or dispatch emergency response immediately. Click below to acknowledge receipt.
        """.trimIndent()
    }

    /**
     * Extracts message_id from Telegram JSON response.
     */
    internal fun parseMessageIdFromResponse(jsonBody: String): String? {
        val regex = """"message_id"\s*:\s*(\d+)""".toRegex()
        val match = regex.find(jsonBody)
        return match?.groupValues?.get(1)
    }

    /**
     * Extracts error message from failed Telegram HTTP response.
     */
    internal fun parseErrorMessageFromResponse(statusCode: Int, jsonBody: String): String {
        val regex = """"description"\s*:\s*"([^"]+)"""".toRegex()
        val desc = regex.find(jsonBody)?.groupValues?.get(1)
        return when (statusCode) {
            401 -> "Invalid Telegram bot credentials (HTTP 401 Unauthorized)"
            400 -> "Telegram API rejected request (HTTP 400 Bad Request): ${desc ?: "Invalid payload or chat ID"}"
            429 -> "Telegram API rate limit exceeded (HTTP 429 Too Many Requests): ${desc ?: "Backoff required"}"
            else -> "Telegram API error (HTTP $statusCode): ${desc ?: jsonBody.take(120)}"
        }
    }

    /**
     * Inspects getUpdates JSON for callback_query matching the incident acknowledgment.
     */
    internal fun parseAcknowledgmentFromUpdates(jsonBody: String, incidentId: String): TelegramAcknowledgment? {
        val targetCallback = "ack:$incidentId"
        if (!jsonBody.contains(targetCallback)) return null

        // Simple regex-based extraction from Telegram getUpdates JSON
        val updateIdRegex = """"update_id"\s*:\s*(\d+)""".toRegex()
        val userIdRegex = """"from"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)""".toRegex()
        val userNameRegex = """"first_name"\s*:\s*"([^"]+)"""".toRegex()

        val updateId = updateIdRegex.find(jsonBody)?.groupValues?.get(1)?.toLongOrNull() ?: 1L
        val userId = userIdRegex.find(jsonBody)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val userName = userNameRegex.find(jsonBody)?.groupValues?.get(1) ?: "Authorized Contact"

        return TelegramAcknowledgment(
            updateId = updateId,
            incidentId = incidentId,
            acknowledgedByUserId = userId,
            acknowledgedByUserName = userName,
            timestampEpochMs = System.currentTimeMillis()
        )
    }
}
