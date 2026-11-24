package com.guardiangrid.tests

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.queue.DeliveryQueueManagerImpl
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.queue.IdempotencyManager
import com.guardiangrid.modules.communication.queue.InMemoryQueueRepository
import com.guardiangrid.modules.communication.security.RecipientAuthorizer
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.communication.telegram.TelegramEmergencyDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Independent verification test suite for GuardianGrid's Emergency Communication Subsystem (Member 3).
 *
 * Verifies:
 * 1. Successful Telegram delivery with Google Maps location links.
 * 2. Invalid credentials handling (HTTP 401 & unconfigured token).
 * 3. Graceful fallback on missing GPS coordinates.
 * 4. Network disconnection and automated offline queueing.
 * 5. Exponential backoff retry execution upon network restoration.
 * 6. Idempotent deduplication to prevent duplicate alert delivery.
 * 7. Delivery status lifecycle (GENERATED -> TRANSMISSION_ATTEMPTED -> DELIVERED -> ACKNOWLEDGED).
 * 8. Strict distinction between API acceptance (DELIVERED) and human confirmation (ACKNOWLEDGED).
 * 9. SMS fallback formatting under 160 characters.
 * 10. Privacy protection: stripping audio evidence from unauthorized contacts.
 * 11. Security invariants: token and phone masking.
 * 12. Persistent file queue survival across process restarts.
 */
class CommunicationModuleTest {

    private lateinit var mockHttp: MockHttpTransport
    private lateinit var mockSms: MockSmsSender
    private lateinit var queueRepo: InMemoryQueueRepository
    private lateinit var communicator: EmergencyCommunicatorImpl

    private val validTestCredentials = TelegramCredentials(
        botToken = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345",
        defaultChatId = "-1001234567890"
    )

    private val sampleContact = EmergencyContact(
        id = "c-1",
        name = "Jane Doe",
        phoneNumber = "+15551234567",
        telegramChatId = "-1001234567890",
        isPrimary = true
    )

    private val sampleLocation = LocationData(
        latitude = 12.971598,
        longitude = 77.594562,
        accuracyMeters = 8.5f,
        altitudeMeters = 920.0,
        speedMps = 0.0f
    )

    @Before
    fun setUp() {
        mockHttp = MockHttpTransport()
        mockSms = MockSmsSender()
        queueRepo = InMemoryQueueRepository()
        TelegramConfigManager.setCredentials(validTestCredentials)

        communicator = EmergencyCommunicatorImpl(
            transport = mockHttp,
            smsSender = mockSms,
            queueRepository = queueRepo,
            credentialsProvider = { TelegramConfigManager.getCredentials() },
            idempotencyManager = IdempotencyManager()
        )
    }

    @After
    fun tearDown() {
        TelegramConfigManager.clear()
        mockHttp.clear()
        mockSms.clear()
    }

    // --- Test 1: Successful Telegram Delivery ---

    @Test
    fun testSuccessfulTelegramDelivery() = runBlocking {
        val payload = AlertPayload(
            incidentId = "INC-1001",
            distressType = DistressType.SCREAM,
            confidence = 0.94f,
            location = sampleLocation,
            emergencyMessage = "Acoustic scream detected. Immediate assistance required.",
            recipientContacts = listOf(sampleContact)
        )

        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))

        assertEquals(1, receipts.size)
        val receipt = receipts.first()
        assertEquals(DeliveryChannel.TELEGRAM, receipt.channel)
        assertEquals(DeliveryStatus.DELIVERED, receipt.status)
        assertEquals("99991", receipt.messageId)
        assertEquals(EmergencyDeliveryState.DELIVERED, communicator.lastDeliveryState.value)

        // Verify request payload contained Google Maps URL
        assertEquals(1, mockHttp.recordedRequests.size)
        val recordedReq = mockHttp.recordedRequests.first()
        assertTrue(recordedReq.body!!.contains("https://maps.google.com/?q=12.971598,77.594562"))
        assertTrue(recordedReq.body!!.contains("INC-1001"))
        assertTrue(recordedReq.body!!.contains("SCREAM"))
    }

    // --- Test 2: Invalid Telegram Credentials ---

    @Test
    fun testInvalidTelegramCredentials() = runBlocking {
        // Configure bad token that returns HTTP 401 Unauthorized
        mockHttp.enqueueResponse(
            HttpResponse(
                statusCode = 401,
                body = """{"ok": false, "error_code": 401, "description": "Unauthorized"}"""
            )
        )

        val payload = AlertPayload(
            incidentId = "INC-1002",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            location = sampleLocation,
            emergencyMessage = "Manual SOS pressed.",
            recipientContacts = listOf(sampleContact)
        )

        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))

        assertEquals(1, receipts.size)
        val receipt = receipts.first()
        assertEquals(DeliveryStatus.FAILED_RETRYING, receipt.status)
        assertTrue(receipt.errorMessage!!.contains("HTTP 401 Unauthorized"))
        assertEquals(EmergencyDeliveryState.FAILED, communicator.lastDeliveryState.value)
    }

    // --- Test 3: Missing GPS Location Handling ---

    @Test
    fun testMissingLocationHandling() = runBlocking {
        val payloadWithoutLocation = AlertPayload(
            incidentId = "INC-1003",
            distressType = DistressType.FALL_IMPACT,
            confidence = 0.88f,
            location = null, // GPS unavailable
            emergencyMessage = "Fall impact detected in underground area.",
            recipientContacts = listOf(sampleContact)
        )

        val dispatcher = TelegramEmergencyDispatcher(mockHttp) { validTestCredentials }
        val formattedMessage = dispatcher.formatEmergencyMessage(payloadWithoutLocation)

        assertTrue(formattedMessage.contains("Location:"))
        assertTrue(formattedMessage.contains("UNAVAILABLE"))
        assertFalse(formattedMessage.contains("https://maps.google.com"))

        // Dispatch must succeed without crashing
        val receipts = communicator.dispatchAlert(payloadWithoutLocation, listOf(DeliveryChannel.TELEGRAM))
        assertEquals(DeliveryStatus.DELIVERED, receipts.first().status)
    }

    // --- Test 4: Network Disconnection & Offline Queueing ---

    @Test
    fun testNetworkUnavailableTriggersQueueing() = runBlocking {
        mockHttp.isNetworkAvailable = false // Simulate complete network disconnection

        val payload = AlertPayload(
            incidentId = "INC-1004",
            distressType = DistressType.SCREAM,
            confidence = 0.92f,
            location = sampleLocation,
            emergencyMessage = "Distress scream during blackout.",
            recipientContacts = listOf(sampleContact)
        )

        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))

        assertEquals(1, receipts.size)
        assertEquals(DeliveryStatus.FAILED_RETRYING, receipts.first().status)
        assertEquals(EmergencyDeliveryState.FAILED, communicator.lastDeliveryState.value)

        // Verify the event was saved to the offline queue
        val queuedEvents = communicator.queueManager.observeQueue().first()
        assertEquals(1, queuedEvents.size)
        assertEquals("INC-1004", queuedEvents.first().payload.incidentId)
        assertEquals(DeliveryStatus.PENDING, queuedEvents.first().status)
    }

    // --- Test 5: Queue Retry with Exponential Backoff ---

    @Test
    fun testExponentialBackoffAndRetryWhenNetworkRestored() = runBlocking {
        mockHttp.isNetworkAvailable = false

        val payload = AlertPayload(
            incidentId = "INC-1005",
            distressType = DistressType.SCREAM,
            confidence = 0.95f,
            location = sampleLocation,
            emergencyMessage = "Acoustic scream distress detected.",
            recipientContacts = listOf(sampleContact)
        )

        // Enqueue offline
        communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))
        val initialQueue = communicator.queueManager.observeQueue().first()
        assertEquals(1, initialQueue.size)
        assertEquals(0, initialQueue.first().retryCount)

        // Verify backoff calculation
        assertEquals(2000L, communicator.queueManager.calculateBackoffDelayMs(0))
        assertEquals(4000L, communicator.queueManager.calculateBackoffDelayMs(1))
        assertEquals(8000L, communicator.queueManager.calculateBackoffDelayMs(2))
        assertEquals(16000L, communicator.queueManager.calculateBackoffDelayMs(3))

        // Restore network connectivity
        mockHttp.isNetworkAvailable = true

        // Trigger network restored retry
        val retryReceipts = communicator.onNetworkRestored()
        assertEquals(1, retryReceipts.size)
        assertEquals(DeliveryStatus.DELIVERED, retryReceipts.first().status)

        // Queue status should now be updated to DELIVERED
        val updatedQueue = communicator.queueManager.observeQueue().first()
        assertEquals(DeliveryStatus.DELIVERED, updatedQueue.first().status)
    }

    // --- Test 6: Idempotent Delivery (Prevent Duplicate Alerts) ---

    @Test
    fun testDuplicateAlertSuppression() = runBlocking {
        val payload = AlertPayload(
            incidentId = "INC-1006",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            location = sampleLocation,
            emergencyMessage = "Emergency SOS button pressed.",
            recipientContacts = listOf(sampleContact)
        )

        // First dispatch
        val receipt1 = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM)).first()
        assertEquals(DeliveryStatus.DELIVERED, receipt1.status)
        assertEquals(1, mockHttp.recordedRequests.size)

        // Second duplicate dispatch with identical incident ID and channel
        val receipt2 = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM)).first()
        assertEquals(DeliveryStatus.DELIVERED, receipt2.status)

        // Network call must NOT be repeated (duplicate suppressed by IdempotencyManager)
        assertEquals(1, mockHttp.recordedRequests.size)

        // Audit log must reflect duplicate suppression
        val duplicateRecord = communicator.auditLog.find { it.isDuplicateSuppressed }
        assertNotNull(duplicateRecord)
        assertEquals("INC-1006", duplicateRecord?.incidentId)
    }

    // --- Test 7: Delivery State Lifecycle & Human Acknowledgment ---

    @Test
    fun testDeliveryLifecycleDistinguishesApiDeliveryFromHumanAcknowledgment() = runBlocking {
        val payload = AlertPayload(
            incidentId = "INC-1007",
            distressType = DistressType.SCREAM,
            confidence = 0.90f,
            location = sampleLocation,
            emergencyMessage = "Acoustic scream.",
            recipientContacts = listOf(sampleContact)
        )

        // 1. Dispatch alert
        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM))
        assertEquals(DeliveryStatus.DELIVERED, receipts.first().status)
        // Note: API delivery is DELIVERED, but NOT yet ACKNOWLEDGED by a human
        assertEquals(EmergencyDeliveryState.DELIVERED, communicator.lastDeliveryState.value)

        // 2. Mock Telegram getUpdates returning human callback acknowledgment
        val mockUpdatesJson = """
            {
                "ok": true,
                "result": [
                    {
                        "update_id": 881234,
                        "callback_query": {
                            "id": "cb-99",
                            "from": {
                                "id": 12345678,
                                "is_bot": false,
                                "first_name": "Jane Doe"
                            },
                            "data": "ack:INC-1007"
                        }
                    }
                ]
            }
        """.trimIndent()
        mockHttp.enqueueResponse(HttpResponse(200, mockUpdatesJson))

        // 3. Poll for human acknowledgment
        val isAck = communicator.pollForTelegramAcknowledgment("INC-1007")
        assertTrue(isAck)
        assertEquals(EmergencyDeliveryState.ACKNOWLEDGED, communicator.lastDeliveryState.value)

        // Verify audit log recorded human acknowledgment
        val ackRecord = communicator.auditLog.find { it.state == EmergencyDeliveryState.ACKNOWLEDGED }
        assertNotNull(ackRecord)
        assertEquals("Jane Doe", ackRecord?.acknowledgedBy)
        assertNotNull(ackRecord?.acknowledgedEpochMs)
    }

    // --- Test 8: SMS Fallback Dispatch ---

    @Test
    fun testSmsFallbackDispatchAndCompactFormat() = runBlocking {
        val payload = AlertPayload(
            incidentId = "INC-1008",
            distressType = DistressType.FALL_IMPACT,
            confidence = 0.85f,
            location = sampleLocation,
            emergencyMessage = "Impact detected.",
            recipientContacts = listOf(sampleContact)
        )

        val receipts = communicator.dispatchAlert(payload, listOf(DeliveryChannel.SMS))

        assertEquals(1, receipts.size)
        assertEquals(DeliveryStatus.DELIVERED, receipts.first().status)
        assertEquals(1, mockSms.sentMessages.size)

        val sentSms = mockSms.sentMessages.first()
        assertEquals("+15551234567", sentSms.destinationAddress)
        assertTrue(sentSms.text.contains("EMERGENCY: GuardianGrid SOS!"))
        assertTrue(sentSms.text.contains("12.9716"))
        assertTrue(sentSms.text.length <= 160) // Enforce single-segment GSM 160-char budget
    }

    // --- Test 9: Audio Evidence Authorization & Privacy ---

    @Test
    fun testAudioEvidencePrivacyRestriction() {
        val unauthorizedContact = EmergencyContact(
            id = "c-2",
            name = "Secondary Contact",
            phoneNumber = "+15559876543",
            isPrimary = false // NOT a primary contact
        )

        val payloadWithAudio = AlertPayload(
            incidentId = "INC-1009",
            distressType = DistressType.SCREAM,
            confidence = 0.90f,
            location = sampleLocation,
            emergencyMessage = "Audio scream.",
            audioSegmentPath = "/sdcard/Android/data/com.guardiangrid/cache/stage2_evidence.aac",
            recipientContacts = listOf(unauthorizedContact)
        )

        val sanitized = RecipientAuthorizer.sanitizePayloadForRecipient(payloadWithAudio, unauthorizedContact)
        // Audio segment path must be stripped for non-primary contacts
        assertNull(sanitized.audioSegmentPath)

        val primarySanitized = RecipientAuthorizer.sanitizePayloadForRecipient(payloadWithAudio, sampleContact)
        // Audio segment path preserved for authorized primary contacts
        assertNotNull(primarySanitized.audioSegmentPath)
    }

    // --- Test 10: Security Credential Masking ---

    @Test
    fun testCredentialMasking() {
        val creds = TelegramCredentials("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345")
        val masked = creds.maskedToken()

        assertTrue(masked.startsWith("123456789:ABC***"))
        assertFalse(masked.contains("defGhIJKlmNoPQRsTUV"))

        val maskedPhone = RecipientAuthorizer.maskPhoneNumber("+15551234567")
        assertEquals("+155****567", maskedPhone)
    }

    // --- Test 11: Bluetooth Store-and-Forward Relay Beacon Encoding ---

    @Test
    fun testBluetoothRelayBeaconEncoding() {
        val payload = AlertPayload(
            incidentId = "INC-1011",
            distressType = DistressType.SCREAM,
            confidence = 0.88f,
            location = sampleLocation,
            emergencyMessage = "Distress signal."
        )

        communicator.bluetoothRelay.startBroadcastingRelay(payload)
        assertTrue(communicator.bluetoothRelay.isRelayActive())

        val beaconBytes = communicator.bluetoothRelay.encodePayloadToCompactBeacon(payload)
        assertEquals(24, beaconBytes.size) // Fits standard BLE manufacturer data packet

        communicator.bluetoothRelay.stopRelay()
        assertFalse(communicator.bluetoothRelay.isRelayActive())
    }

    // --- Test 12: Persistent File Queue Recovery ---

    @Test
    fun testFileQueuePersistenceAcrossRestarts() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "guardiangrid_test_queue_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val fileRepo = FileEmergencyQueueRepository(tempDir)
            val queueMgr = DeliveryQueueManagerImpl(repository = fileRepo)

            val payload = AlertPayload(
                incidentId = "INC-PERSIST-1",
                distressType = DistressType.SCREAM,
                confidence = 0.91f,
                location = sampleLocation,
                emergencyMessage = "Persisted distress."
            )

            val qId = queueMgr.enqueue(payload, DeliveryChannel.TELEGRAM)
            assertEquals(1, fileRepo.getAll().size)

            // Simulate process termination and reboot by instantiating a fresh repository
            val freshRepo = FileEmergencyQueueRepository(tempDir)
            val recoveredEvent = freshRepo.getById(qId)

            assertNotNull(recoveredEvent)
            assertEquals("INC-PERSIST-1", recoveredEvent?.payload?.incidentId)
            assertEquals(DeliveryChannel.TELEGRAM, recoveredEvent?.targetChannel)
            assertEquals(DeliveryStatus.PENDING, recoveredEvent?.status)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
