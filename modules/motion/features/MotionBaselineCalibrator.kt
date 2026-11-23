package com.guardiangrid.modules.motion.features

import com.guardiangrid.core.models.MotionSample
import kotlin.math.abs

/**
 * Computes running baseline characteristics (stationary gravity and user movement noise floor)
 * to adaptively calibrate thresholds and suppress false triggers.
 */
class MotionBaselineCalibrator(
    private val standardGravity: Float = 9.80665f
) {
    @Volatile var baselineGravity: Float = standardGravity
        private set

    @Volatile var baselineNoiseFloor: Float = 0.5f
        private set

    @Volatile var isCalibrated: Boolean = false
        private set

    private val calibrationSamples = mutableListOf<MotionSample>()
    private val maxCalibrationSamples = 100

    /**
     * Feed calibration sample during stationary / normal baseline period.
     */
    @Synchronized
    fun addCalibrationSample(sample: MotionSample) {
        calibrationSamples.add(sample)
        if (calibrationSamples.size >= maxCalibrationSamples) {
            computeBaseline()
        }
    }

    /**
     * Compute baseline from collected samples or manually reset to standard gravity.
     */
    @Synchronized
    fun computeBaseline() {
        if (calibrationSamples.isEmpty()) {
            baselineGravity = standardGravity
            baselineNoiseFloor = 0.5f
            isCalibrated = true
            return
        }

        var sumMag = 0f
        for (s in calibrationSamples) {
            sumMag += s.magnitude
        }
        val mean = sumMag / calibrationSamples.size

        var sumVariance = 0f
        for (s in calibrationSamples) {
            val diff = s.magnitude - mean
            sumVariance += diff * diff
        }
        val variance = sumVariance / calibrationSamples.size

        // Only accept if within reasonable physical gravity range (8.5 to 11.0 m/s^2)
        if (mean in 8.5f..11.0f) {
            baselineGravity = mean
            baselineNoiseFloor = kotlin.math.sqrt(variance).coerceIn(0.2f, 2.0f)
            isCalibrated = true
        } else {
            baselineGravity = standardGravity
            baselineNoiseFloor = 0.5f
            isCalibrated = true
        }
    }

    @Synchronized
    fun reset() {
        calibrationSamples.clear()
        baselineGravity = standardGravity
        baselineNoiseFloor = 0.5f
        isCalibrated = false
    }
}
