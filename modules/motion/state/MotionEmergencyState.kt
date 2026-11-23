package com.guardiangrid.modules.motion.state

import com.guardiangrid.core.models.EmergencyLevel

/**
 * Seven strict emergency lifecycle states required for multimodal distress intelligence.
 */
enum class MotionEmergencyState {
    /** Baseline operational state with active background sensors. */
    NORMAL,

    /** Isolated weak or uncorroborated anomaly detected; monitoring sensitivity raised. */
    SUSPECTED_DISTRESS,

    /** Distress detected; active verification countdown initiated with cancellation opportunity. */
    VERIFYING,

    /** Elevated risk state; multi-signal evidence corroborating or countdown expiring soon. */
    HIGH_CONCERN,

    /** Confirmed emergency! Outbound emergency dispatch and forensic evidence streaming triggered. */
    ALERTED,

    /** User authenticated cancellation of verification or active alert (false alarm handled). */
    CANCELLED,

    /** Emergency successfully handled, incident concluded and marked safe. */
    RESOLVED;

    /**
     * Map to shared core architecture [EmergencyLevel] for cross-module synchronization.
     */
    fun toEmergencyLevel(): EmergencyLevel {
        return when (this) {
            NORMAL -> EmergencyLevel.MONITORING
            SUSPECTED_DISTRESS -> EmergencyLevel.SUSPICIOUS
            VERIFYING, HIGH_CONCERN -> EmergencyLevel.VERIFYING
            ALERTED -> EmergencyLevel.CONFIRMED_EMERGENCY
            CANCELLED -> EmergencyLevel.CANCELLED
            RESOLVED -> EmergencyLevel.RESOLVED
        }
    }
}
