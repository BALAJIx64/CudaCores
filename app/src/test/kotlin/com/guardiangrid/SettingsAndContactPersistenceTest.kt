package com.guardiangrid

import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.communication.persistence.FileSettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test suite validating Task 4: Persistent Application Settings & Contacts.
 *
 * Covers:
 * - Contact input validation (non-blank name, valid phone format).
 * - Duplicate contact rejection.
 * - Contact addition, update, and deletion.
 * - Settings persistence across repository re-instantiation (simulating app restarts).
 * - Discreet mode and scream sensitivity settings synchronization.
 */
class SettingsAndContactPersistenceTest {

    private lateinit var tempDir: File
    private lateinit var repo: FileSettingsRepository

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_settings_test_${System.currentTimeMillis()}").apply { mkdirs() }
        repo = FileSettingsRepository(storageDir = tempDir)
    }

    @After
    fun tearDown() {
        EmergencyStateHolder.reset()
        tempDir.deleteRecursively()
    }

    @Test
    fun testAddValidContact() {
        val contact = EmergencyContact(
            id = "c1",
            name = "Jane Doe",
            phoneNumber = "+1234567890",
            telegramChatId = "jane_chat",
            isPrimary = true
        )

        val result = repo.addContact(contact)
        assertTrue(result.isSuccess)
        assertEquals(1, repo.getSettings().contacts.size)
        assertEquals("Jane Doe", repo.getSettings().contacts[0].name)
    }

    @Test
    fun testRejectBlankNameOrInvalidPhone() {
        val blankName = EmergencyContact(id = "c1", name = "   ", phoneNumber = "+1234567890")
        val blankResult = repo.addContact(blankName)
        assertTrue(blankResult.isFailure)

        val shortPhone = EmergencyContact(id = "c2", name = "John", phoneNumber = "123")
        val phoneResult = repo.addContact(shortPhone)
        assertTrue(phoneResult.isFailure)
    }

    @Test
    fun testRejectDuplicateContact() {
        val contact1 = EmergencyContact(id = "c1", name = "Alice", phoneNumber = "+1-555-123-4567")
        val contact2 = EmergencyContact(id = "c2", name = "Alice Clone", phoneNumber = "+15551234567") // same normalized number

        assertTrue(repo.addContact(contact1).isSuccess)
        val duplicateResult = repo.addContact(contact2)
        assertTrue("Duplicate phone number must be rejected", duplicateResult.isFailure)
        assertEquals(1, repo.getSettings().contacts.size)
    }

    @Test
    fun testUpdateContact() {
        val contact = EmergencyContact(id = "c1", name = "Bob", phoneNumber = "+1987654321")
        repo.addContact(contact)

        val updated = contact.copy(name = "Robert", isPrimary = true)
        val updateResult = repo.updateContact(updated)
        assertTrue(updateResult.isSuccess)

        val stored = repo.getSettings().contacts.first { it.id == "c1" }
        assertEquals("Robert", stored.name)
        assertTrue(stored.isPrimary)
    }

    @Test
    fun testDeleteContact() {
        val contact = EmergencyContact(id = "c1", name = "Charlie", phoneNumber = "+1122334455")
        repo.addContact(contact)
        assertEquals(1, repo.getSettings().contacts.size)

        val deleted = repo.deleteContact("c1")
        assertTrue(deleted)
        assertEquals(0, repo.getSettings().contacts.size)
    }

    @Test
    fun testSettingsSurviveAppRestart() {
        val contact = EmergencyContact(id = "c1", name = "Dana", phoneNumber = "+1002003004")
        repo.addContact(contact)
        repo.setDiscreetMode(true)
        repo.setScreamSensitivity(0.95f)

        // Simulate app restart by re-instantiating repository from same directory
        val reloadedRepo = FileSettingsRepository(storageDir = tempDir)
        val settings = reloadedRepo.getSettings()

        assertEquals(1, settings.contacts.size)
        assertEquals("Dana", settings.contacts[0].name)
        assertTrue(settings.isDiscreetModeEnabled)
        assertEquals(0.95f, settings.screamSensitivity, 0.001f)
    }

    @Test
    fun testStateHolderSynchronization() {
        val contact = EmergencyContact(id = "c1", name = "Eve", phoneNumber = "+1444555666")
        repo.addContact(contact)
        repo.setDiscreetMode(true)
        repo.syncToStateHolder()

        val state = EmergencyStateHolder.state.value
        assertEquals(1, state.trustedContacts.size)
        assertEquals("Eve", state.trustedContacts[0].name)
        assertTrue(state.isDiscreetModeEnabled)
    }
}
