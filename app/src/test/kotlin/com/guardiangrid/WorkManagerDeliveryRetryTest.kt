package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.QueuedEmergencyEvent
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.HttpTransport
import com.guardiangrid.modules.communication.queue.DeliveryQueueManagerImpl
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.security.TelegramCredentials
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit test suite validating Task 2: Automatic Offline Delivery Retry & WorkManager Queue Integration.
 *
 * Covers:
 * - Offline event persistence across restarts via FileEmergencyQueueRepository.
 * - Queueing when delivery encounters network failure.
 * - Exponential backoff retry calculation.
 * - Successful delivery on network recovery.
 * - Max retries exceeded -> ABANDONED transition.
 * - Idempotency suppression of duplicate transmissions.
 */
class WorkManagerDeliveryRetryTest {

    private lateinit var tempDir: File
    private lateinit var queueRepo: FileEmergencyQueueRepository

    class TestHttpTransport : HttpTransport {
        var shouldSucceed = false
        val requestCount = AtomicInteger(0)

        override fun postJson(url: String, jsonBody: String, headers: Map<String, String>): HttpResponse {
            requestCount.incrementAndGet()
            return if (shouldSucceed) {
                HttpResponse(statusCode = 200, body = "{\"ok\":true,\"result\":{\"message_id\":42}}")
            } else {
                HttpResponse(statusCode = 503, body = "{\"ok\":false,\"description\":\"Service Unavailable\"}")
            }
        }

        override fun getJson(url: String, headers: Map<String, String>): HttpResponse {
            return HttpResponse(statusCode = 200, body = "{}")
        }
    }

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_retry_test_${System.currentTimeMillis()}").apply { mkdirs() }
        queueRepo = FileEmergencyQueueRepository(storageDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testOfflineEventPersistenceAcrossRestarts() {
        val payload = AlertPayload(
            incidentId = "INC-OFFLINE-1",
            timestampEpochMs = 1700000000000L,
            distressType = DistressType.SCREAM,
            confidence = 0.95f,
            emergencyMessage = "Distress signal",
            location = null
        )
        val event = QueuedEmergencyEvent(
            queueId = "q-101",
            payload = payload,
            targetChannel = DeliveryChannel.TELEGRAM,
            status = DeliveryStatus.PENDING,
            retryCount = 0,
            nextRetryEpochMs = 1700000000000L,
            createdAtEpochMs = 1700000000000L
        )

        queueRepo.save(event)

        // Simulate app restart by creating a new repository instance from the same directory
        val restartedRepo = FileEmergencyQueueRepository(storageDir = tempDir)
        val allEvents = restartedRepo.getAll()

        assertEquals(1, allEvents.size)
        assertEquals("q-101", allEvents[0].queueId)
        assertEquals("INC-OFFLINE-1", allEvents[0].payload.incidentId)
        assertEquals(DeliveryStatus.PENDING, allEvents[0].status)
    }

    @Test
    fun testExponentialBackoffCalculation() {
        val queueManager = DeliveryQueueManagerImpl(repository = queueRepo, baseDelayMs = 2000L, maxDelayMs = 60000L)

        // Retry 0: baseDelay * 2^0 = 2000 ms
        assertEquals(2000L, queueManager.calculateBackoffDelayMs(0))
        // Retry 1: baseDelay * 2^1 = 4000 ms
        assertEquals(4000L, queueManager.calculateBackoffDelayMs(1))
        // Retry 2: baseDelay * 2^2 = 8000 ms
        assertEquals(8000L, queueManager.calculateBackoffDelayMs(2))
        // Retry 3: baseDelay * 2^3 = 16000 ms
        assertEquals(16000L, queueManager.calculateBackoffDelayMs(3))
        // Retry 10: clamped to maxDelayMs = 60000 ms
        assertEquals(60000L, queueManager.calculateBackoffDelayMs(10))
    }

    @Test
    fun testCommunicatorOfflineQueueingAndWorkManagerTrigger() = runBlocking {
        val transport = TestHttpTransport().apply { shouldSucceed = false }
        val retryTriggered = AtomicBoolean(false)

        val communicator = EmergencyCommunicatorImpl(
            transport = transport,
            queueRepository = queueRepo,
            credentialsProvider = { TelegramCredentials("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345", "999") },
            onOfflineRetryScheduled = { retryTriggered.set(true) }
        )

        val payload = AlertPayload(
            incidentId = "INC-RETRY-TEST",
            timestampEpochMs = System.currentTimeMillis(),
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            emergencyMessage = "Test manual SOS",
            location = null
        )

        // Dispatch alert while offline -> must fail and enqueue
        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))

        assertEquals(1, receipts.size)
        assertEquals(DeliveryStatus.FAILED_RETRYING, receipts[0].status)
        assertTrue("WorkManager retry schedule hook must be invoked", retryTriggered.get())

        // Verify queued in persistent storage
        val queuedItems = queueRepo.getAll()
        assertEquals(1, queuedItems.size)
        assertEquals("INC-RETRY-TEST", queuedItems[0].payload.incidentId)
        assertEquals(DeliveryStatus.PENDING, queuedItems[0].status)

        // Now simulate network recovery: transport becomes successful
        transport.shouldSucceed = true

        val retryReceipts = communicator.queueManager.retryPendingEvents()
        assertEquals(1, retryReceipts.size)
        assertEquals(DeliveryStatus.DELIVERED, retryReceipts[0].status)

        val updatedQueue = queueRepo.getAll()
        assertEquals(DeliveryStatus.DELIVERED, updatedQueue[0].status)
    }

    @Test
    fun testMaxRetriesTransitionsToAbandoned() = runBlocking {
        val transport = TestHttpTransport().apply { shouldSucceed = false }
        val queueManager = DeliveryQueueManagerImpl(
            repository = queueRepo,
            maxRetries = 2,
            baseDelayMs = 0L,
            dispatcherAction = { _, channel ->
                DeliveryReceipt(channel = channel, status = DeliveryStatus.FAILED_RETRYING, errorMessage = "Network timeout")
            }
        )

        val payload = AlertPayload(
            incidentId = "INC-ABANDON",
            timestampEpochMs = System.currentTimeMillis(),
            distressType = DistressType.FALL_IMPACT,
            confidence = 0.90f,
            emergencyMessage = "Fall impact",
            location = null
        )

        queueManager.enqueue(payload, DeliveryChannel.TELEGRAM)

        // First retry attempt
        queueManager.retryPendingEvents()
        assertEquals(DeliveryStatus.FAILED_RETRYING, queueRepo.getAll()[0].status)
        assertEquals(1, queueRepo.getAll()[0].retryCount)

        // Second retry attempt (hits maxRetries = 2) -> must transition to ABANDONED
        queueManager.retryPendingEvents()
        assertEquals(DeliveryStatus.ABANDONED, queueRepo.getAll()[0].status)
        assertEquals(2, queueRepo.getAll()[0].retryCount)
    }

    @Test
    fun testDuplicateDispatchesSuppressedByIdempotency() = runBlocking {
        val transport = TestHttpTransport().apply { shouldSucceed = true }
        val communicator = EmergencyCommunicatorImpl(
            transport = transport,
            queueRepository = queueRepo,
            credentialsProvider = { TelegramCredentials("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345", "999") }
        )

        val payload = AlertPayload(
            incidentId = "INC-DUP-1",
            timestampEpochMs = System.currentTimeMillis(),
            distressType = DistressType.SCREAM,
            confidence = 0.95f,
            emergencyMessage = "Idempotent alert",
            location = null
        )

        // First dispatch -> successful
        val r1 = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))
        assertEquals(DeliveryStatus.DELIVERED, r1[0].status)
        assertEquals(1, transport.requestCount.get())

        // Duplicate dispatch with exact same incident ID and channel -> suppressed
        val r2 = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))
        assertEquals(DeliveryStatus.DELIVERED, r2[0].status)
        assertEquals("Duplicate alert must not trigger another HTTP network request", 1, transport.requestCount.get())
    }
}
