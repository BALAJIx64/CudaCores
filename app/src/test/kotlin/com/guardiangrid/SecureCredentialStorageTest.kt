package com.guardiangrid

import com.guardiangrid.modules.communication.security.SecureTelegramCredentialStore
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import com.guardiangrid.modules.communication.security.TelegramCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test suite validating Task 3: Keystore-Backed Secure Telegram Credentials.
 *
 * Covers:
 * - AES-GCM encryption and persistent storage.
 * - Credential restoration and decryption.
 * - Secure credential deletion.
 * - Corrupted vault safe error recovery.
 * - Token format validation and token masking.
 */
class SecureCredentialStorageTest {

    private lateinit var tempDir: File
    private lateinit var secureStore: SecureTelegramCredentialStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_vault_test_${System.currentTimeMillis()}").apply { mkdirs() }
        secureStore = SecureTelegramCredentialStore(storageDir = tempDir)
        TelegramConfigManager.clear()
    }

    @After
    fun tearDown() {
        TelegramConfigManager.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndLoadCredentials() {
        val validToken = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345"
        val chatId = "-100987654321"
        val creds = TelegramCredentials(botToken = validToken, defaultChatId = chatId)

        assertTrue("Credentials format must be valid", creds.isValid())

        val saveResult = secureStore.saveCredentials(creds)
        assertTrue("Saving credentials must succeed", saveResult)
        assertTrue("Vault file must exist", secureStore.hasCredentials())

        // Re-load from disk
        val loaded = secureStore.loadCredentials()
        assertNotNull("Loaded credentials must not be null", loaded)
        assertEquals(validToken, loaded?.botToken)
        assertEquals(chatId, loaded?.defaultChatId)
    }

    @Test
    fun testCredentialsMaskingProtectsSecrets() {
        val validToken = "987654321:SecretTokenForTestingGuardianGridProduction"
        val creds = TelegramCredentials(botToken = validToken, defaultChatId = "12345")
        val masked = creds.maskedToken()

        assertFalse("Masked token must not contain full secret", masked.contains("SecretTokenForTesting"))
        assertTrue("Masked token must contain bot id prefix", masked.startsWith("987654321:"))
        assertTrue("Masked token must contain asterisk masking", masked.contains("***"))
        assertFalse("toString must never print unmasked token", creds.toString().contains("SecretTokenForTesting"))
    }

    @Test
    fun testDeleteCredentialsRemovesFile() {
        val creds = TelegramCredentials("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345", "123")
        secureStore.saveCredentials(creds)
        assertTrue(secureStore.hasCredentials())

        val deleted = secureStore.deleteCredentials()
        assertTrue("Deletion must succeed", deleted)
        assertFalse("Vault must be deleted", secureStore.hasCredentials())
        assertNull("Loaded credentials after delete must be null", secureStore.loadCredentials())
    }

    @Test
    fun testCorruptedVaultRecovery() {
        val creds = TelegramCredentials("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345", "123")
        secureStore.saveCredentials(creds)

        // Deliberately corrupt the encrypted vault file with random garbage bytes
        val vaultFile = File(tempDir, "telegram_vault.enc")
        vaultFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0xFF.toByte(), 0xEE.toByte()))

        // Must handle corrupted ciphertext safely without throwing uncaught exceptions
        val loaded = secureStore.loadCredentials()
        assertNull("Corrupted vault must safely return null without crashing", loaded)
    }

    @Test
    fun testInvalidTokenFormatRejected() {
        val invalidToken = "not_a_valid_token"
        val creds = TelegramCredentials(botToken = invalidToken)
        assertFalse(creds.isValid())

        val saved = secureStore.saveCredentials(creds)
        assertFalse("Invalid credentials must not be saved to disk", saved)
    }

    @Test
    fun testTelegramConfigManagerIntegration() {
        val validToken = "555666777:ABCdefGhIJKlmNoPQRsTUVwxyZ99999"
        val chatId = "@guardian_alerts"
        val creds = TelegramCredentials(validToken, chatId)

        TelegramConfigManager.initializeWithStorage(secureStore)
        TelegramConfigManager.setCredentials(creds)

        assertTrue(TelegramConfigManager.isConfigured())
        assertEquals(validToken, TelegramConfigManager.getCredentials()?.botToken)

        // Clear must delete from both memory and persistent storage
        TelegramConfigManager.clear()
        assertFalse(TelegramConfigManager.isConfigured())
        assertFalse(secureStore.hasCredentials())
    }
}
