package com.guardiangrid.tests

import com.guardiangrid.core.interfaces.AudioDistressDetector
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.interfaces.MotionAnomalyDetector
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.AudioStage
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.ProtectionMode
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.EmergencyCommunicatorStub
import com.guardiangrid.modules.motion.MotionModuleStub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architectural contract verification tests.
 * Validates that core models, state management, and module stubs adhere to contract rules.
 */
class CoreArchitectureTest {

    @Test
    fun testEmergencyStateHolderLifecycle() {
        EmergencyStateHolder.setProtectionActive(true, ProtectionMode.ACTIVE_MONITORING)
        val state = EmergencyStateHolder.state.value

        assertTrue(state.isProtectionActive)
        assertEquals(ProtectionMode.ACTIVE_MONITORING, state.mode)
        assertEquals(EmergencyLevel.MONITORING, state.emergencyLevel)

        EmergencyStateHolder.setProtectionActive(false)
        val stoppedState = EmergencyStateHolder.state.value
        assertFalse(stoppedState.isProtectionActive)
        assertEquals(ProtectionMode.STANDBY, stoppedState.mode)
    }

    @Test
    fun testAudioModuleStubContract() = runBlocking {
        val audioStub: AudioDistressDetector = AudioModuleStub()
        audioStub.setSensitivity(0.85f)
        val flow = audioStub.startMonitoring()
        assertNotNull(flow)
        assertTrue(audioStub.isMonitoring())

        audioStub.stopMonitoring()
        assertFalse(audioStub.isMonitoring())

        val recorder = AudioModuleStub()
        val stage1Result = recorder.captureStageOneBuffer(5000)
        assertTrue(stage1Result.isSuccess)
        assertEquals(AudioStage.STAGE_1_PREVIEW, stage1Result.getOrNull()?.stage)
    }

    @Test
    fun testMotionModuleStubAndFusionContract() {
        val motionStub: MotionAnomalyDetector = MotionModuleStub()
        val flow = motionStub.startMonitoring()
        assertNotNull(flow)
        assertTrue(motionStub.isMonitoring())
        motionStub.stopMonitoring()
        assertFalse(motionStub.isMonitoring())

        val fusionEngine = MotionModuleStub()
        val audioSignal = DistressSignal(
            id = "s-1",
            type = DistressType.SCREAM,
            confidence = 0.90f,
            source = "AudioClassifier"
        )
        val motionSignal = DistressSignal(
            id = "s-2",
            type = DistressType.FALL_IMPACT,
            confidence = 0.88f,
            source = "MotionClassifier"
        )

        val result = fusionEngine.evaluateSignals(listOf(audioSignal, motionSignal))
        assertTrue(result.combinedConfidence >= 0.85f)
        assertTrue(result.isEscalationTriggered)
    }

    @Test
    fun testCommunicationStubContract() = runBlocking {
        val commStub: EmergencyCommunicator = EmergencyCommunicatorStub()
        val payload = AlertPayload(
            incidentId = "inc-001",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            location = null,
            emergencyMessage = "TEST SOS ALERT"
        )

        val receipts = commStub.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
        assertEquals(2, receipts.size)
        assertTrue(receipts.all { it.status == DeliveryStatus.DELIVERED })
    }

    @Test
    fun testIncidentEscalationState() {
        val incident = EmergencyIncident(
            incidentId = "inc-100",
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            notes = "Multimodal triggered incident"
        )
        EmergencyStateHolder.setEmergencyIncident(incident)

        val state = EmergencyStateHolder.state.value
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, state.emergencyLevel)
        assertEquals("inc-100", state.activeIncident?.incidentId)

        EmergencyStateHolder.setEmergencyIncident(null)
    }
}
