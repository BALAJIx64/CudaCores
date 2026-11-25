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
 * Result of Telegram Bot API verification.
 */
data class TelegramVerificationResult(
    val isSuccess: Boolean,
    val botUsername: String? = null,
    val botId: Long? = null,
    val errorMessage: String? = null
)

/**
 * Result of a Telegram alert dispatch operation.
 */
data class TelegramDispatchResult(
    val state: EmergencyDeliveryState,
    val messageId: String? = null,
    val recipientChatId: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val auditRecord: CommunicationAuditRecord,
    val retryAfterSeconds: Int? = null
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

        /**
         * Formats standardized audio chunk captions containing Incident ID, sequence label,
         * UTC capture timestamp, and exact captured duration.
         */
        fun formatAudioChunkCaption(
            incidentId: String,
            chunkIndex: Int,
            isPreTrigger: Boolean,
            isFinal: Boolean,
            captureTimestampEpochMs: Long,
            durationMs: Long
        ): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val capturedAt = fmt.format(Date(captureTimestampEpochMs))
            val durStr = String.format(Locale.US, "%.1fs", durationMs / 1000.0)
            val seqLabel = when {
                isPreTrigger -> "Pre-Trigger (Seq 0)"
                isFinal -> "Chunk #$chunkIndex (FINAL)"
                else -> "Chunk #$chunkIndex"
            }
            return "GuardianGrid Audio [$seqLabel]\nIncident: $incidentId\nCaptured: $capturedAt\nDuration: $durStr"
        }
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
                val retryAfterSec = parseRetryAfterSeconds(response.statusCode, response.headers, response.body)
                val errorMsg = parseErrorMessageFromResponse(response.statusCode, response.body, retryAfterSec)
                val failedAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.FAILED,
                    errorMessage = errorMsg,
                    latencyMs = latency,
                    retryAfterSeconds = retryAfterSec
                )

                TelegramDispatchResult(
                    state = EmergencyDeliveryState.FAILED,
                    recipientChatId = resolvedChatId,
                    errorMessage = errorMsg,
                    latencyMs = latency,
                    auditRecord = failedAudit,
                    retryAfterSeconds = retryAfterSec
                )
            }
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - startTime
            val safeMessage = sanitizeTokenFromText(e.message ?: "Connection failed")
            val networkError = "Network error communicating with Telegram API: $safeMessage"
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
     * Dispatches an emergency WAV audio segment (pre-trigger 10s, live chunk, or full 20s file)
     * via Telegram Bot API `sendDocument` multipart upload so PCM WAV headers remain lossless.
     */
    fun dispatchAudioFile(
        incidentId: String,
        filePath: String,
        caption: String,
        targetChatId: String? = null
    ): TelegramDispatchResult {
        val startTime = System.currentTimeMillis()
        val eventId = "tg-aud-${UUID.randomUUID()}"
        val creds = credentialsProvider()

        if (creds == null || !creds.isValid()) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Telegram bot token is not configured for audio dispatch",
                latencyMs = latency
            )
            return TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = audit.errorMessage,
                latencyMs = latency,
                auditRecord = audit
            )
        }

        val resolvedChatId = targetChatId ?: creds.defaultChatId
        if (resolvedChatId.isNullOrBlank()) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "No Telegram chat ID configured for audio dispatch",
                latencyMs = latency
            )
            return TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = audit.errorMessage,
                latencyMs = latency,
                auditRecord = audit
            )
        }

        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) {
            val latency = System.currentTimeMillis() - startTime
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                recipient = RecipientAuthorizer.maskChatId(resolvedChatId),
                errorMessage = "Audio evidence file not found on disk: ${file.name}",
                latencyMs = latency
            )
            return TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                recipientChatId = resolvedChatId,
                errorMessage = audit.errorMessage,
                latencyMs = latency,
                auditRecord = audit
            )
        }

        val attemptAudit = CommunicationAuditRecord(
            recordId = UUID.randomUUID().toString(),
            incidentId = incidentId,
            eventId = eventId,
            channel = DeliveryChannel.TELEGRAM,
            state = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED,
            attemptCount = 1,
            recipient = RecipientAuthorizer.maskChatId(resolvedChatId),
            timestampEpochMs = System.currentTimeMillis()
        )

        val endpointUrl = "$TELEGRAM_API_BASE/bot${creds.botToken}/sendDocument"
        return try {
            val response = transport.postMultipartFile(
                url = endpointUrl,
                fieldName = "document",
                fileName = file.name,
                mimeType = "audio/wav",
                fileBytes = file.readBytes(),
                formFields = mapOf(
                    "chat_id" to resolvedChatId,
                    "caption" to caption.take(1024)
                )
            )
            val latency = System.currentTimeMillis() - startTime
            if (response.isSuccessful) {
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
                val retryAfterSec = parseRetryAfterSeconds(response.statusCode, response.headers, response.body)
                val errorMsg = parseErrorMessageFromResponse(response.statusCode, response.body, retryAfterSec)
                val failedAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.FAILED,
                    errorMessage = errorMsg,
                    latencyMs = latency,
                    retryAfterSeconds = retryAfterSec
                )
                TelegramDispatchResult(
                    state = EmergencyDeliveryState.FAILED,
                    recipientChatId = resolvedChatId,
                    errorMessage = errorMsg,
                    latencyMs = latency,
                    auditRecord = failedAudit,
                    retryAfterSeconds = retryAfterSec
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val safeMessage = sanitizeTokenFromText(e.message ?: "Audio upload failed")
            val networkError = "Network error uploading audio to Telegram: $safeMessage"
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
     * Dispatches a final incident-completed summary notification once continuous recording ends.
     */
    fun dispatchIncidentCompleted(
        incidentId: String,
        totalPostChunks: Int,
        totalDurationMs: Long,
        combinedFileName: String,
        targetChatId: String? = null
    ): TelegramDispatchResult {
        val startTime = System.currentTimeMillis()
        val eventId = "tg-done-${UUID.randomUUID()}"
        val creds = credentialsProvider()
        val resolvedChatId = targetChatId ?: creds?.defaultChatId
        if (creds == null || !creds.isValid() || resolvedChatId.isNullOrBlank()) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.TELEGRAM,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Telegram credentials or chat ID missing for completion notification"
            )
            return TelegramDispatchResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }

        val durStr = String.format(Locale.US, "%.1fs", totalDurationMs / 1000.0)
        val text = "✅ *GUARDIANGRID INCIDENT RECORDING COMPLETED*\n\n" +
            "*Incident ID:* `$incidentId`\n" +
            "*Pre-Trigger + Chunks:* `1 pre-trigger + $totalPostChunks chunk(s)`\n" +
            "*Total Audio Duration:* `$durStr`\n" +
            "*Complete Archive:* `$combinedFileName`"

        val sendPayload = TelegramSendMessagePayload(
            chatId = resolvedChatId,
            text = text,
            parseMode = "Markdown",
            disableWebPagePreview = true
        )
        val endpointUrl = "$TELEGRAM_API_BASE/bot${creds.botToken}/sendMessage"
        val attemptAudit = CommunicationAuditRecord(
            recordId = UUID.randomUUID().toString(),
            incidentId = incidentId,
            eventId = eventId,
            channel = DeliveryChannel.TELEGRAM,
            state = EmergencyDeliveryState.TRANSMISSION_ATTEMPTED,
            attemptCount = 1,
            recipient = RecipientAuthorizer.maskChatId(resolvedChatId)
        )

        return try {
            val response = transport.postJson(endpointUrl, sendPayload.toJson())
            val latency = System.currentTimeMillis() - startTime
            if (response.isSuccessful) {
                val msgId = parseMessageIdFromResponse(response.body)
                val okAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.DELIVERED,
                    messageId = msgId,
                    deliveredEpochMs = System.currentTimeMillis(),
                    latencyMs = latency
                )
                TelegramDispatchResult(
                    state = EmergencyDeliveryState.DELIVERED,
                    messageId = msgId,
                    recipientChatId = resolvedChatId,
                    latencyMs = latency,
                    auditRecord = okAudit
                )
            } else {
                val retryAfterSec = parseRetryAfterSeconds(response.statusCode, response.headers, response.body)
                val err = parseErrorMessageFromResponse(response.statusCode, response.body, retryAfterSec)
                val failAudit = attemptAudit.copy(
                    state = EmergencyDeliveryState.FAILED,
                    errorMessage = err,
                    latencyMs = latency,
                    retryAfterSeconds = retryAfterSec
                )
                TelegramDispatchResult(
                    state = EmergencyDeliveryState.FAILED,
                    recipientChatId = resolvedChatId,
                    errorMessage = err,
                    latencyMs = latency,
                    auditRecord = failAudit,
                    retryAfterSeconds = retryAfterSec
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val err = "Network error sending completion summary: ${sanitizeTokenFromText(e.message ?: "Unknown")}"
            val failAudit = attemptAudit.copy(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = err,
                latencyMs = latency
            )
            TelegramDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                recipientChatId = resolvedChatId,
                errorMessage = err,
                latencyMs = latency,
                auditRecord = failAudit
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
     * Actively tests and verifies a Bot Token against Telegram's getMe endpoint.
     */
    fun verifyBotToken(token: String): TelegramVerificationResult {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return TelegramVerificationResult(isSuccess = false, errorMessage = "Bot token is blank")
        }
        val creds = TelegramCredentials(trimmed)
        if (!creds.isValid()) {
            return TelegramVerificationResult(
                isSuccess = false,
                errorMessage = "Invalid bot token format. Expected: <bot_id>:<secret_hash> with at least 20 secret characters"
            )
        }

        val endpoint = "$TELEGRAM_API_BASE/bot$trimmed/getMe"
        return try {
            val response = transport.getJson(endpoint)
            if (response.isSuccessful) {
                val (botId, username) = parseBotInfoFromResponse(response.body)
                TelegramVerificationResult(
                    isSuccess = true,
                    botUsername = username,
                    botId = botId
                )
            } else {
                val errorMsg = parseErrorMessageFromResponse(response.statusCode, response.body)
                TelegramVerificationResult(
                    isSuccess = false,
                    errorMessage = "Telegram Bot API error ($errorMsg)"
                )
            }
        } catch (e: Exception) {
            TelegramVerificationResult(
                isSuccess = false,
                errorMessage = "Network error: ${sanitizeTokenFromText(e.message ?: "Failed to connect to Telegram")}"
            )
        }
    }

    /**
     * Verifies recipient chat ID syntax and reachability.
     */
    fun verifyRecipientChatId(token: String, chatId: String): TelegramVerificationResult {
        val trimmedToken = token.trim()
        val trimmedChatId = chatId.trim()
        if (trimmedChatId.isBlank()) {
            return TelegramVerificationResult(isSuccess = false, errorMessage = "Chat ID is blank")
        }
        val isNumeric = (trimmedChatId.startsWith("-") && trimmedChatId.substring(1).all { it.isDigit() }) ||
                trimmedChatId.all { it.isDigit() }
        val isChannelHandle = trimmedChatId.startsWith("@") && trimmedChatId.length >= 4

        if (!isNumeric && !isChannelHandle) {
            return TelegramVerificationResult(
                isSuccess = false,
                errorMessage = "Invalid Telegram chat ID format. Must be numeric (e.g. -100123456789) or channel handle (@channel)"
            )
        }

        val endpoint = "$TELEGRAM_API_BASE/bot$trimmedToken/getChat?chat_id=$trimmedChatId"
        return try {
            val response = transport.getJson(endpoint)
            if (response.isSuccessful) {
                TelegramVerificationResult(isSuccess = true)
            } else {
                val errorMsg = parseErrorMessageFromResponse(response.statusCode, response.body)
                TelegramVerificationResult(
                    isSuccess = false,
                    errorMessage = "Chat reachability failed ($errorMsg). Ensure bot is in this chat."
                )
            }
        } catch (e: Exception) {
            TelegramVerificationResult(
                isSuccess = false,
                errorMessage = "Network error verifying chat: ${sanitizeTokenFromText(e.message ?: "Unknown")}"
            )
        }
    }

    private fun sanitizeTokenFromText(raw: String): String {
        return raw.replace(Regex("""bot\d+:[A-Za-z0-9_-]+"""), "bot[REDACTED]")
    }

    private fun parseBotInfoFromResponse(json: String): Pair<Long?, String?> {
        val idRegex = """"id"\s*:\s*(\d+)""".toRegex()
        val usernameRegex = """"username"\s*:\s*"([^"]+)"""".toRegex()
        val id = idRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()
        val username = usernameRegex.find(json)?.groupValues?.get(1)
        return Pair(id, username)
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

    internal fun parseRetryAfterSeconds(
        statusCode: Int,
        headers: Map<String, List<String>>,
        jsonBody: String
    ): Int? {
        if (statusCode != 429) return null
        val headerRetry = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.toIntOrNull()
        if (headerRetry != null) return headerRetry
        val bodyRegex = """"retry_after"\s*:\s*(\d+)""".toRegex()
        return bodyRegex.find(jsonBody)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts error message from failed Telegram HTTP response.
     */
    internal fun parseErrorMessageFromResponse(
        statusCode: Int,
        jsonBody: String,
        retryAfterSeconds: Int? = null
    ): String {
        val regex = """"description"\s*:\s*"([^"]+)"""".toRegex()
        val desc = regex.find(jsonBody)?.groupValues?.get(1)
        return when (statusCode) {
            401 -> "Invalid Telegram bot credentials (HTTP 401 Unauthorized)"
            400 -> "Telegram API rejected request (HTTP 400 Bad Request): ${desc ?: "Invalid payload or chat ID"}"
            429 -> "Telegram API rate limit exceeded (HTTP 429 Too Many Requests, retry_after=${retryAfterSeconds ?: 5}s): ${desc ?: "Backoff required"}"
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
