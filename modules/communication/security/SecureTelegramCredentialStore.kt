package com.guardiangrid.modules.communication.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Interface defining secure credential persistence operations for GuardianGrid.
 */
interface SecureCredentialStorage {
    fun saveCredentials(credentials: TelegramCredentials): Boolean
    fun loadCredentials(): TelegramCredentials?
    fun deleteCredentials(): Boolean
    fun hasCredentials(): Boolean
}

/**
 * Hardware-backed and Keystore-anchored credential storage for Telegram Bot API tokens.
 *
 * Security Specifications:
 * - Uses AES-256 in GCM mode (AES/GCM/NoPadding) with a 128-bit authentication tag.
 * - Generates unique 12-byte initialization vectors (IV) for every encryption operation.
 * - Key management is anchored to AndroidKeyStore whenever running on Android platform.
 * - Graceful fallback to software AES-GCM when executing in JVM testing environments.
 * - Never logs unmasked tokens or sensitive cryptographic material.
 * - Handles corrupted files and key invalidation safely.
 */
class SecureTelegramCredentialStore(
    private val storageDir: File,
    private val keyAlias: String = "GuardianGridTelegramMasterKey"
) : SecureCredentialStorage {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val VAULT_FILE_NAME = "telegram_vault.enc"
    }

    private val vaultFile: File get() = File(storageDir, VAULT_FILE_NAME)
    private val isAndroidKeyStoreAvailable: Boolean by lazy {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    // Software key used strictly in headless JVM unit test environments where AndroidKeyStore is absent
    private var jvmFallbackKey: SecretKey? = null

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    @Synchronized
    override fun saveCredentials(credentials: TelegramCredentials): Boolean {
        if (!credentials.isValid()) {
            return false
        }

        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val rawData = "${credentials.botToken}\n${credentials.defaultChatId ?: ""}".toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(rawData)

            // Structure: [12-byte IV] + [Ciphertext + 16-byte GCM Tag]
            val tempFile = File(storageDir, "$VAULT_FILE_NAME.tmp")
            tempFile.outputStream().use { os ->
                os.write(iv)
                os.write(ciphertext)
                os.flush()
            }

            if (tempFile.exists()) {
                if (vaultFile.exists()) {
                    vaultFile.delete()
                }
                tempFile.renameTo(vaultFile)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun loadCredentials(): TelegramCredentials? {
        if (!vaultFile.exists() || vaultFile.length() <= IV_LENGTH_BYTES) {
            return null
        }

        return try {
            val fileBytes = vaultFile.readBytes()
            if (fileBytes.size <= IV_LENGTH_BYTES) {
                return null
            }

            val iv = fileBytes.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = fileBytes.copyOfRange(IV_LENGTH_BYTES, fileBytes.size)

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decrypted = cipher.doFinal(ciphertext)
            val payload = String(decrypted, Charsets.UTF_8)
            val lines = payload.split("\n")
            if (lines.isEmpty()) {
                return null
            }

            val botToken = lines[0].trim()
            val chatId = lines.getOrNull(1)?.trim()?.ifBlank { null }

            val creds = TelegramCredentials(botToken = botToken, defaultChatId = chatId)
            if (creds.isValid()) creds else null
        } catch (e: Exception) {
            // Corrupted vault or invalidated key; safely return null without crashing
            null
        }
    }

    @Synchronized
    override fun deleteCredentials(): Boolean {
        return try {
            if (vaultFile.exists()) {
                vaultFile.delete()
            }
            if (isAndroidKeyStoreAvailable) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias)
                }
            } else {
                jvmFallbackKey = null
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun hasCredentials(): Boolean {
        return vaultFile.exists() && vaultFile.length() > IV_LENGTH_BYTES
    }

    private fun getOrCreateKey(): SecretKey {
        return if (isAndroidKeyStoreAvailable) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.getKey(keyAlias, null) as SecretKey
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(false) // We supply our own cryptographically secure random IV
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } else {
            // Headless JVM test mode
            if (jvmFallbackKey == null) {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256)
                jvmFallbackKey = kg.generateKey()
            }
            jvmFallbackKey!!
        }
    }
}
