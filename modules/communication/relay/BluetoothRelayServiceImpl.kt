package com.guardiangrid.modules.communication.relay

import com.guardiangrid.core.interfaces.BluetoothRelayService
import com.guardiangrid.core.models.AlertPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Implementation of Bluetooth store-and-forward relay service.
 *
 * Provides the BLE beacon broadcasting and scanning foundation for
 * peer-to-peer distress propagation in extreme offline environments.
 */
class BluetoothRelayServiceImpl : BluetoothRelayService {

    private var active = false
    private val scannedBeaconsFlow = MutableSharedFlow<AlertPayload>(extraBufferCapacity = 32)
    private var lastBroadcastPayload: AlertPayload? = null

    override fun startBroadcastingRelay(payload: AlertPayload) {
        active = true
        lastBroadcastPayload = payload
        // Serializes payload into compact binary advertisement format
        val compactBeaconData = encodePayloadToCompactBeacon(payload)
    }

    override fun startRelayScanner(): Flow<AlertPayload> {
        active = true
        return scannedBeaconsFlow.asSharedFlow()
    }

    override fun stopRelay() {
        active = false
        lastBroadcastPayload = null
    }

    override fun isRelayActive(): Boolean = active

    /**
     * Testing / simulation hook to inject a received peer beacon.
     */
    suspend fun simulateReceivedBeacon(payload: AlertPayload) {
        scannedBeaconsFlow.emit(payload)
    }

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
