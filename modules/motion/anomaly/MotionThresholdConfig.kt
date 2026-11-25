package com.guardiangrid.modules.motion.anomaly

/**
 * Centralized, configurable thresholds for conservative motion anomaly and impact detection.
 * Tuned to suppress ordinary handling, walking, desk placement, and isolated bumps while
 * preserving genuine fall-impact and sustained violent-movement detection.
 */
data class MotionThresholdConfig(
    val fallImpactThreshold: Float = 30.0f,
    val standaloneSevereImpactThreshold: Float = 42.0f,
    val fallFreefallThreshold: Float = 2.8f,
    val fallFreefallMinDurationMs: Long = 140L,
    val fallFreefallMaxPrecedingMs: Long = 800L,
    val violentShakeVarianceThreshold: Float = 18.0f,
    val violentShakeReversalThreshold: Int = 6,
    val violentAngularSpeedThreshold: Float = 5.0f,
    val snatchJerkThreshold: Float = 220.0f,
    val snatchMagnitudeThreshold: Float = 32.0f,
    val minConsecutiveAnomalySamples: Int = 2,
    val anomalyCooldownMs: Long = 2000L,
    val sensitivity: Float = 1.0f // 0.5f (very conservative) to 1.5f (sensitive)
) {
    /**
     * Compute effective threshold inversely scaled by sensitivity (higher sensitivity lowers required threshold).
     */
    fun effectiveImpactThreshold(): Float = fallImpactThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveStandaloneImpactThreshold(): Float = standaloneSevereImpactThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveShakeVarianceThreshold(): Float = violentShakeVarianceThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveAngularSpeedThreshold(): Float = violentAngularSpeedThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveSnatchJerkThreshold(): Float = snatchJerkThreshold / sensitivity.coerceIn(0.5f, 2.0f)
    fun effectiveSnatchMagnitudeThreshold(): Float = snatchMagnitudeThreshold / sensitivity.coerceIn(0.5f, 2.0f)
}
