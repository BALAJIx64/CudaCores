package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.security.TelegramCredentials
import com.guardiangrid.modules.communication.telegram.TelegramEmergencyDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Production validation for Telegram Bot API communication:
 * - Credential validation & token masking.
 * - Active bot verification via getMe.
 * - Recipient chat ID validation.
 * - Alert payload dispatch, HTTP error handling, and privacy audits.
 */
class ProductionTelegramCommunicationTest {

    private lateinit var mockTransport: MockHttpTransport
    private lateinit var validCredentials: TelegramCredentials

    @Before
    fun setUp() {
        mockTransport = MockHttpTransport()
        validCredentials = TelegramCredentials(
            botToken = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345",
            defaultChatId = "-1001987654321"
        )
    }

    // --- 1. Credential Validation & Token Masking ---

    @Test
    fun testTelegramCredentialValidation() {
        // Valid token: digit ID + colon + >= 20 char secret
        assertTrue(validCredentials.isValid())

        // Invalid tokens
        assertFalse(TelegramCredentials("").isValid())
        assertFalse(TelegramCredentials("invalid_token_no_colon").isValid())
        assertFalse(TelegramCredentials("abc:short_secret").isValid())
        assertFalse(TelegramCredentials("12345:short").isValid()) // secret too short (< 20)
    }

    @Test
    fun testTokenMaskingNeverExposesSecret() {
        val creds = TelegramCredentials("987654321:AbCdEfGhIjKlMnOpQrStUvWxYz098765")
        val masked = creds.maskedToken()

        assertTrue("Masked token must start with bot ID", masked.startsWith("987654321:"))
        assertTrue("Masked token must contain asterisks", masked.contains("***"))
        assertFalse("Masked token must not contain full secret", masked.contains("AbCdEfGhIjKlMnOpQrStUvWxYz098765"))
        assertFalse("toString() must not expose raw secret", creds.toString().contains("AbCdEfGhIjKlMnOpQrStUvWxYz098765"))
    }

    // --- 2. Bot API Verification (getMe) ---

    @Test
    fun testVerifyBotTokenSuccess() {
        val dispatcher = TelegramEmergencyDispatcher(
            transport = mockTransport,
            credentialsProvider = { validCredentials }
        )

        mockTransport.enqueueResponse(
            HttpResponse(
                statusCode = 200,
                body = """{"ok":true,"result":{"id":123456789,"is_bot":true,"first_name":"GuardianGrid Bot","username":"guardiangrid_emergency_bot"}}"""
            )
        )

        val result = dispatcher.verifyBotToken(validCredentials.botToken)
        assertTrue(result.isSuccess)
        assertEquals("guardiangrid_emergency_bot", result.botUsername)
        assertEquals(123456789L, result.botId)
    }

    @Test
    fun testVerifyBotTokenUnauthorized() {
        val dispatcher = TelegramEmergencyDispatcher(
            transport = mockTransport,
            credentialsProvider = { validCredentials }
        )

        mockTransport.enqueueResponse(
            HttpResponse(
                statusCode = 401,
                body = """{"ok":false,"error_code":401,"description":"Unauthorized"}"""
            )
        )

        val result = dispatcher.verifyBotToken(validCredentials.botToken)
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessage)
        assertTrue("Error should mention unauthorized", result.errorMessage!!.contains("Unauthorized"))
    }

    // --- 3. Recipient Chat ID Verification ---

    @Test
    fun testVerifyRecipientChatIdFormats() {
        val dispatcher = TelegramEmergencyDispatcher(
            transport = mockTransport,
            credentialsProvider = { validCredentials }
        )

        // Valid formats: numeric, negative numeric (supergroup), channel handle
        assertTrue(dispatcher.verifyRecipientChatId(validCredentials.botToken, "123456789").isSuccess)
        assertTrue(dispatcher.verifyRecipientChatId(validCredentials.botToken, "-100123456789").isSuccess)
        assertTrue(dispatcher.verifyRecipientChatId(validCredentials.botToken, "@my_emergency_channel").isSuccess)

        // Invalid formats
        assertFalse(dispatcher.verifyRecipientChatId(validCredentials.botToken, "").isSuccess)
        assertFalse(dispatcher.verifyRecipientChatId(validCredentials.botToken, "invalid chat").isSuccess)
    }

    // --- 4. Alert Payload Dispatch & Error Handling ---

    @Test
    fun testRealDispatchSuccessAndAuditPrivacy() {
        val dispatcher = TelegramEmergencyDispatcher(
            transport = mockTransport,
            credentialsProvider = { validCredentials }
        )

        mockTransport.enqueueResponse(
            HttpResponse(
                statusCode = 200,
                body = """{"ok":true,"result":{"message_id":4521,"date":1620000000,"text":"alert"}}"""
            )
        )

        val payload = AlertPayload(
            incidentId = "INC-TEST-001",
            distressType = DistressType.SCREAM,
            confidence = 0.95f,
            location = LocationData(latitude = 37.7749, longitude = -122.4194, accuracyMeters = 8.0f),
            emergencyMessage = "Severe distress scream detected",
            recipientContacts = listOf(
                EmergencyContact(id = "c1", name = "Responder", phoneNumber = "+14155552671", isPrimary = true)
            )
        )

        val result = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.DELIVERED, result.state)
        assertEquals("4521", result.messageId)

        // Privacy Check: Audit record must not expose raw chat ID
        val audit = result.auditRecord
        assertEquals("INC-TEST-001", audit.incidentId)
        assertNotNull(audit.recipient)
        assertTrue("Recipient chat ID must be masked in audit records", audit.recipient!!.contains("***"))
        assertFalse("Recipient chat ID must not be raw in audit records", audit.recipient == validCredentials.defaultChatId)
    }

    @Test
    fun testDispatchRateLimiting429Handled() {
        val dispatcher = TelegramEmergencyDispatcher(
            transport = mockTransport,
            credentialsProvider = { validCredentials }
        )

        mockTransport.enqueueResponse(
            HttpResponse(
                statusCode = 429,
                body = """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 10"}"""
            )
        )

        val payload = AlertPayload(
            incidentId = "INC-TEST-429",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            emergencyMessage = "SOS Triggered"
        )

        val result = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.FAILED, result.state)
        assertTrue("Error should mention rate limit or 429", result.errorMessage!!.contains("429"))
    }
}
