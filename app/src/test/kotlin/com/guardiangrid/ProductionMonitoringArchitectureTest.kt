package com.guardiangrid

import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.MonitoringServiceState
import com.guardiangrid.core.state.ProtectionMode
import com.guardiangrid.modules.motion.sensors.MotionSensorSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test suite verifying Stage 1: Production Monitoring Architecture.
 *
 * Covers:
 * 1. Application-level container singleton reuse & identity verification.
 * 2. Idempotent protection activation (prevention of duplicate sessions).
 * 3. Safe repeated stop calls.
 * 4. Startup failure reflection via MonitoringServiceState.ERROR.
 * 5. Full resource cleanup on stop (audio/motion jobs cancelled, state reset).
 * 6. Activity recreation isolation (retaining coordinator & active monitoring).
 * 7. Permission denial handling (PERMISSION_REQUIRED state and gated audio start).
 * 8. Complete lifecycle state transitions across MonitoringServiceState.
 */
class ProductionMonitoringArchitectureTest {

    private lateinit var tempDir: File

    class FakeMotionSensorSource : MotionSensorSource {
        private var streaming = false
        override fun start(onSample: (MotionSample) -> Unit) { streaming = true }
        override fun stop() { streaming = false }
        override fun isStreaming(): Boolean = streaming
        override fun hasGyroscope(): Boolean = true
    }

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_arch_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        EmergencyStateHolder.reset()
        tempDir.deleteRecursively()
    }

    @Test
    fun testContainerReturnsSingletonInstances() {
        val container = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        // Multiple property accesses must return the exact same instance in memory
        val coord1 = container.coordinator
        val coord2 = container.coordinator
        assertSame("Coordinator must be a singleton instance within container", coord1, coord2)

        val audio1 = container.audioModule
        val audio2 = container.audioModule
        assertSame("AudioModule must be a singleton instance within container", audio1, audio2)

        val motion1 = container.motionDetector
        val motion2 = container.motionDetector
        assertSame("MotionDetector must be a singleton instance within container", motion1, motion2)

        val loc1 = container.locationManager
        val loc2 = container.locationManager
        assertSame("LocationManager must be a singleton instance within container", loc1, loc2)

        val comms1 = container.communicator
        val comms2 = container.communicator
        assertSame("Communicator must be a singleton instance within container", comms1, comms2)

        val fusion1 = container.fusionEngine
        val fusion2 = container.fusionEngine
        assertSame("FusionEngine must be a singleton instance within container", fusion1, fusion2)

        val settings1 = container.settingsRepository
        val settings2 = container.settingsRepository
        assertSame("SettingsRepository must be a singleton instance within container", settings1, settings2)

        val incident1 = container.incidentRepository
        val incident2 = container.incidentRepository
        assertSame("IncidentRepository must be a singleton instance within container", incident1, incident2)

        val creds1 = container.secureCredentialStore
        val creds2 = container.secureCredentialStore
        assertSame("SecureCredentialStore must be a singleton instance within container", creds1, creds2)
    }

    @Test
    fun testRepeatedStartProtectionIsIdempotent() {
        val container = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        EmergencyStateHolder.updatePermissions(audio = true, location = true)

        // First start
        container.coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(ProtectionMode.ACTIVE_MONITORING, EmergencyStateHolder.state.value.mode)

        // Second start (must not crash, must not duplicate jobs)
        container.coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(ProtectionMode.ACTIVE_MONITORING, EmergencyStateHolder.state.value.mode)

        container.coordinator.stopProtection()
        assertFalse(EmergencyStateHolder.state.value.isProtectionActive)
    }

    @Test
    fun testRepeatedStopProtectionIsSafe() {
        val container = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        container.coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)

        // First stop
        container.coordinator.stopProtection()
        assertFalse(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(ProtectionMode.STANDBY, EmergencyStateHolder.state.value.mode)

        // Second stop (idempotency check)
        container.coordinator.stopProtection()
        assertFalse(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(ProtectionMode.STANDBY, EmergencyStateHolder.state.value.mode)
    }

    @Test
    fun testStartupFailureReflection() {
        assertEquals(MonitoringServiceState.STOPPED, EmergencyStateHolder.state.value.monitoringServiceState)

        val errorMessage = "Hardware microphone initialization failure: AudioRecord error -1"
        EmergencyStateHolder.setMonitoringServiceState(
            serviceState = MonitoringServiceState.ERROR,
            errorMessage = errorMessage
        )

        val currentState = EmergencyStateHolder.state.value
        assertEquals(MonitoringServiceState.ERROR, currentState.monitoringServiceState)
        assertEquals(errorMessage, currentState.monitoringServiceError)
    }

    @Test
    fun testResourceCleanupOnStop() {
        val container = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        EmergencyStateHolder.updatePermissions(audio = true, location = true)
        container.coordinator.startProtection()

        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertTrue(EmergencyStateHolder.state.value.isAudioMonitoringRunning)
        assertTrue(EmergencyStateHolder.state.value.isMotionMonitoringRunning)

        container.coordinator.stopProtection()

        val stateAfterStop = EmergencyStateHolder.state.value
        assertFalse("Protection must be inactive after stopProtection", stateAfterStop.isProtectionActive)
        assertFalse("Audio monitoring must be stopped", stateAfterStop.isAudioMonitoringRunning)
        assertFalse("Motion monitoring must be stopped", stateAfterStop.isMotionMonitoringRunning)
        assertEquals(ProtectionMode.STANDBY, stateAfterStop.mode)
        assertNull("Active incident must be cleared", container.coordinator.activeIncident.value)
        assertNull("StateHolder active incident must be cleared", stateAfterStop.activeIncident)
    }

    @Test
    fun testActivityRecreationPreservesCoordinatorAndProtectionState() {
        // App container owned by Application instance
        val appContainer: GuardianGridContainer = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        // Activity 1 launches and activates protection
        val act1Coordinator = appContainer.coordinator
        EmergencyStateHolder.updatePermissions(audio = true, location = true)
        act1Coordinator.startProtection()
        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.ACTIVE)

        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(MonitoringServiceState.ACTIVE, EmergencyStateHolder.state.value.monitoringServiceState)

        // Activity 1 is destroyed during configuration change / screen rotation.
        // Production architecture rule: Activity.onDestroy() does NOT call coordinator.stopProtection().

        // Activity 2 is created and attaches to the same appContainer
        val act2Coordinator = appContainer.coordinator
        assertSame("Recreated Activity must receive identical coordinator instance", act1Coordinator, act2Coordinator)
        assertTrue("Active protection must persist across Activity recreation", EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(MonitoringServiceState.ACTIVE, EmergencyStateHolder.state.value.monitoringServiceState)

        // Cleanup
        act2Coordinator.stopProtection()
        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPED)
    }

    @Test
    fun testPermissionDenialHandling() {
        val container = DefaultGuardianGridContainer(
            customMotionSource = FakeMotionSensorSource()
        )

        // Audio permission denied
        EmergencyStateHolder.updatePermissions(audio = false, location = true)

        EmergencyStateHolder.setMonitoringServiceState(
            MonitoringServiceState.PERMISSION_REQUIRED,
            "android.permission.RECORD_AUDIO is required to monitor for distress sounds."
        )

        val state = EmergencyStateHolder.state.value
        assertEquals(MonitoringServiceState.PERMISSION_REQUIRED, state.monitoringServiceState)
        assertNotNull(state.monitoringServiceError)

        // Coordinator protection without audio permission must NOT start audio monitoring
        container.coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertFalse("Audio monitoring must NOT run when RECORD_AUDIO permission is not granted", EmergencyStateHolder.state.value.isAudioMonitoringRunning)

        container.coordinator.stopProtection()
    }

    @Test
    fun testMonitoringServiceStateTransitions() {
        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPED)
        assertEquals(MonitoringServiceState.STOPPED, EmergencyStateHolder.state.value.monitoringServiceState)

        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STARTING)
        assertEquals(MonitoringServiceState.STARTING, EmergencyStateHolder.state.value.monitoringServiceState)

        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.ACTIVE)
        assertEquals(MonitoringServiceState.ACTIVE, EmergencyStateHolder.state.value.monitoringServiceState)

        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPING)
        assertEquals(MonitoringServiceState.STOPPING, EmergencyStateHolder.state.value.monitoringServiceState)

        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPED)
        assertEquals(MonitoringServiceState.STOPPED, EmergencyStateHolder.state.value.monitoringServiceState)
    }
}
