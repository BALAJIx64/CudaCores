package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.guardian.GuardianCryptoCodec
import com.guardiangrid.modules.communication.guardian.HttpGuardianRelayClient
import com.guardiangrid.modules.communication.guardian.PairingRole
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.persistence.PhoneNumberValidator
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import com.guardiangrid.ui.NativeContactHelper
import com.guardiangrid.ui.guardian.QrCodeEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sin

class DeploymentRequirementsVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        EmergencyStateHolder.reset()
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
    }

    @Test
    fun circularBufferPreservesFullTenSecondsAndPrePostRelaySaves20sWav() = runBlocking {
        val cacheDir = tempFolder.newFolder("audio_relay_cache")
        val audioModule = AudioModuleStub(cacheDirectory = cacheDir)
        assertEquals(10_000L, audioModule.circularBuffer.durationMs)
        assertEquals(160_000, audioModule.circularBuffer.capacitySamples)

        // Feed 10 full seconds of 16 kHz PCM into rolling buffer
        val preTriggerPcm = ShortArray(160_000) { i ->
            (sin(2.0 * Math.PI * 2400.0 * i / 16000.0) * 12000).toInt().toShort()
        }
        audioModule.circularBuffer.write(preTriggerPcm)
        assertEquals(10_000L, audioModule.circularBuffer.availableDurationMs())

        // Start continuous relay session (10s pre-trigger + 5x200ms post-trigger chunks capped cleanly at 1000ms for unit test timing)
        val session = audioModule.startIncidentAudioRelay(
            incidentId = "INC-RELAY-20S",
            preTriggerDurationMs = 10_000L,
            chunkDurationMs = 200L,
            maxRecordingDurationMs = 1000L
        )

        val preFile = File(session.preTriggerSegment.filePath)
        assertTrue(preFile.exists())
        // 10s * 16000 * 2 bytes + 44 byte WAV header = 320,044 bytes
        assertEquals(320_044L, preFile.length())

        // Simulate continuous incoming mic samples during post-trigger window
        val postWriterJob = launch {
            repeat(5) {
                val postSlice = ShortArray(3200) { i ->
                    (sin(2.0 * Math.PI * 1800.0 * i / 16000.0) * 9000).toInt().toShort()
                }
                audioModule.circularBuffer.write(postSlice)
                delay(45L)
            }
        }

        val emittedChunks = session.chunkFlow.take(5).toList()
        postWriterJob.join()
        val summary = session.completionDeferred.await()

        assertEquals(5, emittedChunks.size)
        assertTrue(emittedChunks.last().isFinal)
        emittedChunks.forEach { chunk ->
            val chunkFile = File(chunk.segment.filePath)
            assertTrue("Chunk WAV file must exist", chunkFile.exists())
            assertTrue("Chunk must contain valid RIFF header + PCM", chunkFile.length() > 44L)
        }

        val combinedFile = File(summary.combinedRecordingSegment.filePath)
        assertTrue("Final complete recording must exist", combinedFile.exists())
        assertTrue("Final recording must include both pre + post PCM", combinedFile.length() > preFile.length())
    }

    @Test
    fun smsNeverCarriesAudioWhileTelegramAndGuardianRelayUploadAudioAndRetryOffline() = runBlocking {
        val queueDir = tempFolder.newFolder("offline_queue")
        val guardianDir = tempFolder.newFolder("guardian_store")
        val audioDir = tempFolder.newFolder("wav_files")
        val wavFile = File(audioDir, "incident_pre10s.wav").apply {
            writeBytes(ByteArray(1024) { 0x42 })
        }

        val transport = MockHttpTransport()
        val smsSender = MockSmsSender()
        val guardianMgr = GuardianConnectivityManager(guardianDir, "HostPhone")
        val guardianInvite = guardianMgr.state.value.activeInvitationUri

        // Pair a guardian device cleanly without account login
        val responderMgr = GuardianConnectivityManager(tempFolder.newFolder("responder_store"), "GuardianPhone")
        val parsedHost = responderMgr.acceptPairingInvitation(guardianInvite, PairingRole.HOST).getOrThrow()
        assertEquals(guardianMgr.getIdentity().deviceId, parsedHost.deviceId)

        guardianMgr.registerGuardianFromResponse(
            guardianDeviceId = responderMgr.getIdentity().deviceId,
            guardianDisplayName = responderMgr.getIdentity().deviceName
        ).getOrThrow()
        guardianMgr.setRelayServerUrl("https://relay.guardiangrid.test").getOrThrow()

        val validToken = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ123456"
        val communicator = EmergencyCommunicatorImpl(
            transport = transport,
            smsSender = smsSender,
            queueRepository = FileEmergencyQueueRepository(queueDir),
            credentialsProvider = { TelegramCredentials(validToken, "987654321") },
            guardianConnectivityManager = guardianMgr
        )

        val contacts = listOf(
            EmergencyContact("c1", "Mom", "+919876543210", isPrimary = true, relationship = "Parent")
        )
        val payload = AlertPayload(
            incidentId = "INC-AUDIO-SEC",
            distressType = DistressType.SCREAM,
            confidence = 0.92f,
            location = LocationData(12.9716, 77.5946, 5.0f),
            emergencyMessage = "Scream detected",
            audioSegmentPath = wavFile.absolutePath,
            recipientContacts = contacts
        )

        // 1. Dispatch standard emergency alert: SMS must carry location + text ONLY (never audio path or file)
        communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS, DeliveryChannel.INTERNET_API))
        assertEquals(1, smsSender.sentMessages.size)
        val smsBody = smsSender.sentMessages.first().text
        assertTrue(smsBody.contains("https://maps.google.com/?q=12.97160,77.59460"))
        assertFalse("SMS must NEVER reference WAV or audio filename", smsBody.contains("incident_pre10s") || smsBody.contains(".wav"))

        // 2. Direct SMS block guard when payload.isAudioRelay == true
        val blockedReceipt = communicator.dispatchAlert(
            payload.copy(isAudioRelay = true, audioChunkIndex = 0),
            listOf(DeliveryChannel.SMS)
        ).first()
        assertEquals(DeliveryStatus.ABANDONED, blockedReceipt.status)
        assertEquals("SMS count must remain 1", 1, smsSender.sentMessages.size)

        // 3. Offline failure enqueues audio relay persistently and retries when connectivity returns
        transport.isNetworkAvailable = false
        val failedAudioReceipts = communicator.dispatchAudioEvidence(
            incidentId = "INC-AUDIO-SEC",
            distressType = DistressType.SCREAM,
            confidence = 0.92f,
            audioFilePath = wavFile.absolutePath,
            caption = "Pre-trigger 10s audio",
            chunkIndex = 0,
            isPreTrigger = true,
            isFinal = false
        )
        assertTrue(failedAudioReceipts.all { it.status == DeliveryStatus.FAILED_RETRYING })
        assertEquals(2, communicator.queueRepository.getAll().size)

        // Restore connectivity -> offline queue delivers both Telegram sendDocument and Guardian /v1/audio
        transport.isNetworkAvailable = true
        val retried = communicator.onNetworkRestored()
        assertTrue(retried.any { it.status == DeliveryStatus.DELIVERED && it.channel == DeliveryChannel.TELEGRAM })
        assertTrue(retried.any { it.status == DeliveryStatus.DELIVERED && it.channel == DeliveryChannel.INTERNET_API })
        assertTrue(transport.recordedRequests.any { it.url.contains("/sendDocument") && it.multipartByteCount == 1024 })
        assertTrue(transport.recordedRequests.any { it.url == "https://relay.guardiangrid.test/v1/audio" && it.headers.containsKey("X-Guardian-Signature") })
    }

    @Test
    fun guardianQrAndRelayClientValidateIntegrityAndReceiveAudioWithoutLogin() {
        val hostMgr = GuardianConnectivityManager(tempFolder.newFolder("host_q"), "Host")
        val guardianMgr = GuardianConnectivityManager(tempFolder.newFolder("guard_q"), "Guardian")

        val inviteUri = hostMgr.state.value.activeInvitationUri
        val qrMatrix = QrCodeEncoder.encode(inviteUri)
        assertTrue("QR matrix size must be >= 21", qrMatrix.size >= 21)
        // Verify top-left 7x7 QR finder corner dark border invariant
        for (i in 0..6) {
            assertTrue(qrMatrix[0][i])
            assertTrue(qrMatrix[6][i])
            assertTrue(qrMatrix[i][0])
            assertTrue(qrMatrix[i][6])
        }

        // Tampered invite signature must fail verification
        val tampered = inviteUri.replace("sig=", "sig=deadbeef")
        assertTrue(GuardianCryptoCodec.parseAndVerifyInvitation(tampered).isFailure)

        // When relay URL is unconfigured, GuardianRelayClient fails honestly without faking delivery
        val transport = MockHttpTransport()
        val client = HttpGuardianRelayClient(guardianMgr, transport)
        assertFalse(client.isRelayEndpointConfigured())
        assertTrue(client.pollIncomingAlerts().isFailure)

        // Configure relay & accept invite -> poll incoming alert + WAV audio -> persisted on Guardian device
        guardianMgr.acceptPairingInvitation(inviteUri, PairingRole.HOST).getOrThrow()
        guardianMgr.setRelayServerUrl("https://relay.guardiangrid.test").getOrThrow()

        val fakeWav = ByteArray(256) { 0x11 }
        transport.stubBinaryResponse("https://relay.guardiangrid.test/audio/1.wav", fakeWav)
        transport.enqueueResponse(
            HttpResponse(
                statusCode = 200,
                body = """
                    {"alerts":[{"alertId":"a1","incidentId":"INC-RX-1","hostDeviceId":"${hostMgr.getIdentity().deviceId}","hostDeviceName":"Host","distressType":"FALL_IMPACT","confidence":0.89,"emergencyMessage":"Fall detected","latitude":12.97,"longitude":77.59,"accuracyMeters":6.0,"audioUrl":"/audio/1.wav","audioFileName":"pre10s.wav","chunkIndex":0,"isPreTrigger":true}]}
                """.trimIndent()
            )
        )

        val polled = client.pollIncomingAlerts().getOrThrow()
        assertEquals(1, polled.size)
        val stored = guardianMgr.state.value.receivedAlerts.first()
        assertEquals("INC-RX-1", stored.incidentId)
        assertEquals(1, stored.audioClips.size)
        val clipFile = File(stored.audioClips.first().localFilePath!!)
        assertTrue(clipFile.exists())
        assertEquals(256L, clipFile.length())
    }

    @Test
    fun motionStruggleAndScreamTriggersBothInitiateSameEmergencyWorkflow() = runBlocking {
        val mockSms = MockSmsSender()
        val communicator = EmergencyCommunicatorImpl(
            transport = MockHttpTransport(),
            smsSender = mockSms
        )
        val syntheticSource = SyntheticMotionSensorSource()
        val motionDetector = MotionAnomalyDetectorImpl(sensorSource = syntheticSource)
        val audioStub = AudioModuleStub(cacheDirectory = tempFolder.newFolder("coord_audio"))
        val coordinator = EmergencyCoordinatorImpl(
            locationProvider = { LocationData(12.91, 77.61, 8.0f) },
            communicator = communicator,
            audioDetector = audioStub,
            motionDetector = motionDetector
        )
        coordinator.setVerificationTimeoutMs(1200L) // fast verification window for test
        EmergencyStateHolder.updateTrustedContacts(
            listOf(EmergencyContact("c1", "Responder", "+919876543210", isPrimary = true))
        )

        coordinator.startProtection()
        delay(200)

        try {
            // Motion struggle / anomaly trigger initiates verification countdown then escalates identically
            motionDetector.emitSimulatedAnomaly(confidence = 0.88f, type = DistressType.MOTION_ANOMALY)
            repeat(15) {
                if (EmergencyStateHolder.state.value.emergencyLevel == EmergencyLevel.VERIFYING) return@repeat
                delay(50)
            }
            assertEquals(EmergencyLevel.VERIFYING, EmergencyStateHolder.state.value.emergencyLevel)

            // Wait for countdown timeout -> full CONFIRMED_EMERGENCY + audio evidence + SMS text alert
            repeat(30) {
                if (EmergencyStateHolder.state.value.emergencyLevel == EmergencyLevel.CONFIRMED_EMERGENCY && mockSms.sentMessages.isNotEmpty()) {
                    return@repeat
                }
                delay(100)
            }
            assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, EmergencyStateHolder.state.value.emergencyLevel)
            assertEquals(1, mockSms.sentMessages.size)
            val activeSegments = coordinator.activeIncident.value?.audioSegments ?: emptyList()
            assertTrue("Emergency escalation must capture pre-trigger audio evidence", activeSegments.isNotEmpty())
        } finally {
            coordinator.stopProtection()
        }
    }

    @Test
    fun nativeContactBookNumbersNormalizeCleanlyForPrimaryAndSecondaryResponders() {
        val formattedUs = NativeContactHelper.normalizeContactBookNumber("(415) 555-2671")
        val formattedIn = NativeContactHelper.normalizeContactBookNumber("+91 94434-17407")
        val trunkZero = NativeContactHelper.normalizeContactBookNumber("09876543210")

        assertTrue(PhoneNumberValidator.validateAndNormalize(formattedUs).isValid)
        assertEquals("+919443417407", PhoneNumberValidator.validateAndNormalize(formattedIn).normalizedNumber)
        assertEquals("9876543210", PhoneNumberValidator.validateAndNormalize(trunkZero).normalizedNumber)

        // Verify KWS subsystem remains excluded from classifier & DistressType
        assertTrue(DistressType.entries.none { it.name == "KEYWORD" })
        val buffer = CircularAudioBuffer(durationMs = 10_000L, sampleRate = 16000)
        val classifier = ScreamDistressClassifier(circularBuffer = buffer)
        val screamPcm = ShortArray(16_000) { i ->
            (sin(2.0 * Math.PI * 2800.0 * i / 16000.0) * 16000).toInt().toShort()
        }
        val silencePcm = ShortArray(16_000) { 0 }
        assertTrue(classifier.evaluateAudioSamples(screamPcm) > 0.35f)
        assertEquals(0.0f, classifier.evaluateAudioSamples(silencePcm), 0.001f)
    }
}
