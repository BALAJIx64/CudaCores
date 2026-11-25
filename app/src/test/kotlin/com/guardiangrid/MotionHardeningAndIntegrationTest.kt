package com.guardiangrid

import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.ProtectionMode
import com.guardiangrid.modules.communication.persistence.FileSettingsRepository
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.falsealarm.FalseAlarmFilter
import com.guardiangrid.modules.motion.features.MotionFeatureExtractor
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import com.guardiangrid.modules.motion.state.MotionEmergencyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.cos
import kotlin.math.sin

class MotionHardeningAndIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class CountingCommunicator : EmergencyCommunicator {
        val dispatchedPayloads = mutableListOf<AlertPayload>()
        override suspend fun dispatchAlert(
            payload: AlertPayload,
            channels: List<DeliveryChannel>
        ): List<DeliveryReceipt> {
            dispatchedPayloads.add(payload)
            return channels.map {
                DeliveryReceipt(channel = it, status = DeliveryStatus.DELIVERED, messageId = "msg-${dispatchedPayloads.size}")
            }
        }

        override suspend fun checkChannelAvailability(channel: DeliveryChannel): Boolean = true
    }

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
    }

    @After
    fun tearDown() {
        EmergencyStateHolder.reset()
    }

    @Test
    fun testStateHolderHasNoDummyPlaceholderContacts() {
        val state = EmergencyStateHolder.state.value
        assertTrue("Default contacts must be empty without dummy placeholder entries", state.trustedContacts.isEmpty())
    }

    @Test
    fun testUnavailableAccelerometerReflectedTruthfullyInCoordinator() {
        val syntheticSource = SyntheticMotionSensorSource().apply {
            setAccelerometerAvailable(false)
        }
        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val coordinator = EmergencyCoordinatorImpl(motionDetector = detector)

        EmergencyStateHolder.updatePermissions(audio = false, location = true)
        coordinator.startProtection()

        assertFalse("Detector must not report monitoring active without accelerometer", detector.isMonitoring())
        assertFalse("StateHolder must reflect unavailable motion monitoring", EmergencyStateHolder.state.value.isMotionMonitoringRunning)
        assertFalse("StateHolder must reflect missing accelerometer hardware", EmergencyStateHolder.state.value.isAccelerometerAvailable)

        coordinator.stopProtection()
    }

    @Test
    fun testRunningGaitSuppressedWhileViolentStruggleDetected() {
        val filter = FalseAlarmFilter()
        val runningExtractor = MotionFeatureExtractor(windowCapacityMs = 1000L)
        val now = 20_000L

        var runningFeatures = runningExtractor.processSample(MotionSample(now, 0f, 9.81f, 0f))
        for (i in 1..20) {
            val t = now + i * 40L
            val ax = (sin(i * 0.7) * 3.0).toFloat()
            val ay = 10.5f + (cos(i * 0.7) * 3.2).toFloat()
            val az = 1.2f
            runningFeatures = runningExtractor.processSample(MotionSample(t, ax, ay, az, gx = 0.8f, gy = 1.1f, gz = 0.6f))
        }

        assertTrue("Rhythmic jogging/running gait must be suppressed as false alarm", filter.isFalseAlarm(runningFeatures, DistressType.MOTION_ANOMALY))

        val struggleExtractor = MotionFeatureExtractor(windowCapacityMs = 1000L)
        var struggleFeatures = struggleExtractor.processSample(MotionSample(now, 0f, 9.81f, 0f))
        for (i in 1..20) {
            val t = now + i * 40L
            val sign = if (i % 2 == 0) 1f else -1f
            struggleFeatures = struggleExtractor.processSample(
                MotionSample(t, sign * 15f, 9.8f + sign * 11f, sign * 9f, gx = 3.5f, gy = 3.0f, gz = 2.5f)
            )
        }

        assertFalse("Violent high-rotation struggle must NOT be suppressed", filter.isFalseAlarm(struggleFeatures, DistressType.MOTION_ANOMALY))
    }

    @Test
    fun testSequentialMotionProcessingUpdatesLiveMetrics() = runBlocking {
        val syntheticSource = SyntheticMotionSensorSource()
        val detector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        detector.startMonitoring()

        val baseTime = 50_000L
        syntheticSource.emitSample(MotionSample(baseTime, 0f, 9.81f, 0f, gx = 0.1f, gy = 0.1f, gz = 0.1f))
        syntheticSource.emitSample(MotionSample(baseTime + 20L, 6.0f, 8.0f, 0f, gx = 1.2f, gy = 0.9f, gz = 0.0f))

        delay(120)
        val metrics = detector.liveMetrics.value
        assertEquals(10.0f, metrics.magnitude, 0.1f)
        assertEquals(1.5f, metrics.angularSpeed, 0.05f)
        assertTrue(metrics.hasAccelerometer)
        assertTrue(metrics.hasGyroscope)

        detector.stopMonitoring()
    }

    @Test
    fun testDiscreetSosWithLocationDispatchesSingleDeduplicatedAlertWithContacts() = runBlocking {
        val comm = CountingCommunicator()
        val contact = EmergencyContact(id = "c1", name = "Trusted Responder", phoneNumber = "+14155550199", isPrimary = true)
        EmergencyStateHolder.updateTrustedContacts(listOf(contact))

        val fix = LocationData(latitude = 12.9716, longitude = 77.5946, accuracyMeters = 10f)
        val coordinator = EmergencyCoordinatorImpl(communicator = comm)

        coordinator.triggerDiscreetSos("Stealth_Discreet_Button", locationOverride = fix)
        delay(120)

        assertEquals("Exactly one alert payload must be dispatched", 1, comm.dispatchedPayloads.size)
        val sentPayload = comm.dispatchedPayloads.first()
        assertEquals(DistressType.SILENT_ACTIVATION, sentPayload.distressType)
        assertEquals(fix, sentPayload.location)
        assertEquals(1, sentPayload.recipientContacts.size)
        assertEquals("+14155550199", sentPayload.recipientContacts.first().phoneNumber)
    }

    @Test
    fun testResumeMonitoringAfterUserCancellationRestoresProtection() = runBlocking {
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val coordinator = EmergencyCoordinatorImpl(motionDetector = motionDetector)

        coordinator.setVerificationTimeoutMs(8000L)
        assertEquals(8000L, EmergencyStateHolder.state.value.verificationTotalTimeoutMs)

        coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)

        val moderateSignal = DistressSignal(
            id = "sig-mod-1",
            type = DistressType.SCREAM,
            confidence = 0.72f,
            timestampEpochMs = System.currentTimeMillis(),
            source = "AudioTest"
        )
        coordinator.handleDistressSignal(moderateSignal)
        assertEquals(EmergencyLevel.VERIFYING, EmergencyStateHolder.state.value.emergencyLevel)

        coordinator.cancelEmergency("User cancelled false alarm")
        assertNull(coordinator.activeIncident.value)
        assertEquals(MotionEmergencyState.CANCELLED, coordinator.stateMachine.state.value)

        coordinator.resumeMonitoringAfterUserClearance()
        assertEquals(MotionEmergencyState.NORMAL, coordinator.stateMachine.state.value)
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(ProtectionMode.ACTIVE_MONITORING, EmergencyStateHolder.state.value.mode)
        assertEquals(EmergencyLevel.MONITORING, EmergencyStateHolder.state.value.emergencyLevel)

        coordinator.stopProtection()
    }

    @Test
    fun testMotionSensitivityAndCountdownPersistAcrossReload() {
        val dir = tempFolder.newFolder("motion_settings")
        val repo = FileSettingsRepository(dir)

        assertTrue(repo.setMotionSensitivity(1.35f))
        assertTrue(repo.setCountdownDuration(15))

        val reloaded = FileSettingsRepository(dir)
        val loaded = reloaded.getSettings()
        assertEquals(1.35f, loaded.motionSensitivity, 0.001f)
        assertEquals(15, loaded.countdownDurationSeconds)
    }
}
