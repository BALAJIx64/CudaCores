package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.QueuedEmergencyEvent
import kotlinx.coroutines.flow.Flow

/**
 * Multi-channel emergency alert dispatcher.
 * Owned by Member 3 (modules/communication).
 */
interface EmergencyCommunicator {
    /**
     * Dispatches an emergency alert across specified communication channels.
     * Implements fallback (e.g. Telegram -> SMS -> Bluetooth Relay).
     */
    suspend fun dispatchAlert(
        payload: AlertPayload,
        channels: List<DeliveryChannel> = listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS)
    ): List<DeliveryReceipt>

    /**
     * Checks channel availability (e.g., internet reachable, SIM carrier active, Bluetooth ON).
     */
    suspend fun checkChannelAvailability(channel: DeliveryChannel): Boolean
}

/**
 * Persistent offline queue and retry coordinator for emergency events.
 * Owned by Member 3 (modules/communication).
 */
interface DeliveryQueueManager {
    /**
     * Enqueue an emergency payload for resilient delivery with retries.
     */
    suspend fun enqueue(payload: AlertPayload, channel: DeliveryChannel): String

    /**
     * Observe queued items and their real-time delivery statuses.
     */
    fun observeQueue(): Flow<List<QueuedEmergencyEvent>>

    /**
     * Trigger immediate retry for all pending or failed events.
     */
    suspend fun retryPendingEvents(): List<DeliveryReceipt>

    /**
     * Clears all items in the persistent queue.
     */
    suspend fun clearQueue() {}

    /**
     * Deletes a specific item by queue ID.
     */
    suspend fun deleteQueuedItem(queueId: String) {}
}

/**
 * Interface for nearby-device Bluetooth store-and-forward relay.
 * Owned by Member 3 (modules/communication) as future extension foundation.
 */
interface BluetoothRelayService {
    /**
     * Broadcast an emergency SOS beacon to nearby GuardianGrid nodes.
     */
    fun startBroadcastingRelay(payload: AlertPayload)

    /**
     * Scan for nearby relay beacons and forward them when internet connectivity returns.
     */
    fun startRelayScanner(): Flow<AlertPayload>

    /**
     * Stop all Bluetooth relay operations.
     */
    fun stopRelay()

    /**
     * Query whether Bluetooth relay is actively operating.
     */
    fun isRelayActive(): Boolean
}
