package com.guardiangrid.modules.communication.supabase

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.core.models.BleEmergencyToken
import com.guardiangrid.core.models.BleRelayAcknowledgement
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.guardian.GuardianCryptoCodec
import com.guardiangrid.modules.communication.models.CommunicationAuditRecord
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.DefaultHttpTransport
import com.guardiangrid.modules.communication.network.HttpTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime configuration for GuardianGrid's Supabase backend integration.
 * Keep Telegram bot tokens strictly on the Supabase Edge Function backend — never in Android code or APK resources.
 * Only the public project URL and public client anon key are held on Android.
 */
data class SupabaseBackendConfig(
    val projectUrl: String = DEFAULT_PROJECT_URL,
    val anonKey: String,
    val defaultChatId: String? = null,
    val retentionDays: Int = 7
) {
    fun isValid(): Boolean =
        projectUrl.trim().startsWith("https://") && anonKey.trim().length >= 20

    fun functionsBaseUrl(): String = "${projectUrl.trim().trimEnd('/')}/functions/v1/emergency-relay"

    companion object {
        const val DEFAULT_PROJECT_URL = "https://yhzvpuycztuwdqttpoid.supabase.co"
    }
}

/**
 * Thread-safe runtime configuration holder for Supabase backend URL and public client anon key.
 * Zero Telegram tokens or Supabase service-role secrets exist in Android source or APK assets.
 */
object SupabaseConfigManager {
    private val configRef = AtomicReference<SupabaseBackendConfig?>(null)
    private var persistenceFile: File? = null

    @Synchronized
    fun initialize(storageDir: File) {
        if (!storageDir.exists()) storageDir.mkdirs()
        persistenceFile = File(storageDir, "supabase_backend.dat")
        persistenceFile?.takeIf { it.exists() }?.let { file ->
            runCatching {
                val lines = file.readLines()
                if (lines.size >= 2) {
                    val cfg = SupabaseBackendConfig(
                        projectUrl = lines[0].trim(),
                        anonKey = lines[1].trim(),
                        defaultChatId = lines.getOrNull(2)?.trim()?.ifBlank { null },
                        retentionDays = lines.getOrNull(3)?.toIntOrNull() ?: 7
                    )
                    if (cfg.isValid()) configRef.set(cfg)
                }
            }
        }
    }

    @Synchronized
    fun setConfig(config: SupabaseBackendConfig) {
        if (!config.isValid()) return
        configRef.set(config)
        persistenceFile?.writeText(
            listOf(
                config.projectUrl.trim(),
                config.anonKey.trim(),
                config.defaultChatId.orEmpty(),
                config.retentionDays.toString()
            ).joinToString("\n")
        )
    }

    @Synchronized
    fun clear() {
        configRef.set(null)
        persistenceFile?.takeIf { it.exists() }?.delete()
    }

    fun getConfig(): SupabaseBackendConfig? = configRef.get()
    fun isConfigured(): Boolean = configRef.get()?.isValid() == true
}

data class SupabaseRelayResult(
    val state: EmergencyDeliveryState,
    val storagePath: String? = null,
    val messageId: String? = null,
    val retryAfterSeconds: Int? = null,
    val errorMessage: String? = null,
    val auditRecord: CommunicationAuditRecord
)

/**
 * Authenticated Android -> Supabase Edge Function client.
 * - Registers the device's per-install HMAC key via `/register-device` (never exposes shared/service secrets in APK).
 * - Registers authorized pairings via `/pair` before alerts/audio can be routed.
 * - Authenticates every request with `Authorization`, `apikey`, `X-Guardian-Device-Id`, `X-Guardian-Timestamp`,
 *   `X-Guardian-Nonce`, and `X-Guardian-Signature`.
 */
class SupabaseEmergencyBackendClient(
    private val connectivityManager: GuardianConnectivityManager,
    private val transport: HttpTransport = DefaultHttpTransport(),
    private val configProvider: () -> SupabaseBackendConfig? = { SupabaseConfigManager.getConfig() }
) {
    private val deviceRegistered = AtomicBoolean(false)

    fun isConfigured(): Boolean = configProvider()?.isValid() == true

    /**
     * Registers or updates this device's cryptographic identity with Supabase `/register-device`.
     */
    fun registerDevice(): Result<String> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))

        val identity = connectivityManager.getIdentity()
        val headers = buildAuthenticatedHeaders(cfg, "register|${identity.deviceId}")
        val json = """{"deviceId":"${jsonEscape(identity.deviceId)}","deviceName":"${jsonEscape(identity.deviceName)}","deviceSecret":"${identity.hmacSecretHex}"}"""

        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/register-device", json, headers)
            if (resp.isSuccessful) {
                deviceRegistered.set(true)
                Result.success(identity.deviceId)
            } else {
                Result.failure(IllegalStateException("Device registration failed (HTTP ${resp.statusCode}): ${resp.body.take(120)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Authorizes a paired Host <-> Guardian relationship on the backend so only authorized guardians
     * can receive emergency alerts and signed private audio links.
     */
    fun registerPairing(hostDeviceId: String, guardianDeviceId: String): Result<Unit> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))

        ensureDeviceRegistered()
        val headers = buildAuthenticatedHeaders(cfg, "pair|$hostDeviceId|$guardianDeviceId")
        val json = """{"hostDeviceId":"${jsonEscape(hostDeviceId)}","guardianDeviceId":"${jsonEscape(guardianDeviceId)}"}"""
        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/pair", json, headers)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(IllegalStateException("Pairing sync HTTP ${resp.statusCode}: ${resp.body.take(120)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun dispatchAlert(payload: AlertPayload): SupabaseRelayResult {
        val startMs = System.currentTimeMillis()
        val eventId = "sb-alert-${UUID.randomUUID()}"
        val cfg = configProvider()
        if (cfg == null || !cfg.isValid()) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Supabase backend not configured"
            )
            return SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }

        ensureDeviceRegistered()
        val guardians = connectivityManager.getActiveGuardians()
        guardians.forEach { g -> registerPairing(connectivityManager.getIdentity().deviceId, g.deviceId) }

        val headers = buildAuthenticatedHeaders(cfg, "alert|${payload.incidentId}")
        val targetsJson = guardians.joinToString(",") { "\"${jsonEscape(it.deviceId)}\"" }
        val latJson = payload.location?.latitude?.toString() ?: "null"
        val lonJson = payload.location?.longitude?.toString() ?: "null"
        val accJson = payload.location?.accuracyMeters?.toString() ?: "null"
        val chatJson = cfg.defaultChatId?.takeIf { it.isNotBlank() }?.let { "\"${jsonEscape(it)}\"" } ?: "null"
        val jsonBody = """{"incidentId":"${jsonEscape(payload.incidentId)}","distressType":"${payload.distressType.name}","confidence":${payload.confidence},"emergencyMessage":"${jsonEscape(payload.emergencyMessage)}","latitude":$latJson,"longitude":$lonJson,"accuracyMeters":$accJson,"targetGuardianDeviceIds":[$targetsJson],"chatId":$chatJson}"""

        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/alert", jsonBody, headers)
            val latency = System.currentTimeMillis() - startMs
            if (resp.isSuccessful) {
                val msgId = """"messageId"\s*:\s*"([^"]+)"""".toRegex().find(resp.body)?.groupValues?.get(1) ?: eventId
                val audit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = payload.incidentId,
                    eventId = eventId,
                    channel = DeliveryChannel.INTERNET_API,
                    state = EmergencyDeliveryState.DELIVERED,
                    attemptCount = 1,
                    recipient = "Supabase-Backend",
                    messageId = msgId,
                    deliveredEpochMs = System.currentTimeMillis(),
                    latencyMs = latency
                )
                SupabaseRelayResult(state = EmergencyDeliveryState.DELIVERED, messageId = msgId, auditRecord = audit)
            } else {
                val retryAfter = parseRetryAfter(resp.statusCode, resp.headers, resp.body)
                val err = "Supabase alert HTTP ${resp.statusCode}: ${resp.body.take(120)}"
                val audit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = payload.incidentId,
                    eventId = eventId,
                    channel = DeliveryChannel.INTERNET_API,
                    state = EmergencyDeliveryState.FAILED,
                    attemptCount = 1,
                    recipient = "Supabase-Backend",
                    errorMessage = err,
                    latencyMs = latency,
                    retryAfterSeconds = retryAfter
                )
                SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, retryAfterSeconds = retryAfter, errorMessage = err, auditRecord = audit)
            }
        } catch (e: Exception) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = payload.incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                recipient = "Supabase-Backend",
                errorMessage = "Supabase alert network error: ${e.message}"
            )
            SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }
    }

    fun uploadAudioChunk(
        incidentId: String,
        chunkSequence: Int,
        isPreTrigger: Boolean,
        isFinal: Boolean,
        durationMs: Long,
        capturedAtEpochMs: Long,
        caption: String,
        wavFile: File
    ): SupabaseRelayResult {
        val startMs = System.currentTimeMillis()
        val eventId = "sb-aud-${UUID.randomUUID()}"
        val cfg = configProvider()
        if (cfg == null || !cfg.isValid()) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Supabase backend URL/key is not configured",
                latencyMs = System.currentTimeMillis() - startMs
            )
            return SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }
        if (!wavFile.exists() || wavFile.length() <= 0L) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "WAV audio file missing for Supabase upload: ${wavFile.name}",
                latencyMs = System.currentTimeMillis() - startMs
            )
            return SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }

        ensureDeviceRegistered()
        val wavBytes = wavFile.readBytes()
        val canonical = "$incidentId|$chunkSequence|${wavBytes.size}"
        val headers = buildAuthenticatedHeaders(cfg, canonical)

        return try {
            val resp = transport.postMultipartFile(
                url = "${cfg.functionsBaseUrl()}/audio-chunk",
                fieldName = "audio",
                fileName = wavFile.name,
                mimeType = "audio/wav",
                fileBytes = wavBytes,
                formFields = buildMap {
                    put("incidentId", incidentId)
                    put("chunkSequence", chunkSequence.toString())
                    put("isPreTrigger", isPreTrigger.toString())
                    put("isFinal", isFinal.toString())
                    put("durationMs", durationMs.toString())
                    put("capturedAtEpochMs", capturedAtEpochMs.toString())
                    put("caption", caption)
                    cfg.defaultChatId?.takeIf { it.isNotBlank() }?.let { put("chatId", it) }
                },
                headers = headers
            )
            val latency = System.currentTimeMillis() - startMs
            if (resp.isSuccessful) {
                val msgId = """"messageId"\s*:\s*"([^"]+)"""".toRegex().find(resp.body)?.groupValues?.get(1) ?: eventId
                val storagePath = """"storagePath"\s*:\s*"([^"]+)"""".toRegex().find(resp.body)?.groupValues?.get(1)
                val audit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = incidentId,
                    eventId = eventId,
                    channel = DeliveryChannel.INTERNET_API,
                    state = EmergencyDeliveryState.DELIVERED,
                    attemptCount = 1,
                    recipient = "Supabase-Backend",
                    messageId = msgId,
                    deliveredEpochMs = System.currentTimeMillis(),
                    latencyMs = latency
                )
                SupabaseRelayResult(
                    state = EmergencyDeliveryState.DELIVERED,
                    storagePath = storagePath,
                    messageId = msgId,
                    auditRecord = audit
                )
            } else {
                val retryAfter = parseRetryAfter(resp.statusCode, resp.headers, resp.body)
                val err = if (retryAfter != null) {
                    "Supabase relay rate limited (HTTP 429 retry_after=${retryAfter}s)"
                } else {
                    "Supabase relay HTTP ${resp.statusCode}: ${resp.body.take(120)}"
                }
                val audit = CommunicationAuditRecord(
                    recordId = UUID.randomUUID().toString(),
                    incidentId = incidentId,
                    eventId = eventId,
                    channel = DeliveryChannel.INTERNET_API,
                    state = EmergencyDeliveryState.FAILED,
                    attemptCount = 1,
                    recipient = "Supabase-Backend",
                    errorMessage = err,
                    latencyMs = latency,
                    retryAfterSeconds = retryAfter
                )
                SupabaseRelayResult(
                    state = EmergencyDeliveryState.FAILED,
                    retryAfterSeconds = retryAfter,
                    errorMessage = err,
                    auditRecord = audit
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startMs
            val err = "Supabase upload network error: ${e.message ?: "Connection failed"}"
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                recipient = "Supabase-Backend",
                errorMessage = err,
                latencyMs = latency
            )
            SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = err, auditRecord = audit)
        }
    }

    fun notifyIncidentCompleted(
        incidentId: String,
        totalChunks: Int,
        totalDurationMs: Long,
        summaryMessage: String
    ): SupabaseRelayResult {
        val startMs = System.currentTimeMillis()
        val eventId = "sb-done-${UUID.randomUUID()}"
        val cfg = configProvider()
        if (cfg == null || !cfg.isValid()) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                errorMessage = "Supabase backend not configured"
            )
            return SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }

        ensureDeviceRegistered()
        val headers = buildAuthenticatedHeaders(cfg, "complete|$incidentId")
        val chatJson = cfg.defaultChatId?.let { "\"${jsonEscape(it)}\"" } ?: "null"
        val jsonBody = """{"incidentId":"${jsonEscape(incidentId)}","totalChunks":$totalChunks,"totalDurationMs":$totalDurationMs,"summaryMessage":"${jsonEscape(summaryMessage)}","chatId":$chatJson}"""

        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/complete", jsonBody, headers)
            val latency = System.currentTimeMillis() - startMs
            val ok = resp.isSuccessful
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = if (ok) EmergencyDeliveryState.DELIVERED else EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                recipient = "Supabase-Backend",
                messageId = if (ok) eventId else null,
                errorMessage = if (ok) null else "HTTP ${resp.statusCode}",
                deliveredEpochMs = if (ok) System.currentTimeMillis() else null,
                latencyMs = latency
            )
            SupabaseRelayResult(
                state = audit.state,
                messageId = audit.messageId,
                errorMessage = audit.errorMessage,
                auditRecord = audit
            )
        } catch (e: Exception) {
            val audit = CommunicationAuditRecord(
                recordId = UUID.randomUUID().toString(),
                incidentId = incidentId,
                eventId = eventId,
                channel = DeliveryChannel.INTERNET_API,
                state = EmergencyDeliveryState.FAILED,
                attemptCount = 1,
                recipient = "Supabase-Backend",
                errorMessage = "Supabase complete notification error: ${e.message}"
            )
            SupabaseRelayResult(state = EmergencyDeliveryState.FAILED, errorMessage = audit.errorMessage, auditRecord = audit)
        }
    }

    /**
     * Obtains a short-lived BLE emergency signing token from the backend for offline alert signing.
     */
    fun issueBleToken(accountId: String, deviceId: String, validityHours: Int = 72): Result<BleEmergencyToken> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))

        ensureDeviceRegistered()
        val headers = buildAuthenticatedHeaders(cfg, "issue-ble-token|$accountId|$deviceId")
        val json = """{"accountId":"${jsonEscape(accountId)}","deviceId":"${jsonEscape(deviceId)}","validityHours":$validityHours}"""

        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/issue-ble-token", json, headers)
            if (resp.isSuccessful) {
                val token = BleEmergencyToken.fromJson(resp.body)
                if (token != null) Result.success(token)
                else Result.failure(IllegalStateException("Failed to parse token response: ${resp.body}"))
            } else {
                Result.failure(IllegalStateException("Token issuance failed HTTP ${resp.statusCode}: ${resp.body.take(120)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Forwards an emergency alert packet received by this Relay Phone to the Supabase backend.
     * Preserves the original packet and its signature completely unchanged.
     */
    fun forwardBleRelayAlert(relayDeviceId: String, packet: BleEmergencyAlertPacket): Result<BleRelayAcknowledgement> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))

        val headers = mapOf(
            "Authorization" to "Bearer ${cfg.anonKey.trim()}",
            "apikey" to cfg.anonKey.trim(),
            "Content-Type" to "application/json"
        )
        val jsonBody = """{"relayDeviceId":"${jsonEscape(relayDeviceId)}","packet":${packet.toJson()}}"""

        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/ble-relay-alert", jsonBody, headers)
            if (resp.isSuccessful) {
                val ack = parseBleRelayAck(resp.body, packet.alertId)
                Result.success(ack)
            } else {
                Result.failure(IllegalStateException("Relay forwarding failed HTTP ${resp.statusCode}: ${resp.body.take(120)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseBleRelayAck(json: String, defaultAlertId: String): BleRelayAcknowledgement {
        val ok = json.contains(""""ok"\s*:\s*true""".toRegex())
        val alertId = """"alertId"\s*:\s*"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1) ?: defaultAlertId
        val status = """"status"\s*:\s*"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1) ?: "RECEIVED"
        val smsDispatched = json.contains(""""smsDispatched"\s*:\s*true""".toRegex())
        val smsError = """"smsError"\s*:\s*"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1)
        val duplicate = json.contains(""""duplicate"\s*:\s*true""".toRegex())
        return BleRelayAcknowledgement(
            ok = ok,
            alertId = alertId,
            status = status,
            smsDispatched = smsDispatched,
            smsError = smsError,
            acknowledged = true,
            duplicate = duplicate
        )
    }

    private fun ensureDeviceRegistered() {
        if (!deviceRegistered.get()) {
            registerDevice()
        }
    }

    internal fun buildAuthenticatedHeaders(
        cfg: SupabaseBackendConfig,
        canonicalSuffix: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Map<String, String> {
        val identity = connectivityManager.getIdentity()
        val nonce = GuardianCryptoCodec.randomHex(12)
        val sig = GuardianCryptoCodec.hmacSha256Hex(identity.hmacSecretHex, "$timestampMs|$nonce|$canonicalSuffix")
        return mapOf(
            "Authorization" to "Bearer ${cfg.anonKey.trim()}",
            "apikey" to cfg.anonKey.trim(),
            "X-Guardian-Device-Id" to identity.deviceId,
            "X-Guardian-Timestamp" to timestampMs.toString(),
            "X-Guardian-Nonce" to nonce,
            "X-Guardian-Signature" to sig
        )
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun parseRetryAfter(statusCode: Int, headers: Map<String, List<String>>, body: String): Int? {
        if (statusCode != 429) return null
        val headerVal = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.toIntOrNull()
        if (headerVal != null) return headerVal
        return """"retry_after"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toIntOrNull()
    }
}
