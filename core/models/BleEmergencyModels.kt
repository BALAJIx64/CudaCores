package com.guardiangrid.core.models

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned BLE emergency-alert packet transferred from Sender Phone to Relay Phone over BLE GATT.
 *
 * Implements strict canonical serialization so that both Sender Phone and Supabase Edge Function
 * compute identical HMAC-SHA256 signatures over the exact same canonical string representation.
 */
data class BleEmergencyAlertPacket(
    val protocolVersion: Int = 1,
    val alertId: String,
    val accountId: String,
    val senderDeviceId: String,
    val tokenId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val nonce: String,
    val alertType: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val emergencyMessage: String = "GuardianGrid BLE Emergency Alert",
    val signature: String = ""
) {
    /**
     * Builds the deterministic canonical string for signature calculation.
     * Matches the Supabase Edge Function's buildBleCanonicalString implementation exactly.
     */
    fun toCanonicalString(): String {
        val latStr = latitude?.toString() ?: ""
        val lonStr = longitude?.toString() ?: ""
        val accStr = accuracyMeters?.toString() ?: ""
        return "v1|$alertId|$accountId|$senderDeviceId|$tokenId|$issuedAt|$expiresAt|$nonce|$alertType|$latStr|$lonStr|$accStr|$emergencyMessage"
    }

    /**
     * Computes the HMAC-SHA256 signature in lowercase hexadecimal format using the short-lived token secret.
     */
    fun calculateSignature(tokenSecret: String): String {
        val canonical = toCanonicalString()
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val sigBytes = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
        return sigBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies whether this packet's signature matches the expected HMAC-SHA256 signature
     * in constant-time comparison to prevent timing attacks.
     */
    fun verifySignature(tokenSecret: String): Boolean {
        val expected = calculateSignature(tokenSecret)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            signature.toByteArray(StandardCharsets.UTF_8)
        )
    }

    /**
     * Validates basic packet sanity (size, protocol version, non-empty mandatory identifiers).
     */
    fun validateBasicStructure(): Result<Unit> {
        if (protocolVersion != 1) {
            return Result.failure(IllegalArgumentException("Unsupported protocol version: $protocolVersion"))
        }
        if (alertId.isBlank()) {
            return Result.failure(IllegalArgumentException("alertId cannot be blank"))
        }
        if (accountId.isBlank()) {
            return Result.failure(IllegalArgumentException("accountId cannot be blank"))
        }
        if (senderDeviceId.isBlank()) {
            return Result.failure(IllegalArgumentException("senderDeviceId cannot be blank"))
        }
        if (tokenId.isBlank()) {
            return Result.failure(IllegalArgumentException("tokenId cannot be blank"))
        }
        if (nonce.isBlank()) {
            return Result.failure(IllegalArgumentException("nonce cannot be blank"))
        }
        if (signature.isBlank()) {
            return Result.failure(IllegalArgumentException("signature cannot be blank"))
        }
        if (expiresAt <= issuedAt) {
            return Result.failure(IllegalArgumentException("expiresAt must be after issuedAt"))
        }
        val estimatedSize = toJson().toByteArray(StandardCharsets.UTF_8).size
        if (estimatedSize > MAX_PACKET_BYTES) {
            return Result.failure(IllegalArgumentException("Packet size ($estimatedSize bytes) exceeds maximum allowable ($MAX_PACKET_BYTES bytes)"))
        }
        return Result.success(Unit)
    }

    /**
     * Serializes this packet into compact JSON.
     */
    fun toJson(): String {
        val latStr = latitude?.toString() ?: "null"
        val lonStr = longitude?.toString() ?: "null"
        val accStr = accuracyMeters?.toString() ?: "null"
        return """{"protocolVersion":$protocolVersion,"alertId":"${escapeJson(alertId)}","accountId":"${escapeJson(accountId)}","senderDeviceId":"${escapeJson(senderDeviceId)}","tokenId":"${escapeJson(tokenId)}","issuedAt":$issuedAt,"expiresAt":$expiresAt,"nonce":"${escapeJson(nonce)}","alertType":"${escapeJson(alertType)}","latitude":$latStr,"longitude":$lonStr,"accuracyMeters":$accStr,"emergencyMessage":"${escapeJson(emergencyMessage)}","signature":"${escapeJson(signature)}"}"""
    }

    companion object {
        const val MAX_PACKET_BYTES = 4096

        private fun escapeJson(str: String): String =
            str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

        fun fromJson(json: String): BleEmergencyAlertPacket {
            fun extractString(key: String): String {
                val regex = "\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"".toRegex()
                val match = regex.find(json) ?: return ""
                return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r")
            }

            fun extractLong(key: String, defaultVal: Long = 0L): Long {
                val regex = "\"$key\"\\s*:\\s*(-?\\d+)".toRegex()
                return regex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: defaultVal
            }

            fun extractInt(key: String, defaultVal: Int = 1): Int {
                val regex = "\"$key\"\\s*:\\s*(-?\\d+)".toRegex()
                return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: defaultVal
            }

            fun extractDouble(key: String): Double? {
                val regex = "\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)".toRegex()
                return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
            }

            fun extractFloat(key: String): Float? {
                val regex = "\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)".toRegex()
                return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
            }

            return BleEmergencyAlertPacket(
                protocolVersion = extractInt("protocolVersion", 1),
                alertId = extractString("alertId"),
                accountId = extractString("accountId"),
                senderDeviceId = extractString("senderDeviceId"),
                tokenId = extractString("tokenId"),
                issuedAt = extractLong("issuedAt"),
                expiresAt = extractLong("expiresAt"),
                nonce = extractString("nonce"),
                alertType = extractString("alertType"),
                latitude = extractDouble("latitude"),
                longitude = extractDouble("longitude"),
                accuracyMeters = extractFloat("accuracyMeters"),
                emergencyMessage = extractString("emergencyMessage"),
                signature = extractString("signature")
            )
        }
    }
}

/**
 * Short-lived backend-issued signing token held securely on the Sender Phone.
 * Used to sign offline emergency packets when internet connectivity is completely lost.
 */
data class BleEmergencyToken(
    val tokenId: String,
    val tokenSecret: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val accountId: String,
    val deviceId: String
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = nowEpochMs >= expiresAt

    fun toJson(): String {
        return """{"tokenId":"$tokenId","tokenSecret":"$tokenSecret","issuedAt":$issuedAt,"expiresAt":$expiresAt,"accountId":"$accountId","deviceId":"$deviceId"}"""
    }

    companion object {
        fun fromJson(json: String): BleEmergencyToken? {
            fun extractString(key: String): String =
                "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex().find(json)?.groupValues?.get(1) ?: ""
            fun extractLong(key: String): Long =
                "\"$key\"\\s*:\\s*(-?\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val tokenId = extractString("tokenId")
            val tokenSecret = extractString("tokenSecret")
            val accountId = extractString("accountId")
            val deviceId = extractString("deviceId")
            val issuedAt = extractLong("issuedAt")
            val expiresAt = extractLong("expiresAt")
            if (tokenId.isBlank() || tokenSecret.isBlank()) return null
            return BleEmergencyToken(tokenId, tokenSecret, issuedAt, expiresAt, accountId, deviceId)
        }
    }
}

/**
 * Acknowledgement response returned by the Supabase backend to the Relay Phone.
 */
data class BleRelayAcknowledgement(
    val ok: Boolean,
    val alertId: String,
    val status: String,
    val smsDispatched: Boolean,
    val smsError: String? = null,
    val acknowledged: Boolean = true,
    val duplicate: Boolean = false
)

/**
 * Relay phone transmission forwarding states.
 */
enum class BleForwardStatus {
    PENDING,
    IN_FLIGHT,
    FORWARDED,
    FAILED_RETRYING,
    ACKNOWLEDGED
}

/**
 * Persistent item in the Relay Phone's store-and-forward queue.
 */
data class BleRelayQueueItem(
    val queueId: String,
    val packet: BleEmergencyAlertPacket,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val forwardStatus: BleForwardStatus = BleForwardStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null
)
