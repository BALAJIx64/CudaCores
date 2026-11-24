package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.communication.sms.SmsEmergencyDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production validation for cellular SMS emergency dispatch:
 * - GSM-7 160-character budget and payload formatting (ID, time, location).
 * - Primary vs secondary responder dispatch prioritization.
 * - In-memory duplicate SMS suppression.
 * - Hardware failure and permission error resilience.
 */
class ProductionSmsDispatchTest {

    private lateinit var mockSmsSender: MockSmsSender
    private lateinit var dispatcher: SmsEmergencyDispatcher

    @Before
    fun setUp() {
        mockSmsSender = MockSmsSender()
        dispatcher = SmsEmergencyDispatcher(mockSmsSender)
    }

    // --- 1. Compact GSM-7 Message Formatting ---

    @Test
    fun testCompactSmsIncludesRequiredForensicData() {
        val payload = AlertPayload(
            incidentId = "INC-8899",
            distressType = DistressType.SCREAM,
            confidence = 0.96f,
            timestampEpochMs = 1716500000000L,
            location = LocationData(latitude = 37.77492, longitude = -122.41941, accuracyMeters = 5.0f),
            emergencyMessage = "Distress scream"
        )

        val smsText = dispatcher.formatCompactSms(payload)

        // Verifications:
        assertTrue("SMS must include Incident ID", smsText.contains("INC-8899"))
        assertTrue("SMS must include Distress Type", smsText.contains("SCREAM"))
        assertTrue("SMS must include Google Maps coordinates link", smsText.contains("https://maps.google.com/?q=37.77492,-122.41941"))
        assertTrue("SMS must fit strictly within 160 characters", smsText.length <= 160)
    }

    @Test
    fun testCompactSmsUnavailableLocationFallback() {
        val payload = AlertPayload(
            incidentId = "INC-IND-01",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            location = null
        )

        val smsText = dispatcher.formatCompactSms(payload)
        assertTrue("SMS must indicate location unavailable when null", smsText.contains("UNAVAILABLE"))
        assertTrue("SMS must fit strictly within 160 characters", smsText.length <= 160)
    }

    // --- 2. Prioritized Dispatch: Primary Responder First ---

    @Test
    fun testPrimaryContactDispatchedFirst() {
        val secondaryContact = EmergencyContact(id = "c2", name = "Secondary Responder", phoneNumber = "+14155550002", isPrimary = false)
        val primaryContact = EmergencyContact(id = "c1", name = "Primary Responder", phoneNumber = "+14155550001", isPrimary = true)

        // Pass contacts with secondary first in the list
        val payload = AlertPayload(
            incidentId = "INC-PRIORITY-01",
            distressType = DistressType.FALL_IMPACT,
            confidence = 0.92f,
            recipientContacts = listOf(secondaryContact, primaryContact)
        )

        val result = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.DELIVERED, result.state)
        assertEquals(2, result.recipientsCount)

        // Check the actual transmission order in MockSmsSender
        val sentMessages = mockSmsSender.sentMessages
        assertEquals(2, sentMessages.size)
        assertEquals("Primary contact must receive alert first", "+14155550001", sentMessages[0].destinationAddress)
        assertEquals("Secondary contact must receive alert second", "+14155550002", sentMessages[1].destinationAddress)
    }

    // --- 3. Duplicate SMS Suppression ---

    @Test
    fun testDuplicateSmsSuppressionWithinCooldown() {
        val contact = EmergencyContact(id = "c1", name = "Primary", phoneNumber = "+14155550001", isPrimary = true)
        val payload = AlertPayload(
            incidentId = "INC-DEDUP-01",
            distressType = DistressType.SCREAM,
            confidence = 0.90f,
            recipientContacts = listOf(contact)
        )

        // First dispatch: should transmit physically
        val firstResult = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.DELIVERED, firstResult.state)
        assertEquals(1, mockSmsSender.sentMessages.size)
        assertFalse(firstResult.auditRecords.first().isDuplicateSuppressed)

        // Immediate second dispatch with same incident ID: must suppress duplicate
        val secondResult = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.DELIVERED, secondResult.state)
        assertEquals("Sent messages count must not increase (duplicate suppressed)", 1, mockSmsSender.sentMessages.size)
        assertTrue("Audit record must mark duplicate suppressed", secondResult.auditRecords.first().isDuplicateSuppressed)
    }

    // --- 4. Hardware Failure & Radio Error Handling ---

    @Test
    fun testTelephonyFailureReportsFailedState() {
        mockSmsSender.isTelephonyAvailable = false

        val contact = EmergencyContact(id = "c1", name = "Primary", phoneNumber = "+14155550001", isPrimary = true)
        val payload = AlertPayload(
            incidentId = "INC-FAIL-01",
            distressType = DistressType.SCREAM,
            confidence = 0.90f,
            recipientContacts = listOf(contact)
        )

        val result = dispatcher.dispatch(payload)
        assertEquals(EmergencyDeliveryState.FAILED, result.state)
        assertEquals(1, result.failedRecipients.size)
        assertNotNull(result.auditRecords.first().errorMessage)
    }
}
