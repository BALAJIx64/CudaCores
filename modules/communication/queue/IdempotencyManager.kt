package com.guardiangrid.modules.communication.queue

import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe idempotency manager to suppress duplicate alert transmissions.
 *
 * Ensures that accidental re-triggers, retried loops, or simultaneous signals
 * for the same incident and channel do not spam emergency contacts or external APIs.
 */
class IdempotencyManager(
    private val maxCacheSize: Int = 1000
) {

    private val deliveredKeys = ConcurrentHashMap<String, DeliveryReceipt>()

    /**
     * Builds a unique idempotency key for an alert dispatch.
     */
    fun buildKey(incidentId: String, channel: DeliveryChannel, recipient: String? = null): String {
        return "$incidentId:$channel:${recipient ?: "default"}"
    }

    /**
     * Checks if this alert has already been successfully delivered or acknowledged.
     */
    fun isAlreadyDelivered(key: String): Boolean {
        return deliveredKeys.containsKey(key)
    }

    /**
     * Records a successful delivery or acknowledgment receipt.
     */
    fun recordDelivered(key: String, receipt: DeliveryReceipt) {
        if (deliveredKeys.size >= maxCacheSize) {
            // Evict oldest entries
            val firstKey = deliveredKeys.keys().nextElement()
            if (firstKey != null) {
                deliveredKeys.remove(firstKey)
            }
        }
        deliveredKeys[key] = receipt
    }

    /**
     * Retrieves the prior receipt if this dispatch was already delivered.
     */
    fun getExistingReceipt(key: String): DeliveryReceipt? = deliveredKeys[key]

    /**
     * Clears cached keys (for testing or reset).
     */
    fun clear() {
        deliveredKeys.clear()
    }
}
