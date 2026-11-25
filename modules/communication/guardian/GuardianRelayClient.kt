package com.guardiangrid.modules.communication.guardian

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.DefaultHttpTransport
import com.guardiangrid.modules.communication.network.HttpTransport
import java.io.File
import java.util.UUID

/**
 * Result of dispatching an emergency notification or audio chunk to paired Guardian devices.
 */
data class GuardianRelayDispatchResult(
    val state: EmergencyDeliveryState,
    val messageId: String? = null,
    val deliveredDeviceIds: List<String> = emptyList(),
    val failedDeviceIds: List<String> = emptyList(),
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val auditRecords: List<CommunicationAuditRecord>
)

/**
 * Concrete client contract for authenticated Host <-> Guardian emergency & audio relay.
 * Requires no traditional account login: authentication relies on paired device ID + HMAC-SHA256 signature.
 */
interface GuardianRelayClient {
    fun isRelayEndpointConfigured(): Boolean
    fun sendAlertEnvelope(payload: AlertPayload): GuardianRelayDispatchResult
    fun sendAudioChunk(
        incidentId: String,
        chunkIndex: Int,
        isPreTrigger: Boolean,
        isFinal: Boolean,
        fileName: String,
        wavBytes: ByteArray
    ): GuardianRelayDispatchResult

    fun pollIncomingAlerts(): Result<List<ReceivedGuardianAlert>>
    fun sendAcknowledgment(targetHostDeviceId: String, incidentId: String): Result<Unit>
}

/**
 * Production HTTP client implementing authenticated GuardianGrid Host-to-Guardian emergency alert
 * and chunked WAV audio relay over HTTPS.
 *
 * Minimum External Infrastructure Required:
 * An HTTPS relay endpoint configured in `relayServerUrl` exposing:
 * - `POST /v1/alerts` (JSON envelope + HMAC signature)
 * - `POST /v1/audio` (multipart/form-data `.wav` chunk + HMAC signature)
 * - `GET  /v1/inbox/{deviceId}` (pending notifications & audio URLs for paired Guardian)
 * - `POST /v1/ack` (Guardian responder acknowledgement)
 */
class HttpGuardianRelayClient(
    private val connectivityManager: GuardianConnectivityManager,
    private val transport: HttpTransport = DefaultHttpTransport()
) : GuardianRelayClient {

    override fun isRelayEndpointConfigured(): Boolean {
        val defaultUrl = connectivityManager.getRelayServerUrl()
        if (defaultUrl.isNotBlank()) return true
        return connectivityManager.getActiveGuardians().any { !it.relayEndpointUrl.isNullOrBlank() }
    }

    override fun sendAlertEnvelope(payload: AlertPayload): GuardianRelayDispatchResult {
        val startMs = System.currentTimeMillis()
        val guardians = connectivityManager.getActiveGuardians()
        if (guardians.isEmpty()) {
            val latency = System.currentTimeMillis() - startMs
            val err = "No paired Guardian devices configured"
            return GuardianRelayDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                latencyMs = latency,
                errorMessage = err,
                auditRecords = listOf(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = payload.incidentId,
                        eventId = "gg-${UUID.randomUUID()}",
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        errorMessage = err,
                        latencyMs = latency
                    )
                )
            )
        }

        val identity = connectivityManager.getIdentity()
        val defaultBase = connectivityManager.getRelayServerUrl()
        val audits = mutableListOf<CommunicationAuditRecord>()
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var lastErr: String? = null
        var firstMsgId: String? = null

        for (guardian in guardians) {
            val baseUrl = (guardian.relayEndpointUrl ?: defaultBase).trim().trimEnd('/')
            val eventId = "gg-${UUID.randomUUID()}"
            if (baseUrl.isBlank()) {
                val err = "GuardianGrid Relay Server URL is not configured (set HTTPS relay URL in Guardian Connectivity)"
                failed.add(guardian.deviceId)
                lastErr = err
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = payload.incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        recipient = guardian.deviceId,
                        errorMessage = err,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                )
                continue
            }

            val ts = System.currentTimeMillis().toString()
            val bodyJson = buildAlertEnvelopeJson(identity, guardian, payload)
            val sig = GuardianCryptoCodec.hmacSha256Hex(guardian.sharedSecretHex, "$ts|${payload.incidentId}|${guardian.deviceId}")
            val headers = mapOf(
                "X-Guardian-Device-Id" to identity.deviceId,
                "X-Guardian-Target-Id" to guardian.deviceId,
                "X-Guardian-Timestamp" to ts,
                "X-Guardian-Signature" to sig
            )

            try {
                val resp = transport.postJson("$baseUrl/v1/alerts", bodyJson, headers)
                val latency = System.currentTimeMillis() - startMs
                if (resp.isSuccessful) {
                    val msgId = extractJsonString(resp.body, "messageId") ?: eventId
                    if (firstMsgId == null) firstMsgId = msgId
                    delivered.add(guardian.deviceId)
                    audits.add(
                        CommunicationAuditRecord(
                            recordId = UUID.randomUUID().toString(),
                            incidentId = payload.incidentId,
                            eventId = eventId,
                            channel = DeliveryChannel.INTERNET_API,
                            state = EmergencyDeliveryState.DELIVERED,
                            attemptCount = 1,
                            recipient = guardian.deviceId,
                            messageId = msgId,
                            deliveredEpochMs = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                    )
                } else {
                    val err = "Relay HTTP ${resp.statusCode}: ${resp.body.take(120)}"
                    lastErr = err
                    failed.add(guardian.deviceId)
                    audits.add(
                        CommunicationAuditRecord(
                            recordId = UUID.randomUUID().toString(),
                            incidentId = payload.incidentId,
                            eventId = eventId,
                            channel = DeliveryChannel.INTERNET_API,
                            state = EmergencyDeliveryState.FAILED,
                            attemptCount = 1,
                            recipient = guardian.deviceId,
                            errorMessage = err,
                            latencyMs = latency
                        )
                    )
                }
            } catch (e: Exception) {
                val err = "Guardian relay error: ${e.message ?: "Connection failed"}"
                lastErr = err
                failed.add(guardian.deviceId)
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = payload.incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        recipient = guardian.deviceId,
                        errorMessage = err,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                )
            }
        }

        val finalState = if (delivered.isNotEmpty()) EmergencyDeliveryState.DELIVERED else EmergencyDeliveryState.FAILED
        return GuardianRelayDispatchResult(
            state = finalState,
            messageId = firstMsgId,
            deliveredDeviceIds = delivered,
            failedDeviceIds = failed,
            latencyMs = System.currentTimeMillis() - startMs,
            errorMessage = if (finalState == EmergencyDeliveryState.DELIVERED) null else lastErr,
            auditRecords = audits
        )
    }

    override fun sendAudioChunk(
        incidentId: String,
        chunkIndex: Int,
        isPreTrigger: Boolean,
        isFinal: Boolean,
        fileName: String,
        wavBytes: ByteArray
    ): GuardianRelayDispatchResult {
        val startMs = System.currentTimeMillis()
        val guardians = connectivityManager.getActiveGuardians()
        if (guardians.isEmpty()) {
            return GuardianRelayDispatchResult(
                state = EmergencyDeliveryState.FAILED,
                errorMessage = "No paired Guardian devices to receive audio relay",
                auditRecords = listOf(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = incidentId,
                        eventId = "gg-aud-${UUID.randomUUID()}",
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        errorMessage = "No paired Guardian devices configured"
                    )
                )
            )
        }

        val identity = connectivityManager.getIdentity()
        val defaultBase = connectivityManager.getRelayServerUrl()
        val audits = mutableListOf<CommunicationAuditRecord>()
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var firstMsgId: String? = null
        var lastErr: String? = null

        for (guardian in guardians) {
            val baseUrl = (guardian.relayEndpointUrl ?: defaultBase).trim().trimEnd('/')
            val eventId = "gg-aud-${UUID.randomUUID()}"
            if (baseUrl.isBlank()) {
                val err = "GuardianGrid Relay Server URL is not configured for audio streaming"
                lastErr = err
                failed.add(guardian.deviceId)
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        recipient = guardian.deviceId,
                        errorMessage = err
                    )
                )
                continue
            }

            val ts = System.currentTimeMillis().toString()
            val sig = GuardianCryptoCodec.hmacSha256Hex(guardian.sharedSecretHex, "$ts|$incidentId|$chunkIndex|${wavBytes.size}")
            val headers = mapOf(
                "X-Guardian-Device-Id" to identity.deviceId,
                "X-Guardian-Target-Id" to guardian.deviceId,
                "X-Guardian-Timestamp" to ts,
                "X-Guardian-Signature" to sig
            )
            val fields = mapOf(
                "incidentId" to incidentId,
                "hostDeviceId" to identity.deviceId,
                "hostDeviceName" to identity.deviceName,
                "targetGuardianDeviceId" to guardian.deviceId,
                "chunkIndex" to chunkIndex.toString(),
                "isPreTrigger" to isPreTrigger.toString(),
                "isFinal" to isFinal.toString()
            )

            try {
                val resp = transport.postMultipartFile(
                    url = "$baseUrl/v1/audio",
                    fieldName = "audio",
                    fileName = fileName,
                    mimeType = "audio/wav",
                    fileBytes = wavBytes,
                    formFields = fields,
                    headers = headers
                )
                val latency = System.currentTimeMillis() - startMs
                if (resp.isSuccessful) {
                    val msgId = extractJsonString(resp.body, "messageId") ?: eventId
                    if (firstMsgId == null) firstMsgId = msgId
                    delivered.add(guardian.deviceId)
                    audits.add(
                        CommunicationAuditRecord(
                            recordId = UUID.randomUUID().toString(),
                            incidentId = incidentId,
                            eventId = eventId,
                            channel = DeliveryChannel.INTERNET_API,
                            state = EmergencyDeliveryState.DELIVERED,
                            attemptCount = 1,
                            recipient = guardian.deviceId,
                            messageId = msgId,
                            deliveredEpochMs = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                    )
                } else {
                    val err = "Audio relay HTTP ${resp.statusCode}: ${resp.body.take(120)}"
                    lastErr = err
                    failed.add(guardian.deviceId)
                    audits.add(
                        CommunicationAuditRecord(
                            recordId = UUID.randomUUID().toString(),
                            incidentId = incidentId,
                            eventId = eventId,
                            channel = DeliveryChannel.INTERNET_API,
                            state = EmergencyDeliveryState.FAILED,
                            attemptCount = 1,
                            recipient = guardian.deviceId,
                            errorMessage = err,
                            latencyMs = latency
                        )
                    )
                }
            } catch (e: Exception) {
                val err = "Audio relay error: ${e.message ?: "Connection failed"}"
                lastErr = err
                failed.add(guardian.deviceId)
                audits.add(
                    CommunicationAuditRecord(
                        recordId = UUID.randomUUID().toString(),
                        incidentId = incidentId,
                        eventId = eventId,
                        channel = DeliveryChannel.INTERNET_API,
                        state = EmergencyDeliveryState.FAILED,
                        attemptCount = 1,
                        recipient = guardian.deviceId,
                        errorMessage = err
                    )
                )
            }
        }

        val finalState = if (delivered.isNotEmpty()) EmergencyDeliveryState.DELIVERED else EmergencyDeliveryState.FAILED
        return GuardianRelayDispatchResult(
            state = finalState,
            messageId = firstMsgId,
            deliveredDeviceIds = delivered,
            failedDeviceIds = failed,
            latencyMs = System.currentTimeMillis() - startMs,
            errorMessage = if (finalState == EmergencyDeliveryState.DELIVERED) null else lastErr,
            auditRecords = audits
        )
    }

    override fun pollIncomingAlerts(): Result<List<ReceivedGuardianAlert>> {
        val defaultUrl = connectivityManager.getRelayServerUrl().trim().trimEnd('/')
        val pairedHosts = connectivityManager.getPairedHosts()
        val effectiveBase = defaultUrl.ifBlank {
            pairedHosts.firstNotNullOfOrNull { it.relayEndpointUrl }?.trim()?.trimEnd('/').orEmpty()
        }
        if (effectiveBase.isBlank()) {
            return Result.failure(
                IllegalStateException("GuardianGrid Relay Server URL is not configured (enter HTTPS relay endpoint in Settings > Guardian Connectivity).")
            )
        }

        val identity = connectivityManager.getIdentity()
        val secretForHeader = pairedHosts.firstOrNull()?.sharedSecretHex ?: identity.hmacSecretHex
        val ts = System.currentTimeMillis().toString()
        val sig = GuardianCryptoCodec.hmacSha256Hex(secretForHeader, "$ts|inbox|${identity.deviceId}")
        val headers = mapOf(
            "X-Guardian-Device-Id" to identity.deviceId,
            "X-Guardian-Timestamp" to ts,
            "X-Guardian-Signature" to sig
        )

        return try {
            val response = transport.getJson("$effectiveBase/v1/inbox/${identity.deviceId}", headers)
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Relay poll failed (HTTP ${response.statusCode})"))
            }
            val parsed = parseInboxResponseJson(response.body, effectiveBase, headers)
            parsed.forEach { connectivityManager.recordReceivedAlert(it) }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun sendAcknowledgment(targetHostDeviceId: String, incidentId: String): Result<Unit> {
        connectivityManager.markReceivedAlertAcknowledged(incidentId)
        val pairedHost = connectivityManager.getPairedHosts().firstOrNull { it.deviceId == targetHostDeviceId }
        val baseUrl = (pairedHost?.relayEndpointUrl ?: connectivityManager.getRelayServerUrl()).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Acknowledged locally; relay URL not configured to notify host."))
        }
        val identity = connectivityManager.getIdentity()
        val ts = System.currentTimeMillis().toString()
        val secret = pairedHost?.sharedSecretHex ?: identity.hmacSecretHex
        val sig = GuardianCryptoCodec.hmacSha256Hex(secret, "$ts|ack|$incidentId|${identity.deviceId}")
        val headers = mapOf(
            "X-Guardian-Device-Id" to identity.deviceId,
            "X-Guardian-Timestamp" to ts,
            "X-Guardian-Signature" to sig
        )
        val json = """{"incidentId":"${jsonEscape(incidentId)}","guardianDeviceId":"${jsonEscape(identity.deviceId)}","guardianName":"${jsonEscape(identity.deviceName)}","acknowledgedAt":$ts}"""
        return try {
            val resp = transport.postJson("$baseUrl/v1/ack", json, headers)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(IllegalStateException("Ack HTTP ${resp.statusCode}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseInboxResponseJson(
        body: String,
        baseUrl: String,
        headers: Map<String, String>
    ): List<ReceivedGuardianAlert> {
        val objectRegex = """\{[^{}]*"incidentId"\s*:\s*"[^"]+"[^{}]*\}""".toRegex()
        val results = mutableListOf<ReceivedGuardianAlert>()
        for (match in objectRegex.findAll(body)) {
            val obj = match.value
            val incidentId = extractJsonString(obj, "incidentId") ?: continue
            val hostId = extractJsonString(obj, "hostDeviceId") ?: "unknown-host"
            val hostName = extractJsonString(obj, "hostDeviceName") ?: "Protected Device"
            val alertId = extractJsonString(obj, "alertId") ?: "rx-${UUID.randomUUID()}"
            val distressType = extractJsonString(obj, "distressType") ?: "EMERGENCY"
            val msg = extractJsonString(obj, "emergencyMessage") ?: "Emergency alert received"
            val conf = extractJsonNumber(obj, "confidence")?.toFloat() ?: 1.0f
            val lat = extractJsonNumber(obj, "latitude")
            val lon = extractJsonNumber(obj, "longitude")
            val acc = extractJsonNumber(obj, "accuracyMeters")?.toFloat()

            val clips = mutableListOf<ReceivedGuardianAudioClip>()
            val audioUrl = extractJsonString(obj, "audioUrl")
            if (!audioUrl.isNullOrBlank()) {
                val resolvedUrl = if (audioUrl.startsWith("http")) {
                    audioUrl
                } else {
                    "${baseUrl.trimEnd('/')}/${audioUrl.trimStart('/')}"
                }
                val fileName = extractJsonString(obj, "audioFileName") ?: "${incidentId}.wav"
                val chunkIdx = extractJsonNumber(obj, "chunkIndex")?.toInt() ?: 0
                val isPre = obj.contains(""""isPreTrigger"\s*:\s*true""".toRegex())
                val isFinal = obj.contains(""""isFinal"\s*:\s*true""".toRegex())
                val bin = runCatching { transport.getBinary(resolvedUrl, headers) }.getOrNull()
                if (bin != null && bin.isSuccessful && bin.bytes.isNotEmpty()) {
                    val saved = connectivityManager.attachReceivedAudioClip(
                        incidentId = incidentId,
                        hostDeviceId = hostId,
                        hostDeviceName = hostName,
                        chunkIndex = chunkIdx,
                        isPreTrigger = isPre,
                        isFinal = isFinal,
                        fileName = fileName,
                        wavBytes = bin.bytes
                    )
                    clips.add(saved)
                }
            }

            results.add(
                ReceivedGuardianAlert(
                    alertId = alertId,
                    incidentId = incidentId,
                    hostDeviceId = hostId,
                    hostDeviceName = hostName,
                    distressType = distressType,
                    confidence = conf,
                    emergencyMessage = msg,
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = acc,
                    audioClips = clips
                )
            )
        }
        return results
    }

    private fun buildAlertEnvelopeJson(
        identity: DeviceIdentity,
        guardian: PairedGuardianDevice,
        payload: AlertPayload
    ): String {
        val latJson = payload.location?.latitude?.toString() ?: "null"
        val lonJson = payload.location?.longitude?.toString() ?: "null"
        val accJson = payload.location?.accuracyMeters?.toString() ?: "null"
        val audioFileJson = payload.audioSegmentPath?.let { "\"${jsonEscape(File(it).name)}\"" } ?: "null"
        return """
            {
              "incidentId": "${jsonEscape(payload.incidentId)}",
              "timestampEpochMs": ${payload.timestampEpochMs},
              "hostDeviceId": "${jsonEscape(identity.deviceId)}",
              "hostDeviceName": "${jsonEscape(identity.deviceName)}",
              "targetGuardianDeviceId": "${jsonEscape(guardian.deviceId)}",
              "distressType": "${payload.distressType.name}",
              "confidence": ${payload.confidence},
              "emergencyMessage": "${jsonEscape(payload.emergencyMessage)}",
              "latitude": $latJson,
              "longitude": $lonJson,
              "accuracyMeters": $accJson,
              "hasAudio": ${!payload.audioSegmentPath.isNullOrBlank()},
              "audioFileName": $audioFileJson
            }
        """.trimIndent()
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonNumber(json: String, key: String): Double? {
        val regex = """"$key"\s*:\s*(-?\d+(?:\.\d+)?)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
