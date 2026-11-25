package com.guardiangrid.modules.communication.relay

import com.guardiangrid.core.interfaces.BluetoothRelayService
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.modules.communication.ble.BleRelayManager
import com.guardiangrid.modules.communication.ble.BleSenderManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Implementation of Bluetooth store-and-forward relay service.
 *
 * Provides:
 * 1. Compact binary BLE manufacturer beacon advertisement foundation (<= 24 bytes).
 * 2. Full GATT-based emergency alert packet transfer via [BleSenderManager] and [BleRelayManager].
 */
class BluetoothRelayServiceImpl(
    val bleSenderManager: BleSenderManager? = null,
    val bleRelayManager: BleRelayManager? = null,
    val defaultAccountId: String? = null,
    val deviceId: String = "DEV-${UUID.randomUUID()}",
    val bleEnabled: Boolean = false
) : BluetoothRelayService {

    private var active = false
    private val scannedBeaconsFlow = MutableSharedFlow<AlertPayload>(extraBufferCapacity = 32)
    private var lastBroadcastPayload: AlertPayload? = null
    private var lastBroadcastBlePacket: BleEmergencyAlertPacket? = null

    override fun startBroadcastingRelay(payload: AlertPayload) {
        active = true
        lastBroadcastPayload = payload
        // Serializes payload into compact binary advertisement format
        encodePayloadToCompactBeacon(payload)

        // Active BLE GATT broadcasting is postponed (bleEnabled = false in production).
        if (!bleEnabled) return

        // If BleSenderManager is configured with a valid token and account UUID, also broadcast signed GATT packet
        if (bleSenderManager != null && bleSenderManager.hasValidCachedToken()) {
            val accountId = defaultAccountId ?: bleSenderManager.getCachedToken()?.accountId
            if (accountId != null) {
                val result = bleSenderManager.createEmergencyAlertPacket(
                    accountId = accountId,
                    senderDeviceId = deviceId,
                    alertType = payload.distressType.name,
                    latitude = payload.location?.latitude,
                    longitude = payload.location?.longitude,
                    accuracyMeters = payload.location?.accuracyMeters,
                    emergencyMessage = payload.emergencyMessage
                )
                if (result.isSuccess) {
                    val packet = result.getOrNull()
                    if (packet != null) {
                        lastBroadcastBlePacket = packet
                        bleSenderManager.startBroadcastingAlert(packet)
                    }
                }
            }
        }
    }
    override fun startRelayScanner(): Flow<AlertPayload> {
        active = bleEnabled
        return scannedBeaconsFlow.asSharedFlow()
    }

    override fun stopRelay() {
        active = false
        lastBroadcastPayload = null
        lastBroadcastBlePacket = null
        bleSenderManager?.stopBroadcasting()
    }

    override fun isRelayActive(): Boolean = active

    fun getLastBroadcastBlePacket(): BleEmergencyAlertPacket? = lastBroadcastBlePacket

    /**
     * Testing / simulation hook to inject a received peer beacon.
     */
    suspend fun simulateReceivedBeacon(payload: AlertPayload) {
        scannedBeaconsFlow.emit(payload)
    }

    /**
     * Testing / simulation hook to inject a received peer BLE alert packet into the relay manager.
     */
    fun simulateReceivedBlePacket(packet: BleEmergencyAlertPacket) =
        bleRelayManager?.receiveAlertPacket(packet)

    /**
     * Compact binary encoding of emergency payload for BLE manufacturer data (<= 24 bytes).
     */
    fun encodePayloadToCompactBeacon(payload: AlertPayload): ByteArray {
        val buffer = ByteBuffer.allocate(24)
        // 4 bytes: Hash of incident ID
        buffer.putInt(payload.incidentId.hashCode())
        // 1 byte: Distress type ordinal
        buffer.put(payload.distressType.ordinal.toByte())
        // 1 byte: Confidence scaled to 0..100
        buffer.put((payload.confidence * 100).toInt().coerceIn(0, 100).toByte())
        // 8 bytes: Latitude (float or double)
        val lat = payload.location?.latitude?.toFloat() ?: 0.0f
        val lon = payload.location?.longitude?.toFloat() ?: 0.0f
        buffer.putFloat(lat)
        buffer.putFloat(lon)
        // 8 bytes: Timestamp millis
        buffer.putLong(payload.timestampEpochMs)
        return buffer.array()
    }
}
