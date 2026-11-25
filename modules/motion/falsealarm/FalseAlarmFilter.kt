package com.guardiangrid.modules.motion.falsealarm

import com.guardiangrid.core.models.DistressType
import com.guardiangrid.modules.motion.features.MotionFeatures
import kotlin.math.abs

/**
 * Intelligent false alarm filter to distinguish benign daily smartphone movements
 * (normal walking, gentle pocket jostling, placing on a desk) from genuine violent distress anomalies.
 */
class FalseAlarmFilter(
    private val deskPlacementMaxMag: Float = 18.0f,
    private val walkingPeakMagRange: ClosedFloatingPointRange<Float> = 8.0f..15.5f
) {
    /**
     * Evaluates whether a detected candidate motion anomaly is actually a benign false positive.
     *
     * @return true if the anomaly should be suppressed as a false alarm; false if it is a genuine anomaly.
     */
    fun isFalseAlarm(features: MotionFeatures, candidateType: DistressType): Boolean {
        // Fall impact requires genuine free-fall preceding impact or severe deceleration spike (> 28 m/s^2)
        if (candidateType == DistressType.FALL_IMPACT) {
            val isDeskPlacement = isPlacingOnDesk(features)
            if (isDeskPlacement) return true

            // If there was no preceding free-fall and impact is moderate, it's likely a table bump
            if (!features.hasFreefallPrecedingImpact && features.currentMagnitude < 26.0f) {
                return true
            }
        }

        // Violent struggle/shaking requires sustained agitation, not a single walking step or jogging
        if (candidateType == DistressType.MOTION_ANOMALY) {
            if (isNormalGaitWalking(features)) return true
            if (isRunningGait(features)) return true
            if (isSingleTransientJostle(features)) return true
        }

        return false
    }

    /**
     * Detects rhythmic jogging / running gait:
     * Periodic medium-high acceleration bounces with moderate rotation and no extreme snatch jerk or freefall struggle.
     */
    fun isRunningGait(features: MotionFeatures): Boolean {
        val isRunningRange = features.meanMagnitude in 9.0f..17.5f
        val boundedVariance = features.varianceMagnitude < 16.0f
        val boundedRotation = features.peakAngularSpeed < 3.4f
        val boundedJerk = features.peakJerk < 90.0f
        val noExtendedFreefall = features.freefallDurationMs < 80L

        return isRunningRange && boundedVariance && boundedRotation && boundedJerk && noExtendedFreefall
    }

    /**
     * Detects placing the phone on a table or desk:
     * Characterized by a single moderate deceleration spike with minimal angular rotation
     * and low variance without preceding free-fall.
     */
    fun isPlacingOnDesk(features: MotionFeatures): Boolean {
        val moderateImpact = features.currentMagnitude in 10.5f..deskPlacementMaxMag
        val lowRotation = features.peakAngularSpeed < 2.0f
        val noFreefall = !features.hasFreefallPrecedingImpact

        return moderateImpact && lowRotation && noFreefall
    }

    /**
     * Detects normal human gait / walking:
     * Rhythmic, moderate magnitude range, low jerk, and no free-fall or violent rotational velocity.
     */
    fun isNormalGaitWalking(features: MotionFeatures): Boolean {
        val isWalkingRange = features.meanMagnitude in walkingPeakMagRange
        val lowVariance = features.varianceMagnitude < 8.0f
        val moderateAngular = features.meanAngularSpeed < 2.5f
        val lowJerk = features.peakJerk < 50.0f
        val noFreefall = !features.hasFreefallPrecedingImpact

        return isWalkingRange && lowVariance && moderateAngular && lowJerk && noFreefall
    }

    /**
     * Detects a single brief pocket tap or isolated jostle:
     * Single brief peak but low window reversals and low angular speed.
     */
    fun isSingleTransientJostle(features: MotionFeatures): Boolean {
        return features.directionReversals <= 1 &&
                features.varianceMagnitude < 6.0f &&
                features.peakAngularSpeed < 2.0f
    }
}
