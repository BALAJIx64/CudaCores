package com.guardiangrid.modules.motion.fusion

import com.guardiangrid.core.interfaces.FusionResult
import com.guardiangrid.core.interfaces.MultimodalFusionEngine
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Production implementation of [MultimodalFusionEngine].
 * Implements mathematically consistent multimodal sensor fusion, temporal cross-modal correlation,
 * confidence-weighted escalation, and false-alarm suppression.
 *
 * @param escalationThreshold Threshold for confirmed escalation (default 0.80)
 * @param strongAudioThreshold Threshold for audio-only escalation without motion confirmation (default 0.85)
 * @param defaultTimeWindowMs Sliding temporal correlation window duration (default 3000ms)
 * @param clock Injectable time provider for deterministic testing (defaults to System.currentTimeMillis)
 */
class MultimodalFusionEngineImpl(
    val escalationThreshold: Float = 0.80f,
    val strongAudioThreshold: Float = 0.85f,
    val defaultTimeWindowMs: Long = 3000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : MultimodalFusionEngine {

    override fun evaluateSignals(
        signals: List<DistressSignal>,
        timeWindowMs: Long
    ): FusionResult {
        if (signals.isEmpty()) {
            return FusionResult(
                combinedConfidence = 0.0f,
                isEscalationTriggered = false,
                contributingSignals = emptyList(),
                explanation = "No distress signals present in evaluation window."
            )
        }

        val windowDuration = if (timeWindowMs > 0) timeWindowMs else defaultTimeWindowMs
        val now = clock()
        val windowCutoff = now - windowDuration

        // Exclude expired signals (older than windowCutoff relative to now)
        // and future timestamps (> 1000ms in the future)
        val activeSignals = signals.filter {
            it.timestampEpochMs in windowCutoff..(now + 1000L)
        }

        if (activeSignals.isEmpty()) {
            return FusionResult(
                combinedConfidence = 0.0f,
                isEscalationTriggered = false,
                contributingSignals = emptyList(),
                explanation = "All signals expired outside sliding time window (${windowDuration}ms) relative to current time."
            )
        }

        // 1. Manual SOS or Silent Activation (Highest Priority - Immediate Escalation)
        val manualSignal = activeSignals.firstOrNull {
            it.type == DistressType.MANUAL_SOS || it.type == DistressType.SILENT_ACTIVATION
        }
        if (manualSignal != null) {
            return FusionResult(
                combinedConfidence = 1.0f,
                isEscalationTriggered = true,
                contributingSignals = listOf(manualSignal),
                explanation = "Manual SOS or Silent Activation initiated by user via ${manualSignal.source}. Immediate emergency escalation triggered."
            )
        }

        // Partition signals into Audio and Motion categories
        val audioSignals = activeSignals.filter {
            it.type == DistressType.SCREAM
        }
        val motionSignals = activeSignals.filter {
            it.type == DistressType.MOTION_ANOMALY || it.type == DistressType.FALL_IMPACT
        }

        val bestAudio = audioSignals.maxByOrNull { it.confidence }
        val bestMotion = motionSignals.maxByOrNull { it.confidence }

        // 2. Correlated Multimodal Trigger (Audio + Motion)
        if (bestAudio != null && bestMotion != null) {
            val deltaMs = abs(bestAudio.timestampEpochMs - bestMotion.timestampEpochMs)

            // Verify that audio and motion signals genuinely overlap within the correlation window
            if (deltaMs <= windowDuration) {
                val cAudio = sanitize(bestAudio.confidence)
                val cMotion = sanitize(bestMotion.confidence)

                // Mathematically consistent independent evidence fusion:
                // P(A U M) = 1 - (1 - P(A)) * (1 - P(M))
                val baseFused = 1.0f - (1.0f - cAudio) * (1.0f - cMotion)

                // Temporal correlation synergy: rewarded only when both signals have non-trivial confidence (>= 0.40)
                val temporalProximity = (1.0f - (deltaMs.toFloat() / windowDuration.toFloat())).coerceIn(0.0f, 1.0f)
                val synergyBoost = if (cAudio >= 0.40f && cMotion >= 0.40f) {
                    0.05f * temporalProximity * sqrt(cAudio * cMotion)
                } else {
                    0.0f
                }

                val combined = sanitize(baseFused + synergyBoost).coerceIn(0.0f, 1.0f)

                // Escalation requires combined confidence >= threshold AND at least one signal with meaningful confidence (>= 0.50)
                // to prevent two insignificant weak signals (e.g. 0.20 + 0.20) from escalating
                val atLeastOneSignificant = cAudio >= 0.50f || cMotion >= 0.50f
                val shouldEscalate = combined >= escalationThreshold && atLeastOneSignificant

                return if (shouldEscalate) {
                    FusionResult(
                        combinedConfidence = combined,
                        isEscalationTriggered = true,
                        contributingSignals = listOf(bestAudio, bestMotion),
                        explanation = "Multimodal Sensor Fusion: Significant temporal cross-correlation confirmed between ${bestAudio.type} (confidence %.2f) and ${bestMotion.type} (confidence %.2f) with Δt = ${deltaMs}ms <= ${windowDuration}ms. Combined confidence = %.2f >= %.2f. Immediate emergency escalation triggered.".format(
                            cAudio,
                            cMotion,
                            combined,
                            escalationThreshold
                        )
                    )
                } else {
                    FusionResult(
                        combinedConfidence = combined,
                        isEscalationTriggered = false,
                        contributingSignals = listOf(bestAudio, bestMotion),
                        explanation = "Multimodal Sensor Fusion: Sub-threshold cross-modal evidence (${bestAudio.type} confidence %.2f, ${bestMotion.type} confidence %.2f, Δt = ${deltaMs}ms). Combined confidence = %.2f < %.2f. Escalation withheld.".format(
                            cAudio,
                            cMotion,
                            combined,
                            escalationThreshold
                        )
                    )
                }
            }
            // If deltaMs > windowDuration, signals do not overlap within window; fall through to evaluate independently
        }

        // 3. Strong Distress Audio Evidence Alone
        // Strong audio evidence must be capable of escalation without mandatory motion confirmation
        if (bestAudio != null) {
            val audioConf = sanitize(bestAudio.confidence)
            if (audioConf >= strongAudioThreshold) {
                return FusionResult(
                    combinedConfidence = audioConf,
                    isEscalationTriggered = true,
                    contributingSignals = listOf(bestAudio),
                    explanation = "Strong acoustic distress detected (${bestAudio.type} with confidence %.2f >= %.2f). Escalating emergency without mandatory motion confirmation.".format(
                        audioConf,
                        strongAudioThreshold
                    )
                )
            } else {
                // Moderate audio alone: starts verification, but does not immediately trigger full confirmed escalation
                val moderatedConfidence = sanitize(audioConf * 0.90f).coerceIn(0.0f, 0.79f)
                return FusionResult(
                    combinedConfidence = moderatedConfidence,
                    isEscalationTriggered = false,
                    contributingSignals = listOf(bestAudio),
                    explanation = "Isolated moderate acoustic distress detected (${bestAudio.type} confidence %.2f). Awaiting motion corroboration or verification countdown timeout.".format(
                        audioConf
                    )
                )
            }
        }

        // 4. Isolated Motion Anomaly Alone (phone drop, random shake, pocket movement)
        // Must NOT automatically equate a random phone shake or drop to an attack
        if (bestMotion != null) {
            val motionConf = sanitize(bestMotion.confidence)
            val cappedConfidence = when (bestMotion.type) {
                DistressType.FALL_IMPACT -> sanitize(motionConf * 0.75f).coerceIn(0.0f, 0.68f)
                DistressType.MOTION_ANOMALY -> sanitize(motionConf * 0.65f).coerceIn(0.0f, 0.58f)
                else -> 0.40f
            }

            return FusionResult(
                combinedConfidence = cappedConfidence,
                isEscalationTriggered = false,
                contributingSignals = listOf(bestMotion),
                explanation = "Isolated motion anomaly detected (${bestMotion.type} raw confidence %.2f, discounted to %.2f) without acoustic corroboration. Escalation suppressed to prevent false alarm from benign movement or phone drop.".format(
                    motionConf,
                    cappedConfidence
                )
            )
        }

        // 5. Unclassified or fallback signals
        val highest = activeSignals.maxByOrNull { it.confidence } ?: activeSignals.first()
        val conf = sanitize(highest.confidence * 0.50f).coerceIn(0.0f, 0.49f)
        return FusionResult(
            combinedConfidence = conf,
            isEscalationTriggered = false,
            contributingSignals = activeSignals,
            explanation = "Signals below escalation threshold (max confidence %.2f). No emergency escalation triggered.".format(conf)
        )
    }

    private fun sanitize(value: Float): Float {
        return if (value.isNaN() || value.isInfinite()) 0.0f else value.coerceIn(0.0f, 1.0f)
    }
}
