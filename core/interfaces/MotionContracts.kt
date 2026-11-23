package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.MotionSample
import kotlinx.coroutines.flow.Flow

/**
 * Result of multimodal correlation and false alarm management.
 */
data class FusionResult(
    val combinedConfidence: Float,
    val isEscalationTriggered: Boolean,
    val contributingSignals: List<DistressSignal>,
    val explanation: String
)

/**
 * Interface for accelerometer/gyroscope abnormal motion and impact detection.
 * Owned by Member 2 (modules/motion).
 */
interface MotionAnomalyDetector {
    /**
     * Start observing sensor hardware for falls, violent shaking, or abnormal movement.
     */
    fun startMonitoring(): Flow<DistressSignal>

    /**
     * Stop motion sensor listeners.
     */
    fun stopMonitoring()

    /**
     * Check whether motion sensors are actively streaming.
     */
    fun isMonitoring(): Boolean

    /**
     * Baseline sensor values to account for user-specific gait or pocket position.
     */
    fun calibrateBaseline()

    /**
     * Stream raw or sampled motion data for debugging and telemetry.
     */
    fun observeMotionSamples(): Flow<MotionSample>
}

/**
 * Multimodal verification engine that correlates audio and motion signals.
 * Owned by Member 2 (modules/motion).
 */
interface MultimodalFusionEngine {
    /**
     * Correlates temporally overlapping signals to filter false alarms and assess distress level.
     *
     * @param signals Incoming signals within the evaluation window
     * @param timeWindowMs Sliding window duration (default: 3000ms)
     * @return Fusion assessment with combined confidence and escalation decision
     */
    fun evaluateSignals(
        signals: List<DistressSignal>,
        timeWindowMs: Long = 3000L
    ): FusionResult
}
