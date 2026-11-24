package com.guardiangrid.core.models

/**
 * Supported transmission channels for resilient emergency communication.
 */
enum class DeliveryChannel {
    TELEGRAM,
    SMS,
    INTERNET_API,
    BLUETOOTH_RELAY
}

/**
 * Status of an alert transmission over a given channel.
 */
enum class DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED_RETRYING,
    ABANDONED
}

/**
 * Outbound alert message payload.
 */
data class AlertPayload(
    val incidentId: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val distressType: DistressType,
    val confidence: Float,
    val location: LocationData? = null,
    val emergencyMessage: String = "GuardianGrid Emergency Alert",
    val audioSegmentPath: String? = null,
    val recipientContacts: List<EmergencyContact> = emptyList()
)

/**
 * Delivery acknowledgment receipt per communication channel.
 */
data class DeliveryReceipt(
    val channel: DeliveryChannel,
    val status: DeliveryStatus,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val recipient: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Queue item for persistent offline store-and-forward alert dispatch.
 */
data class QueuedEmergencyEvent(
    val queueId: String,
    val payload: AlertPayload,
    val targetChannel: DeliveryChannel,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val retryCount: Int = 0,
    val nextRetryEpochMs: Long = System.currentTimeMillis(),
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
