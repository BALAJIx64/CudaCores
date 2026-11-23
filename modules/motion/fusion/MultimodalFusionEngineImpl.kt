package com.guardiangrid.modules.motion.fusion

import com.guardiangrid.core.interfaces.FusionResult
import com.guardiangrid.core.interfaces.MultimodalFusionEngine
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import kotlin.math.abs

/**
 * Production implementation of [MultimodalFusionEngine].
 * Implements explainable multimodal sensor fusion, temporal cross-modal correlation,
 * confidence-weighted escalation, and false-alarm suppression.
 */
class MultimodalFusionEngineImpl(
    val escalationThreshold: Float = 0.80f,
    val strongAudioThreshold: Float = 0.85f,
    val defaultTimeWindowMs: Long = 3000L
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

        // Current evaluation time based on latest signal
        val latestTimestamp = signals.maxOf { it.timestampEpochMs }
        val windowCutoff = latestTimestamp - windowDuration

        // Filter signals strictly within temporal window (pruning stale / out-of-window signals)
        val windowSignals = signals.filter { it.timestampEpochMs in windowCutoff..latestTimestamp }
        if (windowSignals.isEmpty()) {
            return FusionResult(
                combinedConfidence = 0.0f,
                isEscalationTriggered = false,
                contributingSignals = emptyList(),
                explanation = "All signals expired outside sliding time window (${windowDuration}ms)."
            )
        }

        // 1. Check for Manual SOS or Silent Activation (Highest Priority - Immediate Escalation)
        val manualSignal = windowSignals.firstOrNull {
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
        val audioSignals = windowSignals.filter {
            it.type == DistressType.SCREAM || it.type == DistressType.KEYWORD
        }
        val motionSignals = windowSignals.filter {
            it.type == DistressType.MOTION_ANOMALY || it.type == DistressType.FALL_IMPACT
        }

        val bestAudio = audioSignals.maxByOrNull { it.confidence }
        val bestMotion = motionSignals.maxByOrNull { it.confidence }

        // 2. Correlated Multimodal Trigger (Audio + Motion within temporal window)
        if (bestAudio != null && bestMotion != null) {
            val deltaMs = abs(bestAudio.timestampEpochMs - bestMotion.timestampEpochMs)
            val temporalWeight = (1.0f - (deltaMs.toFloat() / windowDuration.toFloat())).coerceIn(0.1f, 1.0f)

            // Independent probability fusion formula with multimodal boost
            val baseFused = 1.0f - (1.0f - sanitize(bestAudio.confidence)) * (1.0f - sanitize(bestMotion.confidence))
            val combined = sanitize(baseFused + 0.08f * temporalWeight).coerceIn(0.85f, 0.99f)
            val shouldEscalate = combined >= escalationThreshold

            return FusionResult(
                combinedConfidence = combined,
                isEscalationTriggered = shouldEscalate,
                contributingSignals = listOf(bestAudio, bestMotion),
                explanation = "Multimodal Sensor Fusion: Temporal cross-correlation confirmed between ${bestAudio.type} (confidence %.2f) and ${bestMotion.type} (confidence %.2f) with Δt = ${deltaMs}ms <= ${windowDuration}ms. Combined confidence = %.2f >= %.2f. Immediate emergency escalation triggered.".format(
                    bestAudio.confidence,
                    bestMotion.confidence,
                    combined,
                    escalationThreshold
                )
            )
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
                val moderatedConfidence = sanitize(audioConf * 0.90f).coerceIn(0.50f, 0.79f)
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
        // Must NOT automatically equate a random phone shake to an attack
        if (bestMotion != null) {
            val motionConf = sanitize(bestMotion.confidence)
            val cappedConfidence = when (bestMotion.type) {
                DistressType.FALL_IMPACT -> sanitize(motionConf * 0.75f).coerceIn(0.50f, 0.68f)
                DistressType.MOTION_ANOMALY -> sanitize(motionConf * 0.65f).coerceIn(0.40f, 0.58f)
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
        val highest = windowSignals.maxByOrNull { it.confidence } ?: windowSignals.first()
        val conf = sanitize(highest.confidence * 0.50f).coerceIn(0.10f, 0.49f)
        return FusionResult(
            combinedConfidence = conf,
            isEscalationTriggered = false,
            contributingSignals = windowSignals,
            explanation = "Signals below escalation threshold (max confidence %.2f). No emergency escalation triggered.".format(conf)
        )
    }

    private fun sanitize(value: Float): Float {
        return if (value.isNaN() || value.isInfinite()) 0.0f else value.coerceIn(0.0f, 1.0f)
    }
}
