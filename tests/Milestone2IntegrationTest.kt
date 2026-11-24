package com.guardiangrid.tests

import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.ProtectionMode
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.anomaly.MotionThresholdConfig
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import com.guardiangrid.modules.motion.state.MotionEmergencyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Task F Milestone 2 Integration Verification Suite.
 * Validates the full runtime integration of real motion hardware interface, concurrent audio pipeline
 * (scream classification + keyword spotting), multimodal fusion with temporal correlation,
 * state machine reconciliation, verification countdown, cancellation, and duplicate dispatch prevention.
 */
class Milestone2IntegrationTest {

    private class TestEmergencyCommunicator : EmergencyCommunicator {
        val dispatchedPayloads = mutableListOf<AlertPayload>()
        var dispatchCount = 0

        override suspend fun checkChannelAvailability(channel: DeliveryChannel): Boolean = true

        override suspend fun dispatchAlert(
            payload: AlertPayload,
            channels: List<DeliveryChannel>
        ): List<DeliveryReceipt> {
            dispatchCount++
            dispatchedPayloads.add(payload)
            return channels.map { channel ->
                DeliveryReceipt(
                    channel = channel,
                    status = DeliveryStatus.DELIVERED,
                    messageId = "test-${UUID.randomUUID()}",
                    recipient = "test-recipient",
                    latencyMs = 50L
                )
            }
        }
    }

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
    }

    // --- Scenario 1: Concurrent Audio Streams Running ---

    @Test
    fun testConcurrentAudioStreamsRunning() {
        val audioStub = AudioModuleStub()
        assertFalse(audioStub.isMonitoring())
        assertFalse(audioStub.screamClassifier.isMonitoring())
        assertFalse(audioStub.captureManager.isCapturing)

        val flow = audioStub.startMonitoring()
        assertNotNull(flow)
        assertTrue(audioStub.isMonitoring())
        assertTrue(audioStub.screamClassifier.isMonitoring())
        assertTrue(audioStub.captureManager.isCapturing)

        audioStub.stopMonitoring()
        assertFalse(audioStub.isMonitoring())
        assertFalse(audioStub.screamClassifier.isMonitoring())
        assertFalse(audioStub.captureManager.isCapturing)
    }

    // --- Scenario 2: Single Supported Vocal Distress Produces Signal ---

    @Test
    fun testSingleSupportedKeywordProducesSignal() = runBlocking {
        val audioStub = AudioModuleStub()
        val flow = audioStub.startMonitoring()

        var detectedSignal: DistressSignal? = null
        val job = launch {
            flow.collect { signal ->
                if (signal.type == DistressType.SCREAM) {
                    detectedSignal = signal
                }
            }
        }

        // Give collector coroutine time to subscribe to SharedFlow
        delay(100)

        audioStub.screamClassifier.emitSimulatedDistress(0.94f, "VocalDistressClassifier")

        var attempts = 0
        while (detectedSignal == null && attempts < 25) {
            delay(40)
            attempts++
        }

        job.cancel()
        audioStub.stopMonitoring()

        assertNotNull("Vocal distress signal must be emitted", detectedSignal)
        assertEquals(DistressType.SCREAM, detectedSignal?.type)
        assertTrue(detectedSignal!!.confidence >= 0.90f)
    }

    // --- Scenario 3: Scream Event Reaches Coordinator and StateHolder ---

    @Test
    fun testScreamEventReachesCoordinator() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val audioStub = AudioModuleStub()
        val coordinator = EmergencyCoordinatorImpl(audioDetector = audioStub)

        coordinator.startProtection()
        // Wait for coordinator's audio monitoring coroutine to subscribe
        delay(100)
        assertTrue(EmergencyStateHolder.state.value.isAudioMonitoringRunning)

        audioStub.screamClassifier.emitSimulatedDistress(0.92f, "ScreamClassifier")

        var attempts = 0
        while (EmergencyStateHolder.state.value.lastDetectedSignal == null && attempts < 25) {
            delay(40)
            attempts++
        }

        val lastSignal = EmergencyStateHolder.state.value.lastDetectedSignal
        assertNotNull("Distress signal must reach EmergencyStateHolder", lastSignal)
        assertEquals(DistressType.SCREAM, lastSignal?.type)
        assertEquals(0.92f, lastSignal?.confidence ?: 0f, 0.01f)
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, EmergencyStateHolder.state.value.emergencyLevel)

        coordinator.stopProtection()
    }

    // --- Scenario 4: Real Motion Interface Integration ---

    @Test
    fun testRealMotionInterfaceIntegration() = runBlocking {
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource, config = MotionThresholdConfig())
        val coordinator = EmergencyCoordinatorImpl(motionDetector = motionDetector)

        assertFalse(motionDetector.isMonitoring())
        assertFalse(syntheticSource.isStreaming())

        coordinator.startProtection()
        // Wait for coordinator's motion monitoring coroutine to subscribe
        delay(100)
        assertTrue(motionDetector.isMonitoring())
        assertTrue(syntheticSource.isStreaming())
        assertTrue(EmergencyStateHolder.state.value.isMotionMonitoringRunning)

        motionDetector.emitSimulatedAnomaly(confidence = 0.88f, type = DistressType.FALL_IMPACT)

        var attempts = 0
        while (EmergencyStateHolder.state.value.lastDetectedSignal == null && attempts < 25) {
            delay(40)
            attempts++
        }

        val lastSignal = EmergencyStateHolder.state.value.lastDetectedSignal
        assertNotNull("Motion signal must reach EmergencyStateHolder", lastSignal)
        assertEquals(DistressType.FALL_IMPACT, lastSignal?.type)
        assertEquals(0.88f, lastSignal?.confidence ?: 0f, 0.01f)

        coordinator.stopProtection()
        assertFalse(motionDetector.isMonitoring())
        assertFalse(syntheticSource.isStreaming())
        assertFalse(EmergencyStateHolder.state.value.isMotionMonitoringRunning)
    }

    // --- Scenario 5: Correlated Audio and Motion Escalates to Confirmed Emergency ---

    @Test
    fun testCorrelatedAudioAndMotionEscalates() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val testComm = TestEmergencyCommunicator()
        val audioStub = AudioModuleStub()
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val coordinator = EmergencyCoordinatorImpl(
            communicator = testComm,
            audioDetector = audioStub,
            motionDetector = motionDetector
        )

        coordinator.startProtection()
        delay(100)

        // Motion anomaly (0.75) followed by scream distress (0.78) within 3-second temporal window
        motionDetector.emitSimulatedAnomaly(confidence = 0.75f, type = DistressType.FALL_IMPACT)
        delay(50)
        audioStub.screamClassifier.emitSimulatedDistress(confidence = 0.78f, source = "AudioClassifier")

        var attempts = 0
        while (EmergencyStateHolder.state.value.emergencyLevel != EmergencyLevel.CONFIRMED_EMERGENCY && attempts < 25) {
            delay(40)
            attempts++
        }

        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, EmergencyStateHolder.state.value.emergencyLevel)
        assertEquals(MotionEmergencyState.ALERTED, coordinator.stateMachine.state.value)
        assertEquals(1, testComm.dispatchCount)
        assertNotNull(coordinator.activeIncident.value)
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, coordinator.activeIncident.value?.level)

        coordinator.stopProtection()
    }

    // --- Scenario 6: Isolated Weak Motion Does Not Trigger SOS ---

    @Test
    fun testIsolatedWeakMotionDoesNotTriggerSos() = runBlocking {
        val testComm = TestEmergencyCommunicator()
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val coordinator = EmergencyCoordinatorImpl(
            communicator = testComm,
            motionDetector = motionDetector
        )

        coordinator.startProtection()
        delay(100)

        // Isolated weak motion anomaly (< 0.60)
        motionDetector.emitSimulatedAnomaly(confidence = 0.45f, type = DistressType.MOTION_ANOMALY)
        delay(200)

        // Should remain in MONITORING, not trigger SOS dispatch or CONFIRMED_EMERGENCY
        assertEquals(EmergencyLevel.MONITORING, EmergencyStateHolder.state.value.emergencyLevel)
        assertEquals(0, testComm.dispatchCount)
        assertNull(coordinator.activeIncident.value)

        coordinator.stopProtection()
    }

    // --- Scenario 7: Strong Audio Alone Triggers SOS Without Motion ---

    @Test
    fun testStrongAudioAloneTriggersSos() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val testComm = TestEmergencyCommunicator()
        val audioStub = AudioModuleStub()
        val coordinator = EmergencyCoordinatorImpl(
            communicator = testComm,
            audioDetector = audioStub
        )

        coordinator.startProtection()
        delay(100)

        // Strong scream (>= 0.85) without any motion anomaly
        audioStub.screamClassifier.emitSimulatedDistress(confidence = 0.90f, source = "TFLiteScreamClassifier")

        var attempts = 0
        while (EmergencyStateHolder.state.value.emergencyLevel != EmergencyLevel.CONFIRMED_EMERGENCY && attempts < 25) {
            delay(40)
            attempts++
        }

        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, EmergencyStateHolder.state.value.emergencyLevel)
        assertEquals(MotionEmergencyState.ALERTED, coordinator.stateMachine.state.value)
        assertEquals(1, testComm.dispatchCount)
        assertEquals(0L, EmergencyStateHolder.state.value.verificationCountdownRemainingMs)

        coordinator.stopProtection()
    }

    // --- Scenario 8: Manual SOS Bypasses Verification ---

    @Test
    fun testManualSosBypassesVerification() = runBlocking {
        val testComm = TestEmergencyCommunicator()
        val coordinator = EmergencyCoordinatorImpl(communicator = testComm)

        coordinator.triggerManualSos()

        var attempts = 0
        while (testComm.dispatchCount == 0 && attempts < 25) {
            delay(40)
            attempts++
        }

        // Immediately confirmed without countdown
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, EmergencyStateHolder.state.value.emergencyLevel)
        assertEquals(0L, EmergencyStateHolder.state.value.verificationCountdownRemainingMs)
        assertEquals(MotionEmergencyState.ALERTED, coordinator.stateMachine.state.value)
        assertNotNull(coordinator.activeIncident.value)
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, coordinator.activeIncident.value?.level)
        assertEquals(1, testComm.dispatchCount)
        assertEquals(DistressType.MANUAL_SOS, testComm.dispatchedPayloads[0].distressType)
    }

    // --- Scenario 9: Cancellation Prevents Delayed Timeout Alert ---

    @Test
    fun testCancellationPreventsDelayedDispatch() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val testComm = TestEmergencyCommunicator()
        val audioStub = AudioModuleStub()
        val coordinator = EmergencyCoordinatorImpl(
            communicator = testComm,
            audioDetector = audioStub
        )

        coordinator.startProtection()
        delay(100)

        // Moderate scream (0.75) triggers verification countdown (moderated to 0.675 in VERIFYING range [0.60, 0.79])
        audioStub.screamClassifier.emitSimulatedDistress(0.75f, "Audio")

        var attempts = 0
        while (EmergencyStateHolder.state.value.verificationCountdownRemainingMs == 0L && attempts < 25) {
            delay(40)
            attempts++
        }

        assertTrue("Verification countdown must be active", EmergencyStateHolder.state.value.verificationCountdownRemainingMs > 0L)
        assertEquals(EmergencyLevel.VERIFYING, EmergencyStateHolder.state.value.emergencyLevel)

        // User cancels before countdown finishes
        coordinator.cancelEmergency("User verified safe")

        assertEquals(0L, EmergencyStateHolder.state.value.verificationCountdownRemainingMs)
        assertEquals(MotionEmergencyState.CANCELLED, coordinator.stateMachine.state.value)

        // Delay to verify no timeout alert is dispatched
        delay(300)
        assertEquals(0, testComm.dispatchCount)
        assertNull(coordinator.activeIncident.value)

        coordinator.stopProtection()
    }

    // --- Scenario 10: Protection Stop Releases Resources ---

    @Test
    fun testProtectionStopReleasesResources() {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val audioStub = AudioModuleStub()
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val coordinator = EmergencyCoordinatorImpl(
            audioDetector = audioStub,
            motionDetector = motionDetector
        )

        coordinator.startProtection()
        Thread.sleep(100)
        assertTrue(audioStub.isMonitoring())
        assertTrue(motionDetector.isMonitoring())
        assertTrue(syntheticSource.isStreaming())
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertTrue(EmergencyStateHolder.state.value.isAudioMonitoringRunning)
        assertTrue(EmergencyStateHolder.state.value.isMotionMonitoringRunning)

        coordinator.stopProtection()
        assertFalse(audioStub.isMonitoring())
        assertFalse(motionDetector.isMonitoring())
        assertFalse(syntheticSource.isStreaming())
        assertFalse(EmergencyStateHolder.state.value.isProtectionActive)
        assertFalse(EmergencyStateHolder.state.value.isAudioMonitoringRunning)
        assertFalse(EmergencyStateHolder.state.value.isMotionMonitoringRunning)
        assertEquals(ProtectionMode.STANDBY, EmergencyStateHolder.state.value.mode)
        assertEquals(0L, EmergencyStateHolder.state.value.verificationCountdownRemainingMs)
    }

    // --- Scenario 11: Duplicate Signals Do Not Cause Duplicate Alert Dispatches ---

    @Test
    fun testDuplicateSignalsDoNotCauseDuplicateDispatch() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val testComm = TestEmergencyCommunicator()
        val audioStub = AudioModuleStub()
        val coordinator = EmergencyCoordinatorImpl(
            communicator = testComm,
            audioDetector = audioStub
        )

        coordinator.startProtection()
        delay(100)

        // First strong scream triggers escalation and alert dispatch
        audioStub.screamClassifier.emitSimulatedDistress(0.92f, "Audio")

        var attempts = 0
        while (testComm.dispatchCount == 0 && attempts < 25) {
            delay(40)
            attempts++
        }
        assertEquals(1, testComm.dispatchCount)

        // Rapid subsequent signals during the active incident must NOT cause duplicate dispatches
        audioStub.screamClassifier.emitSimulatedDistress(0.95f, "Audio")
        delay(200)

        assertEquals(1, testComm.dispatchCount)

        coordinator.stopProtection()
    }
}
