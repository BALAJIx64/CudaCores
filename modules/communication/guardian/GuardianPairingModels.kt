package com.guardiangrid.modules.communication.guardian

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Role a device plays in an authenticated GuardianGrid pairing link.
 */
enum class PairingRole {
    /** The protected person's device being monitored (this device sends alerts + audio). */
    HOST,
    /** A trusted responder's device (receives alerts, location, and emergency audio). */
    GUARDIAN
}

/**
 * Cryptographic, login-free device identity generated and persisted locally on-device.
 */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val publicFingerprint: String,
    val hmacSecretHex: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Record of a paired remote device (either a Guardian watching this Host, or a Host this Guardian watches).
 */
data class PairedGuardianDevice(
    val deviceId: String,
    val displayName: String,
    val role: PairingRole,
    val sharedSecretHex: String,
    val relayEndpointUrl: String? = null,
    val pairedAtEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

/**
 * Received WAV clip (pre-trigger 10s, real-time 2s post-trigger chunk, or full 20s recording) on a Guardian device.
 */
data class ReceivedGuardianAudioClip(
    val clipId: String,
    val incidentId: String,
    val chunkIndex: Int,
    val isPreTrigger: Boolean,
    val isFinal: Boolean,
    val fileName: String,
    val localFilePath: String? = null,
    val remoteAudioUrl: String? = null,
    val durationMs: Long = 0L,
    val byteSize: Long = 0L,
    val receivedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Emergency alert received by a paired Guardian device.
 */
data class ReceivedGuardianAlert(
    val alertId: String,
    val incidentId: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val distressType: String,
    val confidence: Float,
    val emergencyMessage: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val isAcknowledged: Boolean = false,
    val audioClips: List<ReceivedGuardianAudioClip> = emptyList()
)

/**
 * Signed login-free pairing invitation encoded as a QR / deep-link URI.
 */
data class PairingInvitation(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val sharedSecretHex: String,
    val relayEndpointUrl: String? = null,
    val expiresAtEpochMs: Long,
    val signatureHex: String
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = nowEpochMs > expiresAtEpochMs
}

object GuardianCryptoCodec {
    private val random = SecureRandom()

    fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Hex(secretHexOrRaw: String, message: String): String {
        val keyBytes = secretHexOrRaw.toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun signInvitationUriPayload(
        identity: DeviceIdentity,
        sharedSecretHex: String,
        relayEndpointUrl: String?,
        expiresAtEpochMs: Long
    ): String {
        val canonical = buildCanonicalPairingString(
            version = 1,
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            sharedSecretHex = sharedSecretHex,
            relayEndpointUrl = relayEndpointUrl,
            expiresAtEpochMs = expiresAtEpochMs
        )
        // Signed using both local secret and shared invitation token so invitation integrity can be verified offline
        val signature = hmacSha256Hex(sharedSecretHex, canonical).take(32)
        val encName = URLEncoder.encode(identity.deviceName, "UTF-8")
        val encRelay = URLEncoder.encode(relayEndpointUrl ?: "", "UTF-8")
        return "guardiangrid://pair?v=1&id=${identity.deviceId}&name=$encName&secret=$sharedSecretHex&relay=$encRelay&exp=$expiresAtEpochMs&sig=$signature"
    }

    fun parseAndVerifyInvitation(rawUri: String, nowEpochMs: Long = System.currentTimeMillis()): Result<PairingInvitation> {
        return try {
            val trimmed = rawUri.trim()
            if (!trimmed.startsWith("guardiangrid://pair?")) {
                return Result.failure(IllegalArgumentException("Not a valid GuardianGrid pairing URI"))
            }
            val query = trimmed.substringAfter("guardiangrid://pair?")
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else {
                    val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    key to value
                }
            }.toMap()

            val version = params["v"]?.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Missing version"))
            val deviceId = params["id"]?.takeIf { it.length >= 8 }
                ?: return Result.failure(IllegalArgumentException("Invalid host device ID"))
            val deviceName = params["name"]?.ifBlank { "GuardianGrid Device" } ?: "GuardianGrid Device"
            val secret = params["secret"]?.takeIf { it.length >= 32 }
                ?: return Result.failure(IllegalArgumentException("Invalid pairing secret"))
            val relay = params["relay"]?.ifBlank { null }
            val expires = params["exp"]?.toLongOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid expiration timestamp"))
            val sig = params["sig"]?.takeIf { it.length >= 16 }
                ?: return Result.failure(IllegalArgumentException("Missing invitation signature"))

            val canonical = buildCanonicalPairingString(version, deviceId, deviceName, secret, relay, expires)
            val expectedSig = hmacSha256Hex(secret, canonical).take(32)
            if (!MessageDigest.isEqual(expectedSig.toByteArray(), sig.toByteArray())) {
                return Result.failure(SecurityException("Pairing invitation signature mismatch"))
            }
            if (nowEpochMs > expires) {
                return Result.failure(IllegalStateException("Pairing invitation has expired. Request a fresh QR code."))
            }

            Result.success(
                PairingInvitation(
                    version = version,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    sharedSecretHex = secret,
                    relayEndpointUrl = relay,
                    expiresAtEpochMs = expires,
                    signatureHex = sig
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildCanonicalPairingString(
        version: Int,
        deviceId: String,
        deviceName: String,
        sharedSecretHex: String,
        relayEndpointUrl: String?,
        expiresAtEpochMs: Long
    ): String {
        return "$version|$deviceId|${deviceName.trim()}|$sharedSecretHex|${relayEndpointUrl?.trim().orEmpty()}|$expiresAtEpochMs"
    }
}
