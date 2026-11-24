package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.modules.communication.queue.DeliveryQueueManagerImpl
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Production validation for offline persistent queueing,
 * exponential backoff retry calculations, and state recovery across restarts.
 */
class OfflineQueueAndRetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var queueDir: File
    private lateinit var queueRepo: FileEmergencyQueueRepository

    @Before
    fun setUp() {
        queueDir = tempFolder.newFolder("offline_queue_test")
        queueRepo = FileEmergencyQueueRepository(queueDir)
    }

    // --- 1. Enqueue and Observation ---

    @Test
    fun testEnqueueAndObserveQueueFlow() = runBlocking {
        val manager = DeliveryQueueManagerImpl(repository = queueRepo)

        val payload = AlertPayload(
            incidentId = "INC-QUEUE-01",
            distressType = DistressType.SCREAM,
            confidence = 0.95f,
            recipientContacts = listOf(EmergencyContact(id = "c1", name = "Responder", phoneNumber = "+14155552671", isPrimary = true))
        )

        val queueId = manager.enqueue(payload, DeliveryChannel.TELEGRAM)
        assertNotNull(queueId)

        val queueList = manager.observeQueue().first()
        assertEquals(1, queueList.size)
        assertEquals(queueId, queueList[0].queueId)
        assertEquals(DeliveryStatus.PENDING, queueList[0].status)
        assertEquals("INC-QUEUE-01", queueList[0].payload.incidentId)
    }

    // --- 2. Exponential Backoff Delay Calculation ---

    @Test
    fun testExponentialBackoffCalculations() {
        val manager = DeliveryQueueManagerImpl(
            repository = queueRepo,
            baseDelayMs = 2_000L,
            maxDelayMs = 60_000L
        )

        // 2000 * 2^0 = 2,000ms
        assertEquals(2_000L, manager.calculateBackoffDelayMs(0))
        // 2000 * 2^1 = 4,000ms
        assertEquals(4_000L, manager.calculateBackoffDelayMs(1))
        // 2000 * 2^2 = 8,000ms
        assertEquals(8_000L, manager.calculateBackoffDelayMs(2))
        // 2000 * 2^3 = 16,000ms
        assertEquals(16_000L, manager.calculateBackoffDelayMs(3))
        // Cap at maxDelayMs (60,000ms)
        assertEquals(60_000L, manager.calculateBackoffDelayMs(10))
    }

    // --- 3. Retry Status Transitions & Abandonment ---

    @Test
    fun testFailedRetryTransitionsToRetryingThenDelivered() = runBlocking {
        var shouldSucceed = false

        val manager = DeliveryQueueManagerImpl(
            repository = queueRepo,
            dispatcherAction = { _, _ ->
                if (shouldSucceed) {
                    DeliveryReceipt(channel = DeliveryChannel.TELEGRAM, status = DeliveryStatus.DELIVERED)
                } else {
                    DeliveryReceipt(channel = DeliveryChannel.TELEGRAM, status = DeliveryStatus.FAILED_RETRYING, errorMessage = "Temporary network error")
                }
            }
        )

        val payload = AlertPayload(incidentId = "INC-RETRY-01", distressType = DistressType.MANUAL_SOS, confidence = 1.0f)
        val queueId = manager.enqueue(payload, DeliveryChannel.TELEGRAM)

        // Initial state is PENDING
        assertEquals(DeliveryStatus.PENDING, queueRepo.getById(queueId)?.status)

        // First retry attempt fails -> transitions to FAILED_RETRYING
        val failReceipts = manager.retryPendingEvents()
        assertEquals(1, failReceipts.size)
        assertEquals(DeliveryStatus.FAILED_RETRYING, failReceipts[0].status)

        val retryingEvent = queueRepo.getById(queueId)
        assertEquals(DeliveryStatus.FAILED_RETRYING, retryingEvent?.status)
        assertEquals(1, retryingEvent?.retryCount)

        // Force next retry time to now
        queueRepo.save(retryingEvent!!.copy(nextRetryEpochMs = System.currentTimeMillis() - 100))

        // Second retry succeeds -> transitions to DELIVERED
        shouldSucceed = true
        val successReceipts = manager.retryPendingEvents()
        assertEquals(1, successReceipts.size)
        assertEquals(DeliveryStatus.DELIVERED, successReceipts[0].status)

        val deliveredEvent = queueRepo.getById(queueId)
        assertEquals(DeliveryStatus.DELIVERED, deliveredEvent?.status)
    }

    @Test
    fun testMaxRetriesExceededTransitionsToAbandoned() = runBlocking {
        val manager = DeliveryQueueManagerImpl(
            repository = queueRepo,
            maxRetries = 2,
            dispatcherAction = { _, _ ->
                DeliveryReceipt(channel = DeliveryChannel.TELEGRAM, status = DeliveryStatus.FAILED_RETRYING, errorMessage = "Permanent offline")
            }
        )

        val payload = AlertPayload(incidentId = "INC-ABANDON-01", distressType = DistressType.MANUAL_SOS, confidence = 1.0f)
        val queueId = manager.enqueue(payload, DeliveryChannel.TELEGRAM)

        // Attempt 1
        manager.retryPendingEvents()
        val e1 = queueRepo.getById(queueId)
        assertEquals(DeliveryStatus.FAILED_RETRYING, e1?.status)
        assertEquals(1, e1?.retryCount)

        // Reset delay to immediate
        queueRepo.save(e1!!.copy(nextRetryEpochMs = System.currentTimeMillis() - 100))

        // Attempt 2 (reaches maxRetries = 2) -> marks ABANDONED
        manager.retryPendingEvents()
        val e2 = queueRepo.getById(queueId)
        assertEquals(DeliveryStatus.ABANDONED, e2?.status)
        assertEquals(2, e2?.retryCount)
    }

    // --- 4. Queue Clearing and Item Deletion ---

    @Test
    fun testDeleteIndividualQueuedItem() = runBlocking {
        val manager = DeliveryQueueManagerImpl(repository = queueRepo)
        val payload = AlertPayload(incidentId = "INC-DEL-01", distressType = DistressType.SCREAM, confidence = 0.9f)

        val q1 = manager.enqueue(payload, DeliveryChannel.TELEGRAM)
        val q2 = manager.enqueue(payload, DeliveryChannel.SMS)

        assertEquals(2, queueRepo.getAll().size)

        manager.deleteQueuedItem(q1)
        assertNull(queueRepo.getById(q1))
        assertNotNull(queueRepo.getById(q2))
        assertEquals(1, queueRepo.getAll().size)
    }

    @Test
    fun testClearEntireQueue() = runBlocking {
        val manager = DeliveryQueueManagerImpl(repository = queueRepo)
        val payload = AlertPayload(incidentId = "INC-CLEAR-01", distressType = DistressType.SCREAM, confidence = 0.9f)

        manager.enqueue(payload, DeliveryChannel.TELEGRAM)
        manager.enqueue(payload, DeliveryChannel.SMS)
        assertEquals(2, queueRepo.getAll().size)

        manager.clearQueue()
        assertEquals(0, queueRepo.getAll().size)
        assertEquals(0, manager.observeQueue().first().size)
    }

    // --- 5. Persistence Across App Restart ---

    @Test
    fun testQueuePersistedAcrossRepositoryReload() = runBlocking {
        val manager = DeliveryQueueManagerImpl(repository = queueRepo)
        val payload = AlertPayload(
            incidentId = "INC-PERSIST-01",
            distressType = DistressType.SCREAM,
            confidence = 0.98f,
            recipientContacts = listOf(EmergencyContact(id = "c1", name = "Contact A", phoneNumber = "+14155552671", isPrimary = true))
        )

        val qId = manager.enqueue(payload, DeliveryChannel.TELEGRAM)

        // Simulate app kill and reload by initializing a fresh FileEmergencyQueueRepository from same disk directory
        val reloadedRepo = FileEmergencyQueueRepository(queueDir)
        val reloadedEvent = reloadedRepo.getById(qId)

        assertNotNull("Queued alert must survive process termination", reloadedEvent)
        assertEquals("INC-PERSIST-01", reloadedEvent?.payload?.incidentId)
        assertEquals(DeliveryChannel.TELEGRAM, reloadedEvent?.targetChannel)
        assertEquals(DeliveryStatus.PENDING, reloadedEvent?.status)
        assertEquals("+14155552671", reloadedEvent?.payload?.recipientContacts?.first()?.phoneNumber)
    }
}
