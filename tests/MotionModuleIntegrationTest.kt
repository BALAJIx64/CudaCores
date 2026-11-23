package com.guardiangrid.tests

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.anomaly.MotionThresholdConfig
import com.guardiangrid.modules.motion.demo.MotionDemoScenarioRunner
import com.guardiangrid.modules.motion.falsealarm.FalseAlarmFilter
import com.guardiangrid.modules.motion.features.MotionBaselineCalibrator
import com.guardiangrid.modules.motion.features.MotionFeatureExtractor
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import com.guardiangrid.modules.motion.state.EmergencyStateMachine
import com.guardiangrid.modules.motion.state.MotionEmergencyState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Comprehensive independent unit and integration tests for Member 2's Motion and Multimodal Intelligence module.
 * Tests motion anomaly detection, false-alarm logic, multimodal fusion, and emergency state machine.
 */
class MotionModuleIntegrationTest {

    @Test
    fun testKinematicFeatureExtractorCalculations() {
        val extractor = MotionFeatureExtractor(windowCapacityMs = 1000L)
        val now = 10000L

        // Stationary gravity on Z axis
        val sample1 = MotionSample(now, 0f, 0f, 9.8f, 0f, 0f, 0f)
        val feat1 = extractor.processSample(sample1)

        assertEquals(1, feat1.sampleCount)
        assertEquals(9.8f, feat1.currentMagnitude, 0.05f)
        assertEquals(0f, feat1.currentAngularSpeed, 0.01f)

        // Dynamic motion sample with rotation
        val sample2 = MotionSample(now + 20L, 3.0f, 4.0f, 0f, 1.0f, 2.0f, 2.0f) // mag = 5.0, gyro = 3.0
        val feat2 = extractor.processSample(sample2)

        assertEquals(2, feat2.sampleCount)
        assertEquals(5.0f, feat2.currentMagnitude, 0.05f)
        assertEquals(3.0f, feat2.currentAngularSpeed, 0.05f)
        assertTrue(feat2.currentJerk > 0f)
    }

    @Test
    fun testBaselineCalibratorAdaptsToGravity() {
        val calibrator = MotionBaselineCalibrator()
        assertFalse(calibrator.isCalibrated)

        // Feed 100 normal stationary samples
        for (i in 0 until 100) {
            calibrator.addCalibrationSample(MotionSample(1000L + i * 20L, 0f, 9.81f, 0f))
        }

        assertTrue(calibrator.isCalibrated)
        assertEquals(9.81f, calibrator.baselineGravity, 0.05f)
    }

    @Test
    fun testFalseAlarmFilterSuppressesTablePlacement() {
        val filter = FalseAlarmFilter()
        val extractor = MotionFeatureExtractor()
        val now = 5000L

        // Phone gently placed on table: impact mag ~ 14 m/s^2, no freefall, minimal gyro
        val s1 = MotionSample(now, 0f, 9.8f, 0f)
        extractor.processSample(s1)
        val s2 = MotionSample(now + 20L, 2.0f, 14.0f, 1.0f, 0.1f, 0.2f, 0.1f)
        val features = extractor.processSample(s2)

        val isFalseAlarm = filter.isFalseAlarm(features, DistressType.FALL_IMPACT)
        assertTrue("Placing phone on table should be classified as a false alarm", isFalseAlarm)
    }

    @Test
    fun testFalseAlarmFilterSuppressesNormalWalkingGait() {
        val filter = FalseAlarmFilter()
        val extractor = MotionFeatureExtractor()
        val now = 10000L

        var lastFeatures = extractor.processSample(MotionSample(now, 0f, 9.8f, 0f))
        for (i in 1..20) {
            val t = now + i * 50L
            val magX = (Math.sin(i * 0.4) * 2.0).toFloat()
            val magY = 9.8f + (Math.cos(i * 0.4) * 1.5).toFloat()
            val sample = MotionSample(t, magX, magY, 0.5f, 0.2f, 0.3f, 0.1f)
            lastFeatures = extractor.processSample(sample)
        }

        val isFalseAlarm = filter.isFalseAlarm(lastFeatures, DistressType.MOTION_ANOMALY)
        assertTrue("Normal walking gait should be suppressed", isFalseAlarm)
    }

    @Test
    fun testMultimodalFusionCorrelatedAudioAndMotion() {
        val fusionEngine = MultimodalFusionEngineImpl()
        val now = System.currentTimeMillis()

        val audio = DistressSignal(
            id = "a-1",
            type = DistressType.SCREAM,
            confidence = 0.85f,
            timestampEpochMs = now,
            source = "TFLiteScreamClassifier"
        )
        val motion = DistressSignal(
            id = "m-1",
            type = DistressType.FALL_IMPACT,
            confidence = 0.88f,
            timestampEpochMs = now - 500L,
            source = "MotionAnomalyDetector"
        )

        val result = fusionEngine.evaluateSignals(listOf(audio, motion), timeWindowMs = 3000L)
        assertTrue(result.combinedConfidence >= 0.90f)
        assertTrue(result.isEscalationTriggered)
        assertEquals(2, result.contributingSignals.size)
        assertTrue(result.explanation.contains("Temporal cross-correlation"))
    }

    @Test
    fun testMultimodalFusionStrongAudioAloneEscalates() {
        val fusionEngine = MultimodalFusionEngineImpl()
        val now = System.currentTimeMillis()

        // Strong scream >= 0.85 must escalate without mandatory motion confirmation
        val strongAudio = DistressSignal(
            id = "a-strong",
            type = DistressType.SCREAM,
            confidence = 0.89f,
            timestampEpochMs = now,
            source = "TFLiteScreamClassifier"
        )

        val result = fusionEngine.evaluateSignals(listOf(strongAudio))
        assertTrue("Strong audio must trigger escalation", result.isEscalationTriggered)
        assertEquals(0.89f, result.combinedConfidence, 0.01f)
        assertTrue(result.explanation.contains("without mandatory motion confirmation"))
    }

    @Test
    fun testMultimodalFusionIsolatedMotionDoesNotImmediatelyEscalate() {
        val fusionEngine = MultimodalFusionEngineImpl()
        val now = System.currentTimeMillis()

        val motionAlone = DistressSignal(
            id = "m-alone",
            type = DistressType.MOTION_ANOMALY,
            confidence = 0.90f,
            timestampEpochMs = now,
            source = "MotionAnomalyDetector"
        )

        val result = fusionEngine.evaluateSignals(listOf(motionAlone))
        assertFalse("Isolated motion must not immediately escalate to emergency", result.isEscalationTriggered)
        assertTrue(result.combinedConfidence < 0.80f)
        assertTrue(result.explanation.contains("Escalation suppressed"))
    }

    @Test
    fun testMultimodalFusionManualSosEscalatesImmediately() {
        val fusionEngine = MultimodalFusionEngineImpl()
        val now = System.currentTimeMillis()

        val manualSos = DistressSignal(
            id = "sos-1",
            type = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            timestampEpochMs = now,
            source = "UI_Button"
        )

        val result = fusionEngine.evaluateSignals(listOf(manualSos))
        assertTrue(result.isEscalationTriggered)
        assertEquals(1.0f, result.combinedConfidence, 0.01f)
    }

    @Test
    fun testEmergencyStateMachineAllRequiredStatesAndTransitions() {
        val stateMachine = EmergencyStateMachine()
        assertEquals(MotionEmergencyState.NORMAL, stateMachine.state.value)

        // 1. Moderate audio starts VERIFYING
        val moderateAudio = DistressSignal(
            id = "sig-1",
            type = DistressType.SCREAM,
            confidence = 0.75f,
            source = "Audio"
        )
        stateMachine.onSignal(moderateAudio)
        assertEquals(MotionEmergencyState.VERIFYING, stateMachine.state.value)

        // 2. Countdown tick to <= 2000ms triggers HIGH_CONCERN
        stateMachine.onCountdownTick(1500L)
        assertEquals(MotionEmergencyState.HIGH_CONCERN, stateMachine.state.value)

        // 3. User cancels countdown -> CANCELLED
        stateMachine.cancelEmergency("User verified safe")
        assertEquals(MotionEmergencyState.CANCELLED, stateMachine.state.value)

        // 4. Reset back to NORMAL
        stateMachine.resetToNormal()
        assertEquals(MotionEmergencyState.NORMAL, stateMachine.state.value)

        // 5. Manual SOS triggers ALERTED directly
        stateMachine.triggerManualSos()
        assertEquals(MotionEmergencyState.ALERTED, stateMachine.state.value)

        // 6. Responders resolve incident -> RESOLVED
        stateMachine.resolveEmergency("First responders on scene")
        assertEquals(MotionEmergencyState.RESOLVED, stateMachine.state.value)

        // 7. Verify all 7 states have been visited in transition history
        val visitedStates = stateMachine.transitionHistory.map { it.toState }.toSet()
        assertTrue(visitedStates.contains(MotionEmergencyState.VERIFYING))
        assertTrue(visitedStates.contains(MotionEmergencyState.HIGH_CONCERN))
        assertTrue(visitedStates.contains(MotionEmergencyState.CANCELLED))
        assertTrue(visitedStates.contains(MotionEmergencyState.NORMAL))
        assertTrue(visitedStates.contains(MotionEmergencyState.ALERTED))
        assertTrue(visitedStates.contains(MotionEmergencyState.RESOLVED))
    }

    @Test
    fun testEmergencyStateMappingToCoreEmergencyLevel() {
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.MONITORING,
            MotionEmergencyState.NORMAL.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.SUSPICIOUS,
            MotionEmergencyState.SUSPECTED_DISTRESS.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.VERIFYING,
            MotionEmergencyState.VERIFYING.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.VERIFYING,
            MotionEmergencyState.HIGH_CONCERN.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.CONFIRMED_EMERGENCY,
            MotionEmergencyState.ALERTED.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.CANCELLED,
            MotionEmergencyState.CANCELLED.toEmergencyLevel()
        )
        assertEquals(
            com.guardiangrid.core.models.EmergencyLevel.RESOLVED,
            MotionEmergencyState.RESOLVED.toEmergencyLevel()
        )
    }

    @Test
    fun testAllSevenDemonstrationScenariosSucceed() {
        val runner = MotionDemoScenarioRunner()
        val results = runner.runAllScenarios()

        assertEquals(7, results.size)
        for (res in results) {
            assertTrue("Scenario '${res.scenarioName}' should succeed: ${res.explanation}", res.success)
        }
    }
}
