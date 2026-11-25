package com.guardiangrid

import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.core.models.BleEmergencyToken
import com.guardiangrid.core.models.BleForwardStatus
import com.guardiangrid.core.models.BleRelayAcknowledgement
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import com.guardiangrid.modules.audio.TwoStageAudioEvidenceRecorder
import com.guardiangrid.modules.communication.ble.BleRelayManager
import com.guardiangrid.modules.communication.ble.BleSenderManager
import com.guardiangrid.modules.communication.ble.BleTokenStore
import com.guardiangrid.modules.communication.ble.FileBleRelayQueueRepository
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.network.RecordedHttpRequest
import com.guardiangrid.modules.communication.supabase.SupabaseBackendConfig
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Comprehensive automated test suite for GuardianGrid's BLE Emergency Relay Architecture
 * and Supabase Backend Integration, covering all 15 required scenarios.
 */
class BleEmergencyRelayIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var senderTokenStore: BleTokenStore
    private lateinit var senderManager: BleSenderManager
    private lateinit var relayQueueRepo: FileBleRelayQueueRepository
    private lateinit var mockTransport: MockHttpTransport
    private lateinit var backendClient: SupabaseEmergencyBackendClient

    private val testAccountId = UUID.randomUUID().toString()
    private val testSenderDeviceId = "DEV-SENDER-001"
    private val testRelayDeviceId = "DEV-RELAY-002"
    private val testTokenSecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val testTokenId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        val senderStorageDir = File(tempFolder.root, "sender_storage")
        val relayStorageDir = File(tempFolder.root, "relay_storage")

        senderTokenStore = BleTokenStore(senderStorageDir)
        senderManager = BleSenderManager(senderTokenStore)

        // Cache a valid short-lived token for the sender
        val validToken = BleEmergencyToken(
            tokenId = testTokenId,
            tokenSecret = testTokenSecret,
            issuedAt = System.currentTimeMillis() - 1000L,
            expiresAt = System.currentTimeMillis() + 72 * 3600 * 1000L,
            accountId = testAccountId,
            deviceId = testSenderDeviceId
        )
        senderTokenStore.saveToken(validToken)

        relayQueueRepo = FileBleRelayQueueRepository(relayStorageDir)
        mockTransport = MockHttpTransport()

        val connectivityManager = GuardianConnectivityManager(
            storageDir = File(tempFolder.root, "connectivity")
        )

        val config = SupabaseBackendConfig(
            projectUrl = "https://yhzvpuycztuwdqttpoid.supabase.co",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy_anon_key_for_testing_purposes"
        )

        backendClient = SupabaseEmergencyBackendClient(
            connectivityManager = connectivityManager,
            transport = mockTransport,
            configProvider = { config }
        )
    }

    // --- Scenario 1: Valid BLE Alert Packet ---
    @Test
    fun test1_ValidBleAlertPacketCreationAndVerification() {
        val result = senderManager.createEmergencyAlertPacket(
            accountId = testAccountId,
            senderDeviceId = testSenderDeviceId,
            alertType = "MANUAL_SOS",
            latitude = 35.6895,
            longitude = 139.6917,
            accuracyMeters = 5.0f,
            emergencyMessage = "Help! Emergency alert."
        )

        assertTrue("Packet creation must succeed", result.isSuccess)
        val packet = result.getOrNull()!!

        assertEquals(1, packet.protocolVersion)
        assertEquals(testAccountId, packet.accountId)
        assertEquals(testSenderDeviceId, packet.senderDeviceId)
        assertEquals(testTokenId, packet.tokenId)
        assertEquals("MANUAL_SOS", packet.alertType)
        assertTrue(packet.signature.isNotBlank())

        // Verify cryptographic signature matches canonical input
        assertTrue("Signature verification must succeed with correct secret", packet.verifySignature(testTokenSecret))
        assertTrue("Structure validation must succeed", packet.validateBasicStructure().isSuccess)
    }

    // --- Scenario 2: Invalid Signature ---
    @Test
    fun test2_InvalidSignatureDetection() {
        val result = senderManager.createEmergencyAlertPacket(
            accountId = testAccountId,
            senderDeviceId = testSenderDeviceId,
            alertType = "MANUAL_SOS",
            latitude = 35.6895,
            longitude = 139.6917
        )
        val originalPacket = result.getOrNull()!!

        // Tamper with location
        val tamperedLocation = originalPacket.copy(latitude = 40.7128)
        assertFalse("Tampered location must fail signature verification", tamperedLocation.verifySignature(testTokenSecret))

        // Tamper with alert type
        val tamperedType = originalPacket.copy(alertType = "FALSE_ALARM")
        assertFalse("Tampered alert type must fail signature verification", tamperedType.verifySignature(testTokenSecret))

        // Verify with wrong secret
        assertFalse("Verification with wrong secret must fail", originalPacket.verifySignature("0000000000000000000000000000000000000000000000000000000000000000"))
    }

    // --- Scenario 3: Wrong Account ID ---
    @Test
    fun test3_WrongAccountIdRejection() {
        // Non-UUID account ID (e.g. email address or display name) MUST be rejected
        val emailResult = senderManager.createEmergencyAlertPacket(
            accountId = "user@example.com",
            senderDeviceId = testSenderDeviceId
        )
        assertTrue("Email address must not be accepted as account identity", emailResult.isFailure)

        // Account ID mismatch with cached token MUST be rejected
        val differentUuid = UUID.randomUUID().toString()
        val mismatchResult = senderManager.createEmergencyAlertPacket(
            accountId = differentUuid,
            senderDeviceId = testSenderDeviceId
        )
        assertTrue("Account ID mismatch with token must be rejected", mismatchResult.isFailure)
    }

    // --- Scenario 4: Wrong Sender Device ID ---
    @Test
    fun test4_WrongSenderDeviceIdRejection() {
        val mismatchResult = senderManager.createEmergencyAlertPacket(
            accountId = testAccountId,
            senderDeviceId = "DEV-ATTACKER-999"
        )
        assertTrue("Sender device ID mismatch with token must be rejected", mismatchResult.isFailure)
    }

    // --- Scenario 5: Expired Token ---
    @Test
    fun test5_ExpiredTokenRejection() {
        // Store an already expired token
        val expiredToken = BleEmergencyToken(
            tokenId = "tok-expired",
            tokenSecret = testTokenSecret,
            issuedAt = System.currentTimeMillis() - 100_000L,
            expiresAt = System.currentTimeMillis() - 1000L,
            accountId = testAccountId,
            deviceId = testSenderDeviceId
        )
        senderTokenStore.saveToken(expiredToken)

        assertNull("Expired token must not be returned as active", senderTokenStore.getActiveToken())
        assertFalse("Sender must report no valid token", senderManager.hasValidCachedToken())

        val result = senderManager.createEmergencyAlertPacket(
            accountId = testAccountId,
            senderDeviceId = testSenderDeviceId
        )
        assertTrue("Creating packet with expired token must fail", result.isFailure)
    }

    // --- Scenario 6: Replayed Nonce ---
    @Test
    fun test6_ReplayedNonceRejection() {
        val consumedNonces = Collections.synchronizedSet(mutableSetOf<String>())
        mockTransport.setHandler { req ->
            if (req.url.contains("/ble-relay-alert")) {
                val packet = BleEmergencyAlertPacket.fromJson(req.body ?: "{}")
                if (consumedNonces.contains(packet.nonce)) {
                    HttpResponse(statusCode = 409, body = """{"ok":false,"error":"Replay attack detected: nonce already consumed"}""")
                } else {
                    consumedNonces.add(packet.nonce)
                    HttpResponse(statusCode = 200, body = """{"ok":true,"alertId":"${packet.alertId}","status":"RECEIVED","smsDispatched":true,"acknowledged":true}""")
                }
            } else {
                HttpResponse(statusCode = 404, body = "{}")
            }
        }

        val packetResult = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId)
        val packet = packetResult.getOrNull()!!

        val firstSend = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet)
        assertTrue("First alert transmission must succeed", firstSend.isSuccess)

        // Resend packet with exact same nonce
        val replaySend = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet)
        assertTrue("Replayed nonce must be rejected by backend with error", replaySend.isFailure)
        assertTrue(replaySend.exceptionOrNull()?.message?.contains("409") == true)
    }

    // --- Scenario 7: Duplicate Alert ID (Idempotency) ---
    @Test
    fun test7_DuplicateAlertIdIdempotency() {
        val processedAlerts = mutableMapOf<String, String>()
        mockTransport.setHandler { req ->
            if (req.url.contains("/ble-relay-alert")) {
                val packet = BleEmergencyAlertPacket.fromJson(req.body ?: "{}")
                val isDup = processedAlerts.containsKey(packet.alertId)
                processedAlerts[packet.alertId] = "SMS_SENT"
                HttpResponse(
                    statusCode = 200,
                    body = """{"ok":true,"alertId":"${packet.alertId}","status":"SMS_SENT","smsDispatched":${!isDup},"acknowledged":true,"duplicate":$isDup}"""
                )
            } else HttpResponse(404, "{}")
        }

        val packet = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId).getOrNull()!!

        val firstAck = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet).getOrNull()!!
        assertFalse(firstAck.duplicate)
        assertEquals("SMS_SENT", firstAck.status)

        val secondAck = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet).getOrNull()!!
        assertTrue("Duplicate submission must be recognized as duplicate", secondAck.duplicate)
        assertTrue("Duplicate submission must still receive acknowledgement", secondAck.acknowledged)
    }

    // --- Scenario 8: Concurrent Duplicate Requests ---
    @Test
    fun test8_ConcurrentDuplicateRequestsDeduplication() {
        val relayManager = BleRelayManager(
            relayDeviceId = testRelayDeviceId,
            queueRepository = relayQueueRepo,
            backendForwarder = { _, _ -> Result.success(BleRelayAcknowledgement(true, "A1", "SMS_SENT", true)) }
        )

        val packet = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId).getOrNull()!!

        // Simulate concurrent reception of the same packet from multiple BLE scan callbacks
        val item1 = relayManager.receiveAlertPacket(packet).getOrNull()!!
        val item2 = relayManager.receiveAlertPacket(packet).getOrNull()!!

        assertEquals("Same queue ID must be reused for identical alert", item1.queueId, item2.queueId)
        assertEquals("Repository must contain exactly 1 queue item", 1, relayQueueRepo.getAll().size)
    }

    // --- Scenario 9: Relay Offline and Retry ---
    @Test
    fun test9_RelayOfflineAndRetry() {
        val relayManager = BleRelayManager(
            relayDeviceId = testRelayDeviceId,
            queueRepository = relayQueueRepo,
            backendForwarder = { relayId, pkt -> backendClient.forwardBleRelayAlert(relayId, pkt) }
        )

        val packet = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId).getOrNull()!!
        val item = relayManager.receiveAlertPacket(packet).getOrNull()!!

        // 1. Relay is OFFLINE
        mockTransport.isNetworkAvailable = false
        val forwardResult = relayManager.forwardQueuedItem(item)
        assertTrue("Forwarding while offline must fail", forwardResult.isFailure)

        // Queue must preserve item as FAILED_RETRYING
        val pending = relayQueueRepo.getPending()
        assertEquals(1, pending.size)
        assertEquals(BleForwardStatus.FAILED_RETRYING, pending[0].forwardStatus)
        assertEquals(1, pending[0].retryCount)

        // 2. Relay comes ONLINE
        mockTransport.isNetworkAvailable = true
        mockTransport.enqueueResponse(
            HttpResponse(200, """{"ok":true,"alertId":"${packet.alertId}","status":"SMS_SENT","smsDispatched":true,"acknowledged":true}""")
        )

        val retryResults = relayManager.retryPendingQueue()
        assertEquals(1, retryResults.size)
        assertTrue("Retry must succeed when network is restored", retryResults[0].isSuccess)

        // Queue item marked ACKNOWLEDGED
        assertEquals(0, relayQueueRepo.getPending().size)
        assertEquals(BleForwardStatus.ACKNOWLEDGED, relayQueueRepo.getAll()[0].forwardStatus)
    }

    // --- Scenario 10: Relay Forwarding Packet Unchanged ---
    @Test
    fun test10_RelayForwardingPacketUnchanged() {
        var deliveredBody: String? = null
        mockTransport.setHandler { req ->
            deliveredBody = req.body
            HttpResponse(200, """{"ok":true,"alertId":"A1","status":"SMS_SENT","smsDispatched":true,"acknowledged":true}""")
        }

        val relayManager = BleRelayManager(
            relayDeviceId = testRelayDeviceId,
            queueRepository = relayQueueRepo,
            backendForwarder = { relayId, pkt -> backendClient.forwardBleRelayAlert(relayId, pkt) }
        )

        val packet = senderManager.createEmergencyAlertPacket(
            accountId = testAccountId,
            senderDeviceId = testSenderDeviceId,
            alertType = "SCREAM",
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 8.5f,
            emergencyMessage = "Forensic scream distress!"
        ).getOrNull()!!

        val item = relayManager.receiveAlertPacket(packet).getOrNull()!!
        relayManager.forwardQueuedItem(item)

        assertNotNull(deliveredBody)
        val forwardedPacket = BleEmergencyAlertPacket.fromJson(deliveredBody!!)

        // Relay must NEVER alter any field
        assertEquals(packet.alertId, forwardedPacket.alertId)
        assertEquals(packet.accountId, forwardedPacket.accountId)
        assertEquals(packet.senderDeviceId, forwardedPacket.senderDeviceId)
        assertEquals(packet.tokenId, forwardedPacket.tokenId)
        assertEquals(packet.issuedAt, forwardedPacket.issuedAt)
        assertEquals(packet.nonce, forwardedPacket.nonce)
        assertEquals(packet.signature, forwardedPacket.signature)
        assertEquals(packet.alertType, forwardedPacket.alertType)
        assertEquals(packet.latitude, forwardedPacket.latitude)
        assertEquals(packet.longitude, forwardedPacket.longitude)
        assertEquals(packet.emergencyMessage, forwardedPacket.emergencyMessage)
    }

    // --- Scenario 11: Contact Lookup Performed Only by Backend ---
    @Test
    fun test11_ContactLookupPerformedOnlyByBackend() {
        // Assert RelayManager has no methods, properties or dependencies referencing contacts
        val relayClass = BleRelayManager::class.java
        val methodNames = relayClass.methods.map { it.name }
        assertFalse(methodNames.any { it.contains("Contact", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("Phone", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("Sms", ignoreCase = true) })

        // Assert BleEmergencyAlertPacket does not contain contact numbers
        val packetClass = BleEmergencyAlertPacket::class.java
        val fieldNames = packetClass.declaredFields.map { it.name }
        assertFalse(fieldNames.any { it.contains("Contact", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("Phone", ignoreCase = true) })
    }

    // --- Scenario 12: SMS Dispatched Once ---
    @Test
    fun test12_SmsDispatchedOnce() {
        var smsSendCount = 0
        mockTransport.setHandler { req ->
            if (req.url.contains("/ble-relay-alert")) {
                if (smsSendCount == 0) {
                    smsSendCount++
                    HttpResponse(200, """{"ok":true,"alertId":"A1","status":"SMS_SENT","smsDispatched":true,"acknowledged":true}""")
                } else {
                    HttpResponse(200, """{"ok":true,"alertId":"A1","status":"SMS_SENT","smsDispatched":false,"acknowledged":true,"duplicate":true}""")
                }
            } else HttpResponse(404, "{}")
        }

        val packet = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId).getOrNull()!!

        val r1 = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet).getOrNull()!!
        assertTrue(r1.smsDispatched)

        val r2 = backendClient.forwardBleRelayAlert("DEV-RELAY-003", packet).getOrNull()!!
        assertFalse("Second transmission of same alert must not trigger another SMS", r2.smsDispatched)
        assertEquals(1, smsSendCount)
    }

    // --- Scenario 13: Failed SMS Delivery and Recording ---
    @Test
    fun test13_FailedSmsDeliveryAndRecording() {
        mockTransport.setHandler { req ->
            HttpResponse(
                200,
                """{"ok":true,"alertId":"A1","status":"SMS_FAILED","smsDispatched":false,"smsError":"Twilio authentication failure: 20003","acknowledged":true}"""
            )
        }

        val packet = senderManager.createEmergencyAlertPacket(testAccountId, testSenderDeviceId).getOrNull()!!
        val ack = backendClient.forwardBleRelayAlert(testRelayDeviceId, packet).getOrNull()!!

        assertFalse("smsDispatched must be false on failure", ack.smsDispatched)
        assertEquals("SMS_FAILED", ack.status)
        assertEquals("Twilio authentication failure: 20003", ack.smsError)
    }

    // --- Scenario 14: No Backend Secret Exposed in Android ---
    @Test
    fun test14_NoBackendSecretExposedInAndroid() {
        val forbiddenStrings = listOf(
            "SUPABASE_SERVICE_ROLE_KEY",
            "GUARDIAN_HMAC_PEPPER",
            "TWILIO_AUTH_TOKEN",
            "TELEGRAM_BOT_TOKEN"
        )

        val checkedClasses = listOf(
            BleSenderManager::class.java,
            BleRelayManager::class.java,
            FileBleRelayQueueRepository::class.java,
            BleTokenStore::class.java,
            BleEmergencyAlertPacket::class.java,
            BleEmergencyToken::class.java,
            SupabaseEmergencyBackendClient::class.java,
            SupabaseBackendConfig::class.java
        )

        for (cls in checkedClasses) {
            for (field in cls.declaredFields) {
                for (forbidden in forbiddenStrings) {
                    assertFalse("Class ${cls.simpleName} must not contain forbidden secret field $forbidden", field.name.equals(forbidden, ignoreCase = true))
                }
            }
        }
    }

    // --- Scenario 15: Existing Audio Pipeline Remains Unaffected ---
    @Test
    fun test15_ExistingAudioPipelineRemainsUnaffected() {
        val circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
        val classifier = ScreamDistressClassifier(circularBuffer = circularBuffer)

        // Verify classifier rejects silence
        val silence = ShortArray(16000) { 0 }
        val score = classifier.evaluateAudioSamples(silence)
        assertTrue("Audio classifier must reject silence ($score < 0.15)", score < 0.15f)

        // Verify 2-stage audio evidence recorder initializes without error
        val recorder = TwoStageAudioEvidenceRecorder(circularBuffer, null, tempFolder.root)
        val stage1 = kotlinx.coroutines.runBlocking { recorder.captureStageOneBuffer(5000L) }
        assertTrue(stage1.isSuccess)
    }
}
