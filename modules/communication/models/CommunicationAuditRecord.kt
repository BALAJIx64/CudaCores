package com.guardiangrid.modules.communication.models

import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt

/**
 * Audit trail entry for tracking every emergency transmission attempt,
 * platform acceptance, retry, and contact acknowledgment.
 */
data class CommunicationAuditRecord(
    val recordId: String,
    val incidentId: String,
    val eventId: String,
    val channel: DeliveryChannel,
    val state: EmergencyDeliveryState,
    val attemptCount: Int,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val deliveredEpochMs: Long? = null,
    val acknowledgedEpochMs: Long? = null,
    val acknowledgedBy: String? = null,
    val messageId: String? = null,
    val recipient: String? = null,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val isDuplicateSuppressed: Boolean = false,
    val retryAfterSeconds: Int? = null
) {
    /**
     * Converts audit record into the core project DeliveryReceipt contract.
     */
    fun toDeliveryReceipt(): DeliveryReceipt = DeliveryReceipt(
        channel = channel,
        status = state.toDeliveryStatus(),
        timestampEpochMs = timestampEpochMs,
        messageId = messageId,
        recipient = recipient,
        latencyMs = latencyMs,
        errorMessage = errorMessage
    )
}
