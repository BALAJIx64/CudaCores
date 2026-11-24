package com.guardiangrid.modules.communication.security

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe configuration manager for Telegram Bot API credentials.
 * Supports runtime injection, environment variable resolution, and secure clear operations.
 */
object TelegramConfigManager {

    private val credentialsRef = AtomicReference<TelegramCredentials?>(null)
    private var persistentStorage: SecureCredentialStorage? = null

    init {
        // Attempt fallback to environment variable on initialization
        val envCreds = TelegramCredentials.fromEnvironment()
        if (envCreds != null && envCreds.isValid()) {
            credentialsRef.set(envCreds)
        }
    }

    /**
     * Initializes the manager with Keystore-backed secure storage.
     * Loads any pre-existing encrypted credentials.
     */
    @Synchronized
    fun initializeWithStorage(storage: SecureCredentialStorage) {
        this.persistentStorage = storage
        val loaded = storage.loadCredentials()
        if (loaded != null && loaded.isValid()) {
            credentialsRef.set(loaded)
        }
    }

    /**
     * Programmatically configures Telegram credentials (e.g. from user settings or tests)
     * and persists them to Keystore-backed storage if initialized.
     */
    fun setCredentials(credentials: TelegramCredentials) {
        credentialsRef.set(credentials)
        persistentStorage?.saveCredentials(credentials)
    }

    /**
     * Sets token and optional default chat ID directly.
     */
    fun setCredentials(botToken: String, defaultChatId: String? = null) {
        setCredentials(TelegramCredentials(botToken.trim(), defaultChatId?.trim()))
    }

    /**
     * Retrieves currently configured credentials.
     */
    fun getCredentials(): TelegramCredentials? = credentialsRef.get()

    /**
     * Returns true if credentials are non-null and valid.
     */
    fun isConfigured(): Boolean = credentialsRef.get()?.isValid() == true

    /**
     * Clears stored credentials from memory and deletes from encrypted persistent storage.
     */
    fun clear() {
        credentialsRef.set(null)
        persistentStorage?.deleteCredentials()
    }

    /**
     * Verifies currently stored credentials by connecting to Telegram Bot API.
     */
    fun testConnection(
        transport: com.guardiangrid.modules.communication.network.HttpTransport = com.guardiangrid.modules.communication.network.DefaultHttpTransport()
    ): com.guardiangrid.modules.communication.telegram.TelegramVerificationResult {
        val creds = getCredentials()
            ?: return com.guardiangrid.modules.communication.telegram.TelegramVerificationResult(
                isSuccess = false,
                errorMessage = "No Telegram credentials configured"
            )
        val dispatcher = com.guardiangrid.modules.communication.telegram.TelegramEmergencyDispatcher(transport)
        return dispatcher.verifyBotToken(creds.botToken)
    }
}
