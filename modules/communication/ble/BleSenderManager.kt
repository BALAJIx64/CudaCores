package com.guardiangrid.modules.communication.ble

import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.core.models.BleEmergencyToken
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Storage manager for caching short-lived backend-issued BLE signing tokens.
 * Enables the sender phone to sign emergency packets completely offline during network blackouts.
 */
class BleTokenStore(private val storageDir: File) {
    private val tokenFile: File = File(storageDir, "ble_emergency_token.json")
    private val cachedTokenRef = AtomicReference<BleEmergencyToken?>(null)

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        if (tokenFile.exists()) {
            runCatching {
                val json = tokenFile.readText()
                val token = BleEmergencyToken.fromJson(json)
                if (token != null && !token.isExpired()) {
                    cachedTokenRef.set(token)
                } else if (token != null && token.isExpired()) {
                    tokenFile.delete()
                    cachedTokenRef.set(null)
                }
            }
        }
    }

    @Synchronized
    fun saveToken(token: BleEmergencyToken) {
        if (!storageDir.exists()) storageDir.mkdirs()
        tokenFile.writeText(token.toJson())
        cachedTokenRef.set(token)
    }

    fun getActiveToken(): BleEmergencyToken? {
        val current = cachedTokenRef.get()
        if (current != null && current.isExpired()) {
            cachedTokenRef.set(null)
            tokenFile.delete()
            return null
        }
        return current
    }

    @Synchronized
    fun clearToken() {
        cachedTokenRef.set(null)
        if (tokenFile.exists()) tokenFile.delete()
    }
}

/**
 * Android BLE Sender Manager.
 * Responsibilities:
 * 1. Generate cryptographically random alert ID and nonce for every incident.
 * 2. Validate Supabase Auth account UUID format.
 * 3. Construct and sign versioned BleEmergencyAlertPacket using cached token.
 * 4. Broadcast packet over BLE GATT service without exposing server secrets.
 */
class BleSenderManager(
    private val tokenStore: BleTokenStore,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val activePacketRef = AtomicReference<BleEmergencyAlertPacket?>(null)
    private var isBroadcasting = false

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FE01-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_ALERT_PACKET: UUID = UUID.fromString("0000FE02-0000-1000-8000-00805F9B34FB")
    }

    /**
     * Checks if a valid, unexpired signing token is cached for emergency activation.
     */
    fun hasValidCachedToken(): Boolean = tokenStore.getActiveToken() != null

    /**
     * Gets the currently cached token or null.
     */
    fun getCachedToken(): BleEmergencyToken? = tokenStore.getActiveToken()

    /**
     * Updates the cached token (typically called when online after fetching from Supabase).
     */
    fun updateCachedToken(token: BleEmergencyToken) {
        tokenStore.saveToken(token)
    }

    /**
     * Creates and cryptographically signs a new versioned BLE emergency-alert packet.
     * Operates completely offline using the cached short-lived token.
     */
    fun createEmergencyAlertPacket(
        accountId: String,
        senderDeviceId: String,
        alertType: String = "MANUAL_SOS",
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyMeters: Float? = null,
        emergencyMessage: String = "GuardianGrid BLE Emergency Alert"
    ): Result<BleEmergencyAlertPacket> {
        // Enforce valid UUID format for accountId (Supabase Auth user ID)
        val accountUuid = runCatching { UUID.fromString(accountId.trim()) }.getOrNull()
        if (accountUuid == null) {
            return Result.failure(IllegalArgumentException("accountId must be a valid Supabase Auth UUID, not email or name: $accountId"))
        }

        val token = tokenStore.getActiveToken()
            ?: return Result.failure(IllegalStateException("No valid, unexpired BLE emergency token available. Sync with GuardianGrid backend while online."))

        if (token.accountId != accountId.trim()) {
            return Result.failure(IllegalStateException("Cached token account (${token.accountId}) does not match current user ($accountId)"))
        }

        if (token.deviceId != senderDeviceId.trim()) {
            return Result.failure(IllegalStateException("Cached token device (${token.deviceId}) does not match sender device ($senderDeviceId)"))
        }

        val alertId = UUID.randomUUID().toString()
        val nonceBytes = ByteArray(16)
        secureRandom.nextBytes(nonceBytes)
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }

        val now = System.currentTimeMillis()
        val unsignedPacket = BleEmergencyAlertPacket(
            protocolVersion = 1,
            alertId = alertId,
            accountId = accountId.trim(),
            senderDeviceId = senderDeviceId.trim(),
            tokenId = token.tokenId,
            issuedAt = now,
            expiresAt = token.expiresAt,
            nonce = nonce,
            alertType = alertType,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            emergencyMessage = emergencyMessage,
            signature = ""
        )

        // Calculate cryptographic HMAC-SHA256 signature using cached token secret
        val signature = unsignedPacket.calculateSignature(token.tokenSecret)
        val signedPacket = unsignedPacket.copy(signature = signature)

        val validation = signedPacket.validateBasicStructure()
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull()!!)
        }

        return Result.success(signedPacket)
    }

    /**
     * Starts BLE GATT advertising and hosts the emergency characteristic for nearby relays.
     */
    @Synchronized
    fun startBroadcastingAlert(packet: BleEmergencyAlertPacket): Result<Unit> {
        val validation = packet.validateBasicStructure()
        if (validation.isFailure) return validation

        activePacketRef.set(packet)
        isBroadcasting = true
        return Result.success(Unit)
    }

    @Synchronized
    fun stopBroadcasting() {
        activePacketRef.set(null)
        isBroadcasting = false
    }

    fun isBroadcasting(): Boolean = isBroadcasting

    fun getActiveBroadcastPacket(): BleEmergencyAlertPacket? = activePacketRef.get()
}
