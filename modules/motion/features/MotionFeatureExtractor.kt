package com.guardiangrid.modules.motion.features

import com.guardiangrid.core.models.MotionSample
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feature extractor maintaining a sliding window of motion samples to compute
 * kinematic derivatives (magnitude, jerk, angular speed) and statistical window metrics.
 * Safely handles empty input, insufficient samples, duplicate/out-of-order timestamps, and division by zero.
 */
class MotionFeatureExtractor(
    private val windowCapacityMs: Long = 1000L,
    private val baselineCalibrator: MotionBaselineCalibrator = MotionBaselineCalibrator(),
    private val freefallMaxMagnitude: Float = 2.8f,
    private val freefallMinDurationMs: Long = 140L
) {
    private val window = LinkedList<MotionSample>()
    private var lastSample: MotionSample? = null
    private var freefallStartTimeMs: Long? = null
    private var lastFreefallEventMs: Long? = null

    @Synchronized
    fun processSample(sample: MotionSample): MotionFeatures {
        // Drop samples with timestamps that go backwards in time (out-of-order anomalies)
        if (window.isNotEmpty() && sample.timestampEpochMs < window.last().timestampEpochMs) {
            return computeCurrentFeatures(window.last())
        }

        window.addLast(sample)

        // Evict samples older than window capacity
        val windowStartCutoff = sample.timestampEpochMs - windowCapacityMs
        while (window.isNotEmpty() && window.first().timestampEpochMs < windowStartCutoff) {
            window.removeFirst()
        }

        return computeCurrentFeatures(sample)
    }

    private fun computeCurrentFeatures(sample: MotionSample): MotionFeatures {
        val baselineG = baselineCalibrator.baselineGravity
        val currentMag = if (sample.magnitude.isNaN() || sample.magnitude.isInfinite()) 0f else sample.magnitude
        val dynamicAcc = abs(currentMag - baselineG)

        val rawAngular = sqrt(sample.gx * sample.gx + sample.gy * sample.gy + sample.gz * sample.gz)
        val currentAngular = if (rawAngular.isNaN() || rawAngular.isInfinite()) 0f else rawAngular

        // Jerk computation (d|a| / dt) with strict division-by-zero protection
        val prev = lastSample
        val dtSec = if (prev != null && sample.timestampEpochMs > prev.timestampEpochMs) {
            (sample.timestampEpochMs - prev.timestampEpochMs) / 1000.0f
        } else {
            0f
        }

        val currentJerk = if (prev != null && dtSec > 0.001f && !dtSec.isNaN() && !dtSec.isInfinite()) {
            val deltaA = abs(currentMag - prev.magnitude)
            val jerkVal = deltaA / dtSec
            if (jerkVal.isNaN() || jerkVal.isInfinite()) 0f else jerkVal
        } else {
            0f
        }

        // Freefall tracking (< freefallMaxMagnitude)
        val isFreefallNow = currentMag in 0.0f..freefallMaxMagnitude
        if (isFreefallNow) {
            if (freefallStartTimeMs == null) {
                freefallStartTimeMs = sample.timestampEpochMs
            }
        } else {
            if (freefallStartTimeMs != null) {
                val duration = sample.timestampEpochMs - freefallStartTimeMs!!
                if (duration in freefallMinDurationMs..2000L) {
                    lastFreefallEventMs = sample.timestampEpochMs
                }
                freefallStartTimeMs = null
            }
        }

        val freefallDuration = if (freefallStartTimeMs != null && sample.timestampEpochMs >= freefallStartTimeMs!!) {
            sample.timestampEpochMs - freefallStartTimeMs!!
        } else {
            0L
        }

        // Did freefall occur in recent window (within last 800ms preceding this sample)
        val hasFreefallPreceding = lastFreefallEventMs != null &&
                (sample.timestampEpochMs - lastFreefallEventMs!!) in 0..800L

        // Window statistics
        var peakMag = currentMag
        var minMag = currentMag
        var sumMag = 0f
        var peakAngular = currentAngular
        var sumAngular = 0f
        var peakJerk = currentJerk
        var reversals = 0
        var lastDelta = 0f

        val count = window.size
        var prevInWindow: MotionSample? = null

        if (count > 0) {
            peakMag = 0f
            minMag = Float.MAX_VALUE
            for (s in window) {
                val mag = if (s.magnitude.isNaN() || s.magnitude.isInfinite()) 0f else s.magnitude
                if (mag > peakMag) peakMag = mag
                if (mag < minMag) minMag = mag
                sumMag += mag

                val ang = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
                val safeAng = if (ang.isNaN() || ang.isInfinite()) 0f else ang
                if (safeAng > peakAngular) peakAngular = safeAng
                sumAngular += safeAng

                if (prevInWindow != null && s.timestampEpochMs > prevInWindow.timestampEpochMs) {
                    val dt = (s.timestampEpochMs - prevInWindow.timestampEpochMs) / 1000f
                    if (dt > 0.001f && !dt.isNaN() && !dt.isInfinite()) {
                        val j = abs(mag - prevInWindow.magnitude) / dt
                        if (!j.isNaN() && !j.isInfinite() && j > peakJerk) peakJerk = j
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
        }

        val meanMag = if (count > 0) sumMag / count else currentMag
        val meanAngular = if (count > 0) sumAngular / count else currentAngular

        // Variance of magnitude (handling single or zero samples)
        var sumVar = 0f
        if (count > 1) {
            for (s in window) {
                val diff = s.magnitude - meanMag
                sumVar += diff * diff
            }
        }
        val varianceMag = if (count > 1) sumVar / count else 0f

        val windowDuration = if (window.size > 1) {
            sample.timestampEpochMs - window.first().timestampEpochMs
        } else {
            0L
        }

        lastSample = sample

        return MotionFeatures(
            timestampEpochMs = sample.timestampEpochMs,
            sampleCount = count,
            windowDurationMs = windowDuration,
            currentMagnitude = currentMag,
            peakMagnitude = if (peakMag.isNaN()) currentMag else peakMag,
            minMagnitude = if (minMag == Float.MAX_VALUE || minMag.isNaN()) currentMag else minMag,
            meanMagnitude = if (meanMag.isNaN()) currentMag else meanMag,
            varianceMagnitude = if (varianceMag.isNaN()) 0f else varianceMag,
            currentAngularSpeed = currentAngular,
            peakAngularSpeed = if (peakAngular.isNaN()) currentAngular else peakAngular,
            meanAngularSpeed = if (meanAngular.isNaN()) currentAngular else meanAngular,
            currentJerk = currentJerk,
            peakJerk = if (peakJerk.isNaN()) currentJerk else peakJerk,
            directionReversals = reversals,
            freefallDurationMs = freefallDuration,
            hasFreefallPrecedingImpact = hasFreefallPreceding || (freefallDuration >= freefallMinDurationMs),
            dynamicAcceleration = if (dynamicAcc.isNaN()) 0f else dynamicAcc
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
