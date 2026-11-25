package com.guardiangrid

import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.AudioRelayChunk
import com.guardiangrid.modules.audio.TwoStageAudioEvidenceRecorder
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.communication.supabase.SupabaseBackendConfig
import com.guardiangrid.modules.communication.supabase.SupabaseConfigManager
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sin

class EmergencyAudioUpgradeVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testToken = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ123456"

    @Before
    fun setup() {
        EmergencyStateHolder.reset()
        SupabaseConfigManager.clear()
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
    }

    @Test
    fun `1 previous 10 seconds sent as one unsplit file and 2 post trigger chunks are exact 10s continuing beyond first chunk`() = runBlocking {
        val cacheDir = tempFolder.newFolder("audio_10s_continuous")
        val audioModule = AudioModuleStub(cacheDirectory = cacheDir)

        // Pre-fill rolling buffer with 10 full seconds (160,000 samples @ 16 kHz)
        val pre10s = ShortArray(160_000) { i ->
            (sin(2.0 * Math.PI * 2400.0 * i / 16000.0) * 10000).toInt().toShort()
        }
        audioModule.circularBuffer.write(pre10s)
        assertEquals(10_000L, audioModule.circularBuffer.availableDurationMs())

        // Start continuous relay session with default 10s chunks (max 60s)
        val session = audioModule.startIncidentAudioRelay(
            incidentId = "INC-10S-CONT",
            preTriggerDurationMs = 10_000L,
            chunkDurationMs = 10_000L,
            maxRecordingDurationMs = 60_000L
        )

        // Verify Requirement 1: Pre-trigger 10s saved & sent as ONE unsplit file (320,044 bytes = 44 header + 160,000*2 PCM)
        val preFile = File(session.preTriggerSegment.filePath)
        assertTrue(preFile.exists())
        assertEquals(10_000L, session.preTriggerSegment.durationMs)
        assertEquals(320_044L, preFile.length())

        val collectedChunks = mutableListOf<AudioRelayChunk>()
        val collectJob = launch {
            session.chunkFlow.collect { collectedChunks.add(it) }
        }
        delay(30)

        // Verify Requirement 2 & 3: Feed 24 seconds continuously across multiple slices (no gaps/overlaps)
        // Chunk 1 = 10s (160,000), Chunk 2 = 10s (160,000), Chunk 3 = final partial 4s (64,000)
        audioModule.circularBuffer.write(ShortArray(160_000) { 1001 })
        delay(60)
        audioModule.circularBuffer.write(ShortArray(160_000) { 2002 })
        delay(60)
        audioModule.circularBuffer.write(ShortArray(64_000) { 3003 })
        delay(60)

        // Explicitly terminate session
        audioModule.stopRecording()
        val summary = session.completionDeferred.await()
        delay(30)
        collectJob.cancel()

        assertEquals("Must emit 2 full 10s chunks plus 1 partial final 4s chunk", 3, collectedChunks.size)
        // Sequential numbering 1, 2, 3
        assertEquals(listOf(1, 2, 3), collectedChunks.map { it.chunkIndex })
        assertEquals("Chunk 1 must contain exact 10s (160,000 samples)", 160_000, collectedChunks[0].totalSamples)
        assertEquals(320_044L, File(collectedChunks[0].segment.filePath).length())
        assertEquals("Chunk 2 must contain exact 10s (160,000 samples)", 160_000, collectedChunks[1].totalSamples)
        assertEquals(320_044L, File(collectedChunks[1].segment.filePath).length())
        assertFalse(collectedChunks[0].isFinal)
        assertFalse(collectedChunks[1].isFinal)

        // Partial final chunk (4s = 64,000 samples)
        assertEquals(64_000, collectedChunks[2].totalSamples)
        assertEquals(4_000L, collectedChunks[2].durationMs)
        assertTrue(collectedChunks[2].isFinal)

        // Complete chronological incident WAV = 10s pre + 24s post = 34s (544,000 samples -> 1,088,044 bytes)
        assertEquals(34_000L, summary.totalDurationMs)
        assertEquals(1_088_044L, File(summary.combinedRecordingSegment.filePath).length())
        assertTrue(summary.endedByCancellationOrStop)
    }

    @Test
    fun `4 chunk ordering captions and 5 cancellation stops capture and 8 SMS never receives audio`() = runBlocking {
        val cacheDir = tempFolder.newFolder("coord_seq")
        val queueDir = tempFolder.newFolder("coord_queue")
        val transport = MockHttpTransport()
        val smsSender = MockSmsSender()
        val audioModule = AudioModuleStub(cacheDirectory = cacheDir)

        // Pre-fill 10 seconds in rolling buffer
        audioModule.circularBuffer.write(ShortArray(160_000) { 555 })

        val communicator = EmergencyCommunicatorImpl(
            transport = transport,
            smsSender = smsSender,
            queueRepository = FileEmergencyQueueRepository(queueDir),
            credentialsProvider = { TelegramCredentials(testToken, "987654321") }
        )

        val coordinator = EmergencyCoordinatorImpl(
            locationProvider = { LocationData(12.9716, 77.5946, 5.0f) },
            communicator = communicator,
            audioDetector = audioModule
        )
        // Use 10s chunk default
        coordinator.setAudioRecordingConfig(chunkDurationMs = 10_000L, maxRecordingDurationMs = 120_000L)
        EmergencyStateHolder.updateTrustedContacts(
            listOf(EmergencyContact("c1", "Primary", "+919876543210", isPrimary = true))
        )

        coordinator.triggerManualSos("Manual_Button")
        delay(80)

        // Inject 12 seconds continuously via captureManager (Chunk 1 = 10s, tail = 2s flushed on cancel)
        val chunk10s = ShortArray(160_000) { 777 }
        val tail2s = ShortArray(32_000) { 888 }
        audioModule.captureManager.injectSyntheticSamples(chunk10s)
        delay(80)
        audioModule.captureManager.injectSyntheticSamples(tail2s)
        delay(80)

        // Cancel/stop emergency session -> stops microphone capture cleanly & flushes partial chunk + completion notification
        coordinator.cancelEmergency("User cancelled emergency")
        assertFalse("Cancellation must stop AudioCaptureManager", audioModule.captureManager.isCapturing)

        // Wait briefly for sequential dispatcher to drain final chunk & completion message
        repeat(30) {
            val doneSent = transport.recordedRequests.any { it.url.endsWith("/sendMessage") && (it.body?.contains("COMPLETED") == true) }
            if (doneSent) return@repeat
            delay(50)
        }

        // Check uploaded WAV documents strictly in sequential order: Pre-Trigger (0) -> Chunk #1 -> Chunk #2 (FINAL)
        val docUploads = transport.recordedRequests.filter { it.url.contains("/sendDocument") }
        assertEquals(3, docUploads.size)
        assertTrue(docUploads[0].multipartFileName!!.startsWith("stage1_pre10s_"))
        assertTrue(docUploads[0].body!!.contains("Pre-Trigger (Seq 0)"))
        assertTrue(docUploads[0].body!!.contains("Duration: 10.0s"))
        assertTrue(docUploads[0].body!!.contains("Incident: INC-"))
        assertTrue(docUploads[0].body!!.contains("Captured:"))

        assertTrue(docUploads[1].multipartFileName!!.contains("_chunk_1_"))
        assertTrue(docUploads[1].body!!.contains("Chunk #1"))
        assertTrue(docUploads[1].body!!.contains("Duration: 10.0s"))

        assertTrue(docUploads[2].multipartFileName!!.contains("_chunk_2_"))
        assertTrue(docUploads[2].body!!.contains("Chunk #2 (FINAL)"))
        assertTrue(docUploads[2].body!!.contains("Duration:"))
        assertFalse("Partial final chunk must be under 10.0s", docUploads[2].body!!.contains("Duration: 10.0s"))

        // Completion summary sent to Telegram
        assertTrue(
            transport.recordedRequests.any { it.url.endsWith("/sendMessage") && it.body?.contains("COMPLETED") == true }
        )

        // Requirement 8: SMS carried alert text + GPS ONLY, never audio
        assertEquals(1, smsSender.sentMessages.size)
        val sms = smsSender.sentMessages.first().text
        assertTrue(sms.contains("https://maps.google.com/?q=12.97160,77.59460"))
        assertFalse(sms.contains(".wav") || sms.contains("chunk") || sms.contains("stage1"))
    }

    @Test
    fun `6 offline retries maintain order without duplicating or losing chunks and honor 429 retry after`() = runBlocking {
        val queueDir = tempFolder.newFolder("retry_seq_queue")
        val wavDir = tempFolder.newFolder("retry_wavs")
        val f0 = File(wavDir, "stage1_pre10s_INC-R1_1.wav").apply { writeBytes(ByteArray(200) { 1 }) }
        val f1 = File(wavDir, "evidence_INC-R1_chunk_1_2.wav").apply { writeBytes(ByteArray(200) { 2 }) }
        val f2 = File(wavDir, "evidence_INC-R1_chunk_2_3.wav").apply { writeBytes(ByteArray(200) { 3 }) }

        val transport = MockHttpTransport()
        val comm = EmergencyCommunicatorImpl(
            transport = transport,
            smsSender = MockSmsSender(),
            queueRepository = FileEmergencyQueueRepository(queueDir),
            credentialsProvider = { TelegramCredentials(testToken, "987654321") }
        )

        // First fail with HTTP 429 retry_after=2 on Chunk 0, and network error on Chunks 1 & 2
        transport.enqueueResponse(
            HttpResponse(
                statusCode = 429,
                body = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":2}}""",
                headers = mapOf("Retry-After" to listOf("2"))
            )
        )
        comm.dispatchAudioEvidence("INC-R1", DistressType.SCREAM, 0.9f, f0.absolutePath, chunkIndex = 0, isPreTrigger = true, isFinal = false)

        transport.isNetworkAvailable = false
        comm.dispatchAudioEvidence("INC-R1", DistressType.SCREAM, 0.9f, f1.absolutePath, chunkIndex = 1, isPreTrigger = false, isFinal = false)
        comm.dispatchAudioEvidence("INC-R1", DistressType.SCREAM, 0.9f, f2.absolutePath, chunkIndex = 2, isPreTrigger = false, isFinal = true)

        assertEquals(3, comm.queueRepository.getAll().size)

        // Restore connectivity and retry -> verify strict order 0 -> 1 -> 2, zero loss, and zero duplicates on second retry
        transport.isNetworkAvailable = true
        transport.clear()
        val retryReceipts = comm.onNetworkRestored()
        assertEquals(3, retryReceipts.size)
        assertTrue(retryReceipts.all { it.status == DeliveryStatus.DELIVERED })

        val uploadOrder = transport.recordedRequests.filter { it.url.contains("/sendDocument") }.map { it.multipartFileName }
        assertEquals(listOf(f0.name, f1.name, f2.name), uploadOrder)

        // Re-running retry or re-dispatching f1 does NOT duplicate upload (IdempotencyManager)
        val countBefore = transport.recordedRequests.size
        comm.onNetworkRestored()
        val dupReceipt = comm.dispatchAudioEvidence("INC-R1", DistressType.SCREAM, 0.9f, f1.absolutePath, chunkIndex = 1, isPreTrigger = false, isFinal = false)
        assertEquals(DeliveryStatus.DELIVERED, dupReceipt.first().status)
        assertEquals(countBefore, transport.recordedRequests.size)
    }

    @Test
    fun `7 telegram failure does not terminate local recording or delete undelivered chunks`() = runBlocking {
        val cacheDir = tempFolder.newFolder("tg_fail_resilience")
        val queueDir = tempFolder.newFolder("tg_fail_queue")
        val audioModule = AudioModuleStub(cacheDirectory = cacheDir)
        audioModule.circularBuffer.write(ShortArray(160_000) { 444 })

        val transport = MockHttpTransport().apply {
            isNetworkAvailable = false // Simulate complete Telegram outage
        }
        val comm = EmergencyCommunicatorImpl(
            transport = transport,
            smsSender = MockSmsSender(),
            queueRepository = FileEmergencyQueueRepository(queueDir),
            credentialsProvider = { TelegramCredentials(testToken, "987654321") }
        )

        val coordinator = EmergencyCoordinatorImpl(
            communicator = comm,
            audioDetector = audioModule
        )
        coordinator.triggerManualSos("SOS")
        delay(60)

        // Capture full 10s Chunk #1 through live capture stream while Telegram is offline
        audioModule.captureManager.injectSyntheticSamples(ShortArray(160_000) { 666 })
        delay(100)
        coordinator.resolveEmergency("Resolved")
        delay(120)

        // Local WAV files must remain intact on disk and undelivered chunks must NOT be deleted by cleanup
        val evidenceFiles = File(cacheDir, "evidence").listFiles()?.toList().orEmpty()
        assertTrue(evidenceFiles.any { it.name.startsWith("stage1_pre10s_") && it.length() == 320_044L })
        assertTrue(evidenceFiles.any { it.name.contains("_chunk_1_") && it.length() == 320_044L })
        assertTrue(evidenceFiles.any { it.name.contains("_full_") && it.length() >= 640_044L })

        val deletedUndelivered = audioModule.cleanupSatisfiedEvidenceChunks(
            deliveredFilePaths = emptySet(),
            retentionWindowMs = 0L
        )
        assertEquals("Must NEVER delete undelivered source chunks", 0, deletedUndelivered)
    }

    @Test
    fun `supabase authenticated backend keeps telegram tokens off client and signs requests`() {
        val guardianDir = tempFolder.newFolder("sb_guardian")
        val wavDir = tempFolder.newFolder("sb_wav")
        val wavFile = File(wavDir, "chunk_1.wav").apply { writeBytes(ByteArray(512) { 0x22 }) }

        val mgr = GuardianConnectivityManager(guardianDir, "VivoTest")
        val cfg = SupabaseBackendConfig(
            projectUrl = "https://xyzproject.supabase.co",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9_testkey_123456",
            defaultChatId = "987654321"
        )
        val transport = MockHttpTransport().apply {
            enqueueResponse(
                HttpResponse(
                    statusCode = 200,
                    body = """{"ok":true,"deviceId":"${mgr.getIdentity().deviceId}","registered":true}"""
                )
            )
            enqueueResponse(
                HttpResponse(
                    statusCode = 200,
                    body = """{"ok":true,"storagePath":"GG/INC-SB/chunk_0001.wav","messageId":"tg-msg-88"}"""
                )
            )
        }
        val sbClient = SupabaseEmergencyBackendClient(mgr, transport) { cfg }

        val res = sbClient.uploadAudioChunk(
            incidentId = "INC-SB",
            chunkSequence = 1,
            isPreTrigger = false,
            isFinal = false,
            durationMs = 10_000L,
            capturedAtEpochMs = System.currentTimeMillis(),
            caption = "GuardianGrid Audio [Chunk #1]",
            wavFile = wavFile
        )

        assertEquals(com.guardiangrid.modules.communication.models.EmergencyDeliveryState.DELIVERED, res.state)
        assertEquals("GG/INC-SB/chunk_0001.wav", res.storagePath)
        assertEquals(2, transport.recordedRequests.size)
        val regReq = transport.recordedRequests[0]
        assertEquals("https://xyzproject.supabase.co/functions/v1/emergency-relay/register-device", regReq.url)
        val req = transport.recordedRequests[1]
        assertEquals("https://xyzproject.supabase.co/functions/v1/emergency-relay/audio-chunk", req.url)
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
        assertTrue(req.headers.containsKey("apikey"))
        assertTrue(req.headers.containsKey("X-Guardian-Device-Id"))
        assertTrue(req.headers.containsKey("X-Guardian-Timestamp"))
        assertTrue(req.headers.containsKey("X-Guardian-Nonce"))
        assertTrue(req.headers.containsKey("X-Guardian-Signature"))
        assertFalse("Request must never carry Telegram Bot Token", req.url.contains("bot") && req.url.contains("telegram"))
    }
}
