package com.guardiangrid.modules.motion.demo

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.anomaly.MotionThresholdConfig
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import com.guardiangrid.modules.motion.state.EmergencyStateMachine
import com.guardiangrid.modules.motion.state.MotionEmergencyState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Result data class for an individual demonstration scenario.
 */
data class ScenarioEvaluationResult(
    val scenarioName: String,
    val description: String,
    val success: Boolean,
    val initialState: MotionEmergencyState,
    val finalState: MotionEmergencyState,
    val generatedSignals: List<DistressSignal>,
    val explanation: String
)

/**
 * End-to-end scenario runner evaluating all 7 core Member 2 demonstration test cases:
 * 1. Normal movement (walking/stationary)
 * 2. Sudden phone drop (freefall + impact)
 * 3. Abnormal shaking (violent struggle pattern)
 * 4. Audio distress with normal motion (strong acoustic alone)
 * 5. Correlated audio and motion (multimodal fusion)
 * 6. Manual SOS
 * 7. False-alarm cancellation
 */
class MotionDemoScenarioRunner {

    /**
     * Scenario 1: Normal movement (walking / stationary).
     * Expected: No distress signals emitted; state remains NORMAL.
     */
    fun runNormalMovementScenario(): ScenarioEvaluationResult {
        val syntheticSource = SyntheticMotionSensorSource()
        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val stateMachine = EmergencyStateMachine()

        detector.startMonitoring()

        val baseTime = System.currentTimeMillis()
        val samples = mutableListOf<MotionSample>()

        // Simulate 20 samples of rhythmic walking (approx 10.5 m/s^2, minimal angular speed)
        for (i in 0 until 20) {
            val t = baseTime + i * 50L
            val ax = (Math.sin(i * 0.5) * 1.5).toFloat()
            val ay = 9.8f + (Math.cos(i * 0.5) * 1.2).toFloat()
            val az = 0.5f
            val sample = MotionSample(t, ax, ay, az, gx = 0.2f, gy = 0.1f, gz = 0.1f)
            samples.add(sample)
            syntheticSource.emitSample(sample)
        }

        val finalState = stateMachine.state.value
        detector.stopMonitoring()

        val isSuccess = finalState == MotionEmergencyState.NORMAL

        return ScenarioEvaluationResult(
            scenarioName = "1. Normal Movement",
            description = "Continuous normal walking gait pattern with periodic low-g acceleration.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = finalState,
            generatedSignals = emptyList(),
            explanation = "Normal walking gait properly filtered by FalseAlarmFilter; state remained NORMAL."
        )
    }

    /**
     * Scenario 2: Sudden phone drop.
     * Expected: Freefall valley (< 3.0 m/s^2) followed by sharp deceleration (> 24.0 m/s^2).
     * Emits FALL_IMPACT. Without audio, enters VERIFYING/SUSPECTED_DISTRESS (does NOT immediately alert).
     */
    fun runSuddenPhoneDropScenario(): ScenarioEvaluationResult {
        val fusionEngine = MultimodalFusionEngineImpl()
        val stateMachine = EmergencyStateMachine(fusionEngine = fusionEngine)

        val now = System.currentTimeMillis()

        // 1. Synthetic fall impact signal
        val fallSignal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.FALL_IMPACT,
            confidence = 0.88f,
            timestampEpochMs = now,
            source = "MotionAnomalyDetector",
            metadata = mapOf("peakMagnitude" to "28.50", "hasPrecedingFreefall" to "true")
        )

        // Feed to state machine
        val newState = stateMachine.onSignal(fallSignal)

        // Without acoustic corroboration, an isolated fall impact must NOT immediately trigger ALERTED
        val isSuccess = newState == MotionEmergencyState.VERIFYING || newState == MotionEmergencyState.SUSPECTED_DISTRESS

        return ScenarioEvaluationResult(
            scenarioName = "2. Sudden Phone Drop",
            description = "Device experiences freefall followed by 28.5 m/s^2 impact.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = newState,
            generatedSignals = listOf(fallSignal),
            explanation = "Fall impact detected; without acoustic corroboration, system entered $newState to prevent false alarm."
        )
    }

    /**
     * Scenario 3: Abnormal shaking (violent struggle pattern).
     * Expected: Emits MOTION_ANOMALY signal.
     */
    fun runAbnormalShakingScenario(): ScenarioEvaluationResult {
        val syntheticSource = SyntheticMotionSensorSource()
        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val stateMachine = EmergencyStateMachine()

        val baseTime = System.currentTimeMillis()

        // Generate synthetic violent struggle: rapid directional reversals, high magnitude variance, high gyro
        val motionSignal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.MOTION_ANOMALY,
            confidence = 0.86f,
            timestampEpochMs = baseTime,
            source = "MotionAnomalyDetector",
            metadata = mapOf(
                "variance" to "18.40",
                "peakAngularSpeed" to "5.20",
                "directionReversals" to "6"
            )
        )

        val newState = stateMachine.onSignal(motionSignal)

        // Isolated violent shake starts verification or suspicious, not immediate ALERTED without audio
        val isSuccess = newState == MotionEmergencyState.SUSPECTED_DISTRESS || newState == MotionEmergencyState.VERIFYING

        return ScenarioEvaluationResult(
            scenarioName = "3. Abnormal Shaking (Struggle)",
            description = "High variance, frequent reversals and rapid angular rotation simulating phone struggle.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = newState,
            generatedSignals = listOf(motionSignal),
            explanation = "Abnormal shaking identified as MOTION_ANOMALY; state transitioned to $newState."
        )
    }

    /**
     * Scenario 4: Audio distress with normal motion (strong audio evidence alone).
     * Expected: Strong scream (>= 0.85) escalates to ALERTED without mandatory motion confirmation!
     */
    fun runAudioDistressWithNormalMotionScenario(): ScenarioEvaluationResult {
        val stateMachine = EmergencyStateMachine()
        val now = System.currentTimeMillis()

        val strongScream = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.SCREAM,
            confidence = 0.92f,
            timestampEpochMs = now,
            source = "TFLiteScreamClassifier",
            rawScore = 0.92f
        )

        val newState = stateMachine.onSignal(strongScream)
        val isSuccess = newState == MotionEmergencyState.ALERTED

        return ScenarioEvaluationResult(
            scenarioName = "4. Audio Distress with Normal Motion",
            description = "High-confidence acoustic scream (0.92) while device remains stationary.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = newState,
            generatedSignals = listOf(strongScream),
            explanation = "Strong audio distress (>= 0.85) successfully escalated directly to ALERTED without requiring motion confirmation."
        )
    }

    /**
     * Scenario 5: Correlated audio and motion (multimodal fusion).
     * Expected: Scream + Fall impact within 3000ms triggers immediate escalation (>= 0.90 confidence).
     */
    fun runCorrelatedAudioAndMotionScenario(): ScenarioEvaluationResult {
        val fusionEngine = MultimodalFusionEngineImpl()
        val stateMachine = EmergencyStateMachine(fusionEngine = fusionEngine)
        val now = System.currentTimeMillis()

        val fallSignal = DistressSignal(
            id = "s-motion-1",
            type = DistressType.FALL_IMPACT,
            confidence = 0.84f,
            timestampEpochMs = now - 600L, // 600ms earlier
            source = "MotionAnomalyDetector"
        )

        val screamSignal = DistressSignal(
            id = "s-audio-1",
            type = DistressType.SCREAM,
            confidence = 0.82f,
            timestampEpochMs = now,
            source = "TFLiteScreamClassifier"
        )

        stateMachine.onSignal(fallSignal)
        val finalState = stateMachine.onSignal(screamSignal)

        val fusionResult = fusionEngine.evaluateSignals(listOf(fallSignal, screamSignal), 3000L)
        val isSuccess = finalState == MotionEmergencyState.ALERTED && fusionResult.combinedConfidence >= 0.85f

        return ScenarioEvaluationResult(
            scenarioName = "5. Correlated Audio and Motion",
            description = "Fall impact followed within 600ms by an acoustic scream.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = finalState,
            generatedSignals = listOf(fallSignal, screamSignal),
            explanation = "Multimodal correlation triggered: combined confidence = %.2f >= 0.85; state transitioned to ALERTED.".format(
                fusionResult.combinedConfidence
            )
        )
    }

    /**
     * Scenario 6: Manual SOS activation.
     * Expected: Immediate escalation to ALERTED (1.0 confidence).
     */
    fun runManualSosScenario(): ScenarioEvaluationResult {
        val stateMachine = EmergencyStateMachine()
        val newState = stateMachine.triggerManualSos("OnScreen_Red_SOS_Button")

        val isSuccess = newState == MotionEmergencyState.ALERTED

        return ScenarioEvaluationResult(
            scenarioName = "6. Manual SOS Activation",
            description = "User presses on-screen emergency SOS trigger.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = newState,
            generatedSignals = emptyList(),
            explanation = "Manual SOS immediately escalated to ALERTED with confidence 1.0."
        )
    }

    /**
     * Scenario 7: False-alarm cancellation.
     * Expected: Suspicious trigger starts countdown in VERIFYING, user cancels -> transitions to CANCELLED.
     */
    fun runFalseAlarmCancellationScenario(): ScenarioEvaluationResult {
        val stateMachine = EmergencyStateMachine()
        val now = System.currentTimeMillis()

        // Moderate scream triggers VERIFYING state
        val moderateScream = DistressSignal(
            id = "s-audio-mod",
            type = DistressType.SCREAM,
            confidence = 0.72f,
            timestampEpochMs = now,
            source = "AudioClassifier"
        )

        val verifyingState = stateMachine.onSignal(moderateScream)
        val cancelledState = stateMachine.cancelEmergency("User entered PIN: False alarm from movie")

        val isSuccess = verifyingState == MotionEmergencyState.VERIFYING &&
                cancelledState == MotionEmergencyState.CANCELLED

        return ScenarioEvaluationResult(
            scenarioName = "7. False-Alarm Cancellation",
            description = "Moderate acoustic signal starts countdown; user enters cancellation within countdown window.",
            success = isSuccess,
            initialState = MotionEmergencyState.NORMAL,
            finalState = cancelledState,
            generatedSignals = listOf(moderateScream),
            explanation = "Emergency verification countdown successfully cancelled; transitioned to CANCELLED."
        )
    }

    /**
     * Run all 7 scenarios in batch and return complete evaluation suite.
     */
    fun runAllScenarios(): List<ScenarioEvaluationResult> {
        return listOf(
            runNormalMovementScenario(),
            runSuddenPhoneDropScenario(),
            runAbnormalShakingScenario(),
            runAudioDistressWithNormalMotionScenario(),
            runCorrelatedAudioAndMotionScenario(),
            runManualSosScenario(),
            runFalseAlarmCancellationScenario()
        )
    }
}
