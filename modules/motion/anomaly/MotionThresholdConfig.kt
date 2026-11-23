package com.guardiangrid.modules.motion.anomaly

/**
 * Configurable thresholds for motion anomaly and impact detection.
 * Supports runtime sensitivity tuning.
 */
data class MotionThresholdConfig(
    val fallImpactThreshold: Float = 24.0f,
    val fallFreefallThreshold: Float = 3.0f,
    val fallFreefallMaxPrecedingMs: Long = 800L,
    val violentShakeVarianceThreshold: Float = 14.0f,
    val violentShakeReversalThreshold: Int = 4,
    val violentAngularSpeedThreshold: Float = 3.8f,
    val snatchJerkThreshold: Float = 95.0f,
    val snatchMagnitudeThreshold: Float = 21.0f,
    val sensitivity: Float = 1.0f // 0.5f (low) to 1.5f (high)
) {
    /**
     * Compute effective threshold inversely scaled by sensitivity (higher sensitivity lowers required threshold).
     */
    fun effectiveImpactThreshold(): Float = fallImpactThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveShakeVarianceThreshold(): Float = violentShakeVarianceThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveAngularSpeedThreshold(): Float = violentAngularSpeedThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveSnatchJerkThreshold(): Float = snatchJerkThreshold / sensitivity.coerceIn(0.5f, 2.0f)
}
