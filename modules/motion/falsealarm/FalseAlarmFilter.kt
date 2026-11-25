package com.guardiangrid.modules.motion.falsealarm

import com.guardiangrid.core.models.DistressType
import com.guardiangrid.modules.motion.features.MotionFeatures
import kotlin.math.abs

/**
 * Intelligent false alarm filter to distinguish benign daily smartphone movements
 * (normal walking, gentle pocket jostling, placing on a desk) from genuine violent distress anomalies.
 */
class FalseAlarmFilter(
    private val deskPlacementMaxMag: Float = 26.0f,
    private val walkingPeakMagRange: ClosedFloatingPointRange<Float> = 7.5f..18.0f
) {
    /**
     * Evaluates whether a detected candidate motion anomaly is actually a benign false positive.
     *
     * @return true if the anomaly should be suppressed as a false alarm; false if it is a genuine anomaly.
     */
    fun isFalseAlarm(features: MotionFeatures, candidateType: DistressType): Boolean {
        // Fall impact requires genuine free-fall preceding impact or extreme sustained/severe deceleration
        if (candidateType == DistressType.FALL_IMPACT) {
            if (isPlacingOnDesk(features)) return true
            if (isOrdinaryHandling(features)) return true

            // Without preceding free-fall, suppress moderate table bumps / isolated drops onto soft surfaces
            if (!features.hasFreefallPrecedingImpact && features.currentMagnitude < 36.0f) {
                return true
            }
        }

        // Violent struggle/shaking requires sustained multi-axis agitation, not walking, running, handling, or isolated spikes
        if (candidateType == DistressType.MOTION_ANOMALY) {
            if (isNormalGaitWalking(features)) return true
            if (isRunningGait(features)) return true
            if (isOrdinaryHandling(features)) return true
            if (isSingleTransientJostle(features)) return true
        }

        return false
    }

    /**
     * Detects rhythmic jogging / running gait:
     * Periodic medium-high acceleration bounces with moderate rotation and no extreme snatch jerk or freefall struggle.
     */
    fun isRunningGait(features: MotionFeatures): Boolean {
        val isRunningRange = features.meanMagnitude in 9.0f..19.5f
        val boundedVariance = features.varianceMagnitude < 24.0f
        val boundedRotation = features.peakAngularSpeed < 4.0f
        val boundedJerk = features.peakJerk < 150.0f
        val noExtendedFreefall = features.freefallDurationMs < 120L

        return isRunningRange && boundedVariance && boundedRotation && boundedJerk && noExtendedFreefall
    }

    /**
     * Detects placing the phone on a table or desk:
     * Characterized by a moderate deceleration spike with low-to-moderate rotation
     * and low window variance without preceding free-fall.
     */
    fun isPlacingOnDesk(features: MotionFeatures): Boolean {
        val moderateImpact = features.currentMagnitude in 10.5f..deskPlacementMaxMag
        val lowRotation = features.peakAngularSpeed < 3.2f
        val lowVariance = features.varianceMagnitude < 20.0f
        val noFreefall = !features.hasFreefallPrecedingImpact

        return moderateImpact && lowRotation && lowVariance && noFreefall
    }

    /**
     * Detects ordinary handheld usage (picking up, shifting grip, pocket insertion, texting):
     * Moderate mean acceleration, low reversal count, low variance, and no free-fall.
     */
    fun isOrdinaryHandling(features: MotionFeatures): Boolean {
        return features.meanMagnitude in 7.5f..16.5f &&
                features.varianceMagnitude < 20.0f &&
                features.directionReversals <= 4 &&
                features.peakAngularSpeed < 4.0f &&
                !features.hasFreefallPrecedingImpact
    }

    /**
     * Detects normal human gait / walking:
     * Rhythmic, moderate magnitude range, moderate jerk, and no free-fall or violent rotational velocity.
     */
    fun isNormalGaitWalking(features: MotionFeatures): Boolean {
        val isWalkingRange = features.meanMagnitude in walkingPeakMagRange
        val lowVariance = features.varianceMagnitude < 16.0f
        val moderateAngular = features.meanAngularSpeed < 3.5f
        val lowJerk = features.peakJerk < 120.0f
        val noFreefall = !features.hasFreefallPrecedingImpact

        return isWalkingRange && lowVariance && moderateAngular && lowJerk && noFreefall
    }

    /**
     * Detects an isolated spike, brief pocket tap, or transient jostle:
     * Low reversal count and low sustained window agitation without freefall.
     */
    fun isSingleTransientJostle(features: MotionFeatures): Boolean {
        return !features.hasFreefallPrecedingImpact &&
                features.directionReversals <= 2 &&
                features.varianceMagnitude < 20.0f &&
                features.peakAngularSpeed < 3.5f &&
                features.currentMagnitude < 36.0f
    }
}
