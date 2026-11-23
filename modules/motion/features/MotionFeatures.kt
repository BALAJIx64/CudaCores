package com.guardiangrid.modules.motion.features

/**
 * Extracted kinematic and statistical features across a sliding time window of motion samples.
 */
data class MotionFeatures(
    val timestampEpochMs: Long,
    val sampleCount: Int,
    val windowDurationMs: Long,
    val currentMagnitude: Float,
    val peakMagnitude: Float,
    val minMagnitude: Float,
    val meanMagnitude: Float,
    val varianceMagnitude: Float,
    val currentAngularSpeed: Float,
    val peakAngularSpeed: Float,
    val meanAngularSpeed: Float,
    val currentJerk: Float,
    val peakJerk: Float,
    val directionReversals: Int,
    val freefallDurationMs: Long,
    val hasFreefallPrecedingImpact: Boolean,
    val dynamicAcceleration: Float
)
