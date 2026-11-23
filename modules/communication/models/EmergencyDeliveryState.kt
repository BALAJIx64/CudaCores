package com.guardiangrid.modules.communication.models

import com.guardiangrid.core.models.DeliveryStatus

/**
 * Granular lifecycle states for emergency alert transmissions.
 *
 * State flow:
 * GENERATED -> QUEUED -> TRANSMISSION_ATTEMPTED -> DELIVERED -> ACKNOWLEDGED
 *                                               \-> FAILED
 *
 * Invariants:
 * 1. An alert is NEVER marked DELIVERED merely because an API request was initiated.
 * 2. Telegram API acceptance (DELIVERED) is strictly distinguished from human acknowledgment (ACKNOWLEDGED).
 */
enum class EmergencyDeliveryState(val description: String) {
    /**
     * Alert payload has been constructed from the incident trigger.
     */
    GENERATED("Emergency alert payload generated"),

    /**
     * Alert has been placed into the local offline persistence queue awaiting transmission.
     */
    QUEUED("Enqueued in local persistent storage"),

    /**
     * Outbound transmission has been initiated over network, SMS, or relay (in-flight).
     */
    TRANSMISSION_ATTEMPTED("Transmission initiated (in-flight)"),

    /**
     * Transmission successfully accepted by the receiving infrastructure
     * (e.g. Telegram Bot API HTTP 200 ok=true, or carrier SMS sent confirmation).
     * This represents delivery to the platform, NOT human acknowledgment.
     */
    DELIVERED("Delivered to communication channel / platform"),

    /**
     * A human recipient has actively confirmed receipt (e.g. clicked inline acknowledgment button,
     * replied with ACK keyword).
     */
    ACKNOWLEDGED("Human recipient actively acknowledged receipt"),

    /**
     * Transmission failed due to network error, invalid credentials, or exhausted retries.
     */
    FAILED("Transmission failed");

    /**
     * Maps the granular communication state to the core project DeliveryStatus contract.
     */
    fun toDeliveryStatus(): DeliveryStatus = when (this) {
        GENERATED -> DeliveryStatus.PENDING
        QUEUED -> DeliveryStatus.PENDING
        TRANSMISSION_ATTEMPTED -> DeliveryStatus.IN_FLIGHT
        DELIVERED -> DeliveryStatus.DELIVERED
        ACKNOWLEDGED -> DeliveryStatus.DELIVERED
        FAILED -> DeliveryStatus.FAILED_RETRYING
    }
}
