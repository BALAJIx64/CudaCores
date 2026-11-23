package com.guardiangrid.modules.motion.features

import com.guardiangrid.core.models.MotionSample
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feature extractor maintaining a sliding window of motion samples to compute
 * kinematic derivatives (magnitude, jerk, angular speed) and statistical window metrics.
 */
class MotionFeatureExtractor(
    private val windowCapacityMs: Long = 1000L,
    private val baselineCalibrator: MotionBaselineCalibrator = MotionBaselineCalibrator()
) {
    private val window = LinkedList<MotionSample>()
    private var lastSample: MotionSample? = null
    private var freefallStartTimeMs: Long? = null
    private var lastFreefallEventMs: Long? = null

    @Synchronized
    fun processSample(sample: MotionSample): MotionFeatures {
        window.addLast(sample)

        // Evict samples older than window capacity
        val windowStartCutoff = sample.timestampEpochMs - windowCapacityMs
        while (window.isNotEmpty() && window.first.timestampEpochMs < windowStartCutoff) {
            window.removeFirst()
        }

        val baselineG = baselineCalibrator.baselineGravity
        val currentMag = sample.magnitude
        val dynamicAcc = abs(currentMag - baselineG)

        val currentAngular = sqrt(sample.gx * sample.gx + sample.gy * sample.gy + sample.gz * sample.gz)

        // Jerk computation (d|a| / dt)
        val prev = lastSample
        val dtSec = if (prev != null && sample.timestampEpochMs > prev.timestampEpochMs) {
            (sample.timestampEpochMs - prev.timestampEpochMs) / 1000.0f
        } else {
            0.02f // fallback default ~50Hz
        }
        val currentJerk = if (prev != null && dtSec > 0.001f) {
            abs(currentMag - prev.magnitude) / dtSec
        } else {
            0f
        }

        // Freefall tracking (< 3.0 m/s^2)
        val isFreefallNow = currentMag < 3.0f
        if (isFreefallNow) {
            if (freefallStartTimeMs == null) {
                freefallStartTimeMs = sample.timestampEpochMs
            }
        } else {
            if (freefallStartTimeMs != null) {
                val duration = sample.timestampEpochMs - freefallStartTimeMs!!
                if (duration >= 80L) { // At least 80ms in freefall to qualify
                    lastFreefallEventMs = sample.timestampEpochMs
                }
                freefallStartTimeMs = null
            }
        }

        val freefallDuration = if (freefallStartTimeMs != null) {
            sample.timestampEpochMs - freefallStartTimeMs!!
        } else {
            0L
        }

        // Did freefall occur in recent window (e.g. within last 800ms preceding this sample)?
        val hasFreefallPreceding = lastFreefallEventMs != null &&
                (sample.timestampEpochMs - lastFreefallEventMs!!) in 0..800L

        // Window statistics
        var peakMag = 0f
        var minMag = Float.MAX_VALUE
        var sumMag = 0f
        var peakAngular = 0f
        var sumAngular = 0f
        var peakJerk = 0f
        var reversals = 0
        var lastDelta = 0f

        val count = window.size
        var prevInWindow: MotionSample? = null

        for (s in window) {
            val mag = s.magnitude
            if (mag > peakMag) peakMag = mag
            if (mag < minMag) minMag = mag
            sumMag += mag

            val ang = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            if (ang > peakAngular) peakAngular = ang
            sumAngular += ang

            if (prevInWindow != null) {
                val dt = (s.timestampEpochMs - prevInWindow.timestampEpochMs) / 1000f
                if (dt > 0.001f) {
                    val j = abs(mag - prevInWindow.magnitude) / dt
                    if (j > peakJerk) peakJerk = j
                }

                val delta = mag - prevInWindow.magnitude
                if ((delta > 0.5f && lastDelta < -0.5f) || (delta < -0.5f && lastDelta > 0.5f)) {
                    reversals++
                }
                if (abs(delta) > 0.3f) {
                    lastDelta = delta
                }
            }
            prevInWindow = s
        }

        val meanMag = if (count > 0) sumMag / count else currentMag
        val meanAngular = if (count > 0) sumAngular / count else currentAngular

        // Variance of magnitude
        var sumVar = 0f
        for (s in window) {
            val diff = s.magnitude - meanMag
            sumVar += diff * diff
        }
        val varianceMag = if (count > 1) sumVar / count else 0f

        val windowDuration = if (window.isNotEmpty()) {
            sample.timestampEpochMs - window.first.timestampEpochMs
        } else {
            0L
        }

        lastSample = sample

        return MotionFeatures(
            timestampEpochMs = sample.timestampEpochMs,
            sampleCount = count,
            windowDurationMs = windowDuration,
            currentMagnitude = currentMag,
            peakMagnitude = peakMag,
            minMagnitude = if (minMag == Float.MAX_VALUE) currentMag else minMag,
            meanMagnitude = meanMag,
            varianceMagnitude = varianceMag,
            currentAngularSpeed = currentAngular,
            peakAngularSpeed = peakAngular,
            meanAngularSpeed = meanAngular,
            currentJerk = currentJerk,
            peakJerk = peakJerk,
            directionReversals = reversals,
            freefallDurationMs = freefallDuration,
            hasFreefallPrecedingImpact = hasFreefallPreceding || (freefallDuration >= 80L),
            dynamicAcceleration = dynamicAcc
        )
    }

    @Synchronized
    fun reset() {
        window.clear()
        lastSample = null
        freefallStartTimeMs = null
        lastFreefallEventMs = null
    }
}
