package com.guardiangrid

import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.modules.communication.persistence.FileSettingsRepository
import com.guardiangrid.modules.communication.persistence.PhoneNumberValidator
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

/**
 * Production validation for emergency contact management,
 * E.164 international phone number normalization, and primary/secondary designation.
 */
class EmergencyContactsAndValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repoDir: File
    private lateinit var repo: FileSettingsRepository

    @Before
    fun setUp() {
        repoDir = tempFolder.newFolder("contact_settings_test")
        repo = FileSettingsRepository(repoDir)
    }

    // --- 1. E.164 International Phone Number Validation ---

    @Test
    fun testValidInternationalPhoneNumbers() {
        val validCases = listOf(
            "+14155552671" to "+14155552671",
            "+91 98765 43210" to "+919876543210",
            "+44 (0) 7911 123456" to "+4407911123456",
            "+61-491-570-156" to "+61491570156",
            "+81.90.1234.5678" to "+819012345678",
            "9876543210" to "9876543210"
        )

        for ((input, expectedNormalized) in validCases) {
            val result = PhoneNumberValidator.validateAndNormalize(input)
            assertTrue("Expected valid for input: $input (${result.errorMessage})", result.isValid)
            assertEquals("Normalized number mismatch for $input", expectedNormalized, result.normalizedNumber)
        }
    }

    @Test
    fun testInvalidPhoneNumbersRejected() {
        val invalidCases = listOf(
            "",                   // Blank
            "   ",                // Whitespace
            "12345",              // Too short (< 7 digits)
            "+12345",             // Too short (< 7 digits)
            "123456789012345678", // Too long (> 15 digits)
            "+1-800-CALL-NOW",    // Contains non-digit characters
            "INVALID_PHONE"       // Alpha string
        )

        for (input in invalidCases) {
            val result = PhoneNumberValidator.validateAndNormalize(input)
            assertFalse("Expected invalid for: $input", result.isValid)
            assertNotNull("Error message should be present for: $input", result.errorMessage)
        }
    }

    // --- 2. Primary vs Secondary Contact Designation ---

    @Test
    fun testFirstContactAutomaticallyDesignatedPrimary() {
        val contact1 = EmergencyContact(
            id = "c1",
            name = "Alice Primary",
            phoneNumber = "+14155550001",
            isPrimary = false,
            relationship = "Partner"
        )

        val addResult = repo.addContact(contact1)
        assertTrue(addResult.isSuccess)

        val saved = addResult.getOrThrow()
        assertTrue("First added contact must be automatically designated as primary", saved.isPrimary)
        assertEquals("Alice Primary", repo.getPrimaryContact()?.name)
        assertEquals(0, repo.getSecondaryContacts().size)
    }

    @Test
    fun testSecondaryContactAdditionAndPromotion() {
        val c1 = EmergencyContact(id = "c1", name = "Alice", phoneNumber = "+14155550001", isPrimary = true, relationship = "Partner")
        val c2 = EmergencyContact(id = "c2", name = "Bob", phoneNumber = "+14155550002", isPrimary = false, relationship = "Brother")
        val c3 = EmergencyContact(id = "c3", name = "Charlie", phoneNumber = "+14155550003", isPrimary = false, relationship = "Colleague")

        repo.addContact(c1)
        repo.addContact(c2)
        repo.addContact(c3)

        assertEquals("c1", repo.getPrimaryContact()?.id)
        assertEquals(2, repo.getSecondaryContacts().size)

        // Promote Bob (c2) to primary
        val promoted = repo.setPrimaryContact("c2")
        assertTrue(promoted)

        assertEquals("c2", repo.getPrimaryContact()?.id)
        assertTrue(repo.getPrimaryContact()!!.isPrimary)

        // Verify Alice (c1) was automatically demoted to secondary
        val alice = repo.getSettings().contacts.first { it.id == "c1" }
        assertFalse("Previous primary contact must be demoted", alice.isPrimary)

        val secondaries = repo.getSecondaryContacts()
        assertEquals(2, secondaries.size)
        assertTrue(secondaries.any { it.id == "c1" })
        assertTrue(secondaries.any { it.id == "c3" })
    }

    @Test
    fun testDeletingPrimaryPromotesNextContact() {
        val c1 = EmergencyContact(id = "c1", name = "Alice", phoneNumber = "+14155550001", isPrimary = true)
        val c2 = EmergencyContact(id = "c2", name = "Bob", phoneNumber = "+14155550002", isPrimary = false)

        repo.addContact(c1)
        repo.addContact(c2)

        assertEquals("c1", repo.getPrimaryContact()?.id)

        // Delete primary contact
        val deleted = repo.deleteContact("c1")
        assertTrue(deleted)

        // Bob should now be automatically promoted to primary
        assertEquals("c2", repo.getPrimaryContact()?.id)
        assertTrue(repo.getPrimaryContact()!!.isPrimary)
        assertEquals(0, repo.getSecondaryContacts().size)
    }

    @Test
    fun testDuplicatePhoneNumberPrevention() {
        val c1 = EmergencyContact(id = "c1", name = "Alice", phoneNumber = "+14155550001")
        val c2 = EmergencyContact(id = "c2", name = "Alice Duplicate", phoneNumber = "+1 (415) 555-0001")

        assertTrue(repo.addContact(c1).isSuccess)
        val duplicateResult = repo.addContact(c2)
        assertFalse("Adding a contact with identical normalized phone number must fail", duplicateResult.isSuccess)
    }

    @Test
    fun testContactPersistenceAcrossRepositoryReload() {
        val c1 = EmergencyContact(
            id = "c1",
            name = "Persistent Contact",
            phoneNumber = "+14155559999",
            telegramChatId = "987654321",
            isPrimary = true,
            relationship = "Parent"
        )
        repo.addContact(c1)

        // Recreate repository from same disk directory
        val reloadedRepo = FileSettingsRepository(repoDir)
        val loaded = reloadedRepo.getPrimaryContact()

        assertNotNull(loaded)
        assertEquals("Persistent Contact", loaded?.name)
        assertEquals("+14155559999", loaded?.phoneNumber)
        assertEquals("987654321", loaded?.telegramChatId)
        assertEquals("Parent", loaded?.relationship)
        assertTrue(loaded!!.isPrimary)
    }
}
