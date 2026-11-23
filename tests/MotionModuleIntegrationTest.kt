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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Comprehensive independent unit and integration tests for Member 2's Motion and Multimodal Intelligence module.
 * Tests motion anomaly detection, false-alarm logic, multimodal fusion, emergency state machine,
 * and edge cases (missing hardware, boundary conditions, out-of-order timestamps, resource cleanup).
 */
class MotionModuleIntegrationTest {

    // --- KINEMATIC FEATURE EXTRACTION TESTS ---

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
    fun testEmptySensorInputHandling() {
        val extractor = MotionFeatureExtractor()
        // Feature extractor initialized with zero samples must handle first sample gracefully
        val firstSample = MotionSample(1000L, 0f, 0f, 0f)
        val features = extractor.processSample(firstSample)

        assertEquals(0f, features.currentMagnitude, 0.001f)
        assertEquals(0f, features.currentAngularSpeed, 0.001f)
        assertEquals(0f, features.currentJerk, 0.001f)
        assertFalse(features.currentMagnitude.isNaN())
        assertFalse(features.currentJerk.isNaN())
    }

    @Test
    fun testDuplicateAndOutOfOrderTimestamps() {
        val extractor = MotionFeatureExtractor(windowCapacityMs = 1000L)
        val baseTime = 10000L

        // Normal sample
        val s1 = MotionSample(baseTime, 0f, 9.8f, 0f)
        extractor.processSample(s1)

        // Duplicate timestamp (dt = 0): must NOT divide by zero
        val s2 = MotionSample(baseTime, 1.0f, 9.8f, 0f)
        val f2 = extractor.processSample(s2)
        assertFalse("Jerk must not be NaN on duplicate timestamp", f2.currentJerk.isNaN())
        assertFalse("Jerk must not be Infinite on duplicate timestamp", f2.currentJerk.isInfinite())
        assertEquals(0f, f2.currentJerk, 0.001f)

        // Out-of-order timestamp (backwards in time): must be handled safely
        val s3 = MotionSample(baseTime - 500L, 2.0f, 12.0f, 0f)
        val f3 = extractor.processSample(s3)
        assertFalse(f3.currentMagnitude.isNaN())
        assertFalse(f3.currentJerk.isNaN())
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

    // --- FALSE ALARM FILTER TESTS ---

    @Test
    fun testFalseAlarmFilterSuppressesTablePlacement() {
        val filter = FalseAlarmFilter()
        val extractor = MotionFeatureExtractor()
        val now = 5000L

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

    // --- SENSOR HARDWARE AND RESOURCE LIFECYCLE TESTS ---

    @Test
    fun testMissingGyroscopeHardwareFallback() = runBlocking {
        val syntheticSource = SyntheticMotionSensorSource()
        syntheticSource.setGyroscopeAvailable(false)
        assertFalse(syntheticSource.hasGyroscope())

        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val flow = detector.startMonitoring()
        assertTrue(detector.isMonitoring())

        var detectedSignal: DistressSignal? = null
        val job = launch {
            flow.collect { signal ->
                detectedSignal = signal
            }
        }

        // Simulate violent struggle with accelerometer variance and directional reversals alone
        val baseTime = System.currentTimeMillis()
        for (i in 0 until 20) {
            val sign = if (i % 2 == 0) 1.0f else -1.0f
            val ax = sign * 14.0f
            val ay = 9.8f + sign * 10.0f
            val az = sign * 8.0f
            // Gyroscope is zero
            syntheticSource.emitSample(MotionSample(baseTime + i * 50L, ax, ay, az, 0f, 0f, 0f))
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(150)

        // Detector must identify struggle even without gyroscope hardware
        detector.stopMonitoring()
        job.cancel()

        assertNotNull("Violent struggle must be detected with accelerometer fallback", detectedSignal)
        assertEquals(DistressType.MOTION_ANOMALY, detectedSignal?.type)
        assertEquals("false", detectedSignal?.metadata?.get("sensorHasGyro"))
    }

    @Test
    fun testRepeatedMonitoringStartStopLifecycle() {
        val syntheticSource = SyntheticMotionSensorSource()
        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)

        for (i in 0 until 5) {
            assertFalse(detector.isMonitoring())
            assertFalse(syntheticSource.isStreaming())

            detector.startMonitoring()
            assertTrue(detector.isMonitoring())
            assertTrue(syntheticSource.isStreaming())

            detector.stopMonitoring()
            assertFalse(detector.isMonitoring())
            assertFalse(syntheticSource.isStreaming())
        }
    }

    // --- MULTIMODAL FUSION & CONFIDENCE THRESHOLD BOUNDARY TESTS ---

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
    fun testMultimodalFusionEscalationThresholdBoundaries() {
        val fusionEngine = MultimodalFusionEngineImpl(
            escalationThreshold = 0.80f,
            strongAudioThreshold = 0.85f
        )
        val now = System.currentTimeMillis()

        // 1. Audio confidence right below 0.85 (0.849f): must NOT escalate alone
        val subThresholdAudio = DistressSignal(
            id = "sub-audio",
            type = DistressType.SCREAM,
            confidence = 0.849f,
            timestampEpochMs = now,
            source = "Audio"
        )
        val subResult = fusionEngine.evaluateSignals(listOf(subThresholdAudio))
        assertFalse("Audio at 0.849 must not escalate alone", subResult.isEscalationTriggered)
        assertTrue(subResult.combinedConfidence < 0.80f)

        // 2. Audio confidence exactly at 0.850f: MUST escalate alone
        val thresholdAudio = DistressSignal(
            id = "thresh-audio",
            type = DistressType.SCREAM,
            confidence = 0.850f,
            timestampEpochMs = now,
            source = "Audio"
        )
        val threshResult = fusionEngine.evaluateSignals(listOf(thresholdAudio))
        assertTrue("Audio at 0.850 must escalate alone", threshResult.isEscalationTriggered)
        assertEquals(0.850f, threshResult.combinedConfidence, 0.001f)
    }

    @Test
    fun testStaleAudioEvidencePrunedFromWindow() {
        val fusionEngine = MultimodalFusionEngineImpl()
        val now = System.currentTimeMillis()

        // Stale audio occurred 5000ms ago (window is 3000ms)
        val staleAudio = DistressSignal(
            id = "stale-audio",
            type = DistressType.SCREAM,
            confidence = 0.80f,
            timestampEpochMs = now - 5000L,
            source = "Audio"
        )

        // New motion occurred now
        val currentMotion = DistressSignal(
            id = "curr-motion",
            type = DistressType.FALL_IMPACT,
            confidence = 0.85f,
            timestampEpochMs = now,
            source = "Motion"
        )

        val result = fusionEngine.evaluateSignals(listOf(staleAudio, currentMotion), timeWindowMs = 3000L)
        // Stale audio must not correlate with current motion!
        assertFalse("Stale audio must not trigger multimodal escalation with fresh motion", result.isEscalationTriggered)
        assertEquals(1, result.contributingSignals.size)
        assertEquals("curr-motion", result.contributingSignals[0].id)
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

    // --- EMERGENCY STATE MACHINE TESTS ---

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

        // 5. Manual SOS triggers ALERTED directly (bypassing countdown)
        stateMachine.triggerManualSos()
        assertEquals(MotionEmergencyState.ALERTED, stateMachine.state.value)
        assertEquals(0L, stateMachine.activeCountdownRemainingMs)

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
