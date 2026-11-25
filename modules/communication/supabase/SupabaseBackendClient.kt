package com.guardiangrid.modules.communication.supabase

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.core.models.BleEmergencyToken
import com.guardiangrid.core.models.BleRelayAcknowledgement
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.guardian.GuardianCryptoCodec
import com.guardiangrid.modules.communication.guardian.PairingInvitation
import com.guardiangrid.modules.communication.guardian.PairingRole
import com.guardiangrid.modules.communication.guardian.ReceivedGuardianAlert
import com.guardiangrid.modules.communication.guardian.ReceivedGuardianAudioClip
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

data class ConfirmedPairingResult(
    val inviteId: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val guardianDeviceId: String
)

data class RemotePairedPeer(
    val deviceId: String,
    val deviceName: String
)

data class SyncedPairingsResult(
    val guardians: List<RemotePairedPeer>,
    val hosts: List<RemotePairedPeer>
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

        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Device registration with backend failed"))
        }
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

    /**
     * Registers a single-use, expiring QR pairing invitation on the backend.
     * Permanent device secrets stay off the QR code.
     */
    fun createPairingInvitation(invite: PairingInvitation): Result<Unit> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))
        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Device registration with backend failed"))
        }

        val headers = buildAuthenticatedHeaders(cfg, "invite|${invite.inviteId}")
        val json = """{"inviteId":"${jsonEscape(invite.inviteId)}","hostDeviceId":"${jsonEscape(invite.deviceId)}","hostDeviceName":"${jsonEscape(invite.deviceName)}","inviteToken":"${jsonEscape(invite.sharedSecretHex)}","expiresAtEpochMs":${invite.expiresAtEpochMs}}"""
        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/pairing/invite", json, headers)
            if (resp.isSuccessful) {
                connectivityManager.markActiveInvitationRegistered(true, "Single-use QR invite active on backend")
                Result.success(Unit)
            } else {
                connectivityManager.markActiveInvitationRegistered(false, "Backend invite error HTTP ${resp.statusCode}")
                Result.failure(IllegalStateException("Invite registration HTTP ${resp.statusCode}: ${resp.body.take(120)}"))
            }
        } catch (e: Exception) {
            connectivityManager.markActiveInvitationRegistered(false, "Offline: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Authenticates the scanning Receiver device, verifies & consumes the single-use invitation
     * on Supabase, and commits the confirmed pairing locally ONLY AFTER HTTP 200 confirmation.
     */
    fun acceptPairingInvitationRemote(invite: PairingInvitation): Result<ConfirmedPairingResult> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))
        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Receiver device authentication with backend failed"))
        }

        val headers = buildAuthenticatedHeaders(cfg, "accept|${invite.inviteId}")
        val json = """{"inviteId":"${jsonEscape(invite.inviteId)}","hostDeviceId":"${jsonEscape(invite.deviceId)}","inviteToken":"${jsonEscape(invite.sharedSecretHex)}"}"""
        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/pairing/accept", json, headers)
            if (!resp.isSuccessful) {
                val err = extractJsonString(resp.body, "error") ?: "HTTP ${resp.statusCode}"
                return Result.failure(IllegalStateException("Backend pairing rejected: $err"))
            }
            val confirmed = resp.body.contains(""""pairingConfirmed"\s*:\s*true""".toRegex())
            if (!confirmed) {
                return Result.failure(IllegalStateException("Backend did not confirm pairing"))
            }
            val hostId = extractJsonString(resp.body, "hostDeviceId") ?: invite.deviceId
            val hostName = extractJsonString(resp.body, "hostDeviceName") ?: invite.deviceName
            val guardianId = extractJsonString(resp.body, "guardianDeviceId") ?: connectivityManager.getIdentity().deviceId

            connectivityManager.commitBackendConfirmedPairing(
                peerDeviceId = hostId,
                peerDisplayName = hostName,
                roleOfPeer = PairingRole.HOST,
                consumedInviteId = invite.inviteId,
                relayEndpointUrl = cfg.functionsBaseUrl()
            )
            Result.success(ConfirmedPairingResult(invite.inviteId, hostId, hostName, guardianId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches backend-confirmed Guardians and Hosts for this device and reconciles local state.
     */
    fun fetchConfirmedPairings(): Result<SyncedPairingsResult> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))
        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Device registration required to sync pairings"))
        }

        val myId = connectivityManager.getIdentity().deviceId
        val headers = buildAuthenticatedHeaders(cfg, "pairings|$myId")
        return try {
            val resp = transport.getJson("${cfg.functionsBaseUrl()}/pairings?deviceId=$myId", headers)
            if (!resp.isSuccessful) {
                return Result.failure(IllegalStateException("Pairing sync HTTP ${resp.statusCode}"))
            }
            val guardiansBlock = extractJsonArrayContent(resp.body, "guardians")
            val hostsBlock = extractJsonArrayContent(resp.body, "hosts")
            val guardians = parsePeerArray(guardiansBlock)
            val hosts = parsePeerArray(hostsBlock)
            connectivityManager.reconcileBackendPairings(
                confirmedGuardians = guardians.map { it.deviceId to it.deviceName },
                confirmedHosts = hosts.map { it.deviceId to it.deviceName },
                relayUrl = cfg.functionsBaseUrl()
            )
            Result.success(SyncedPairingsResult(guardians, hosts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls `/inbox` for paired-host alerts and downloads WAV evidence chunks in sequence order.
     */
    fun pollGuardianInbox(): Result<List<ReceivedGuardianAlert>> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))
        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Guardian device authentication required to poll inbox"))
        }

        val headers = buildAuthenticatedHeaders(cfg, "inbox|all")
        return try {
            val resp = transport.getJson("${cfg.functionsBaseUrl()}/inbox", headers)
            if (!resp.isSuccessful) {
                return Result.failure(IllegalStateException("Inbox sync HTTP ${resp.statusCode}: ${resp.body.take(120)}"))
            }
            val alerts = parseSupabaseInboxAlerts(resp.body)
            alerts.forEach { alert -> connectivityManager.recordReceivedAlert(alert) }
            Result.success(alerts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends authenticated Guardian receipt acknowledgment to Supabase `/ack`.
     */
    fun acknowledgeIncident(incidentId: String): Result<Unit> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))
        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Device authentication required to acknowledge alert"))
        }

        val identity = connectivityManager.getIdentity()
        val headers = buildAuthenticatedHeaders(cfg, "ack|$incidentId")
        val json = """{"incidentId":"${jsonEscape(incidentId)}","guardianName":"${jsonEscape(identity.deviceName)}"}"""
        return try {
            val resp = transport.postJson("${cfg.functionsBaseUrl()}/ack", json, headers)
            if (resp.isSuccessful) {
                connectivityManager.markReceivedAlertAcknowledged(incidentId)
                Result.success(Unit)
            } else {
                val err = extractJsonString(resp.body, "error") ?: "HTTP ${resp.statusCode}"
                Result.failure(IllegalStateException("Ack rejected: $err"))
            }
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

    private fun ensureDeviceRegistered(): Boolean {
        if (deviceRegistered.get()) return true
        return registerDevice().isSuccess
    }

    private fun parseSupabaseInboxAlerts(body: String): List<ReceivedGuardianAlert> {
        val existingByIncident = connectivityManager.state.value.receivedAlerts.associateBy { it.incidentId }
        val alertsArray = extractJsonArrayContent(body, "alerts")
        if (alertsArray.isBlank()) return emptyList()

        val objects = splitTopLevelJsonObjects(alertsArray)
        val results = mutableListOf<ReceivedGuardianAlert>()
        for (obj in objects) {
            val incidentId = extractJsonString(obj, "incidentId") ?: continue
            val hostId = extractJsonString(obj, "hostDeviceId") ?: "unknown-host"
            val hostName = extractJsonString(obj, "hostDeviceName") ?: "Protected Device"
            val distressType = extractJsonString(obj, "distressType") ?: "EMERGENCY"
            val conf = extractJsonNumber(obj, "confidence")?.toFloat() ?: 1.0f
            val msg = extractJsonString(obj, "emergencyMessage") ?: "Emergency Alert"
            val lat = extractJsonNumber(obj, "latitude")
            val lon = extractJsonNumber(obj, "longitude")
            val acc = extractJsonNumber(obj, "accuracyMeters")?.toFloat()
            val acked = obj.contains(""""isAcknowledged"\s*:\s*true""".toRegex())

            val existingClips = existingByIncident[incidentId]?.audioClips?.associateBy { it.chunkIndex }.orEmpty()
            val chunksArray = extractJsonArrayContent(obj, "chunks")
            val chunkObjs = if (chunksArray.isBlank()) emptyList() else splitTopLevelJsonObjects(chunksArray)
            val clips = mutableListOf<ReceivedGuardianAudioClip>()

            for (ch in chunkObjs) {
                val seq = extractJsonNumber(ch, "chunkSequence")?.toInt() ?: 0
                val isPre = ch.contains(""""isPreTrigger"\s*:\s*true""".toRegex())
                val isFinal = ch.contains(""""isFinal"\s*:\s*true""".toRegex())
                val fileName = extractJsonString(ch, "fileName") ?: "${incidentId}_chunk_${seq}.wav"
                val signedUrl = extractJsonString(ch, "signedAudioUrl")

                val cached = existingClips[seq]?.takeIf {
                    !it.localFilePath.isNullOrBlank() && File(it.localFilePath).exists() && File(it.localFilePath).length() > 44L
                }
                if (cached != null) {
                    clips.add(cached.copy(isFinal = isFinal || cached.isFinal))
                } else if (!signedUrl.isNullOrBlank()) {
                    val bin = runCatching { transport.getBinary(signedUrl) }.getOrNull()
                    if (bin != null && bin.isSuccessful && bin.bytes.isNotEmpty()) {
                        val saved = connectivityManager.attachReceivedAudioClip(
                            incidentId = incidentId,
                            hostDeviceId = hostId,
                            hostDeviceName = hostName,
                            chunkIndex = seq,
                            isPreTrigger = isPre,
                            isFinal = isFinal,
                            fileName = fileName,
                            wavBytes = bin.bytes
                        )
                        clips.add(saved)
                    }
                }
            }

            results.add(
                ReceivedGuardianAlert(
                    alertId = existingByIncident[incidentId]?.alertId ?: "rx-$incidentId",
                    incidentId = incidentId,
                    hostDeviceId = hostId,
                    hostDeviceName = hostName,
                    distressType = distressType,
                    confidence = conf,
                    emergencyMessage = msg,
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = acc,
                    isAcknowledged = acked,
                    audioClips = clips.sortedBy { if (it.isPreTrigger) 0 else it.chunkIndex }
                )
            )
        }
        return results
    }

    /**
     * Obtains a short-lived BLE emergency signing token from the backend for offline alert signing.
     */
    fun issueBleToken(accountId: String, deviceId: String, validityHours: Int = 72): Result<BleEmergencyToken> {
        val cfg = configProvider() ?: return Result.failure(IllegalStateException("Supabase backend not configured"))
        if (!cfg.isValid()) return Result.failure(IllegalArgumentException("Invalid Supabase configuration"))

        if (!ensureDeviceRegistered()) {
            return Result.failure(IllegalStateException("Device registration with backend failed"))
        }
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

    private fun extractJsonArrayContent(json: String, key: String): String {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return ""
        val bracketStart = json.indexOf('[', keyIdx)
        if (bracketStart < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in bracketStart until json.length) {
            val c = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return json.substring(bracketStart + 1, i)
                }
            }
        }
        return ""
    }

    private fun splitTopLevelJsonObjects(arrayContent: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in arrayContent.indices) {
            val c = arrayContent[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    if (depth == 0) start = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(arrayContent.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun parsePeerArray(arrayContent: String): List<RemotePairedPeer> {
        if (arrayContent.isBlank()) return emptyList()
        return splitTopLevelJsonObjects(arrayContent).mapNotNull { obj ->
            val id = extractJsonString(obj, "deviceId") ?: return@mapNotNull null
            val name = extractJsonString(obj, "deviceName") ?: "GuardianGrid Device"
            RemotePairedPeer(id, name)
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        return """"$key"\s*:\s*"([^"]*)"""".toRegex().find(json)?.groupValues?.get(1)
    }

    private fun extractJsonNumber(json: String, key: String): Double? {
        return """"$key"\s*:\s*(-?\d+(?:\.\d+)?)""".toRegex().find(json)?.groupValues?.get(1)?.toDoubleOrNull()
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
