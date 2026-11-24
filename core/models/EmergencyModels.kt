package com.guardiangrid.core.models

/**
 * Represents the current operational level of emergency escalation.
 */
enum class EmergencyLevel {
    IDLE,
    MONITORING,
    SUSPICIOUS,
    VERIFYING,
    CONFIRMED_EMERGENCY,
    RESOLVED,
    CANCELLED
}

/**
 * Geographic coordinates captured during emergency events.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val speedMps: Float = 0f,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val provider: String = "fused"
)

/**
 * Trusted contact designated to receive emergency alerts.
 */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val telegramChatId: String? = null,
    val isPrimary: Boolean = false,
    val relationship: String = "Emergency Contact"
)

/**
 * Complete record of an emergency incident.
 */
data class EmergencyIncident(
    val incidentId: String,
    val startTimeEpochMs: Long = System.currentTimeMillis(),
    var lastUpdatedEpochMs: Long = startTimeEpochMs,
    val level: EmergencyLevel = EmergencyLevel.VERIFYING,
    val triggeringSignals: List<DistressSignal> = emptyList(),
    val location: LocationData? = null,
    val audioSegments: List<AudioSegment> = emptyList(),
    val isSilent: Boolean = false,
    val notes: String = ""
) {
    val distressType: DistressType?
        get() = triggeringSignals.firstOrNull()?.type
}
