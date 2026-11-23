package com.guardiangrid.modules.communication.security

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe configuration manager for Telegram Bot API credentials.
 * Supports runtime injection, environment variable resolution, and secure clear operations.
 */
object TelegramConfigManager {

    private val credentialsRef = AtomicReference<TelegramCredentials?>(null)

    init {
        // Attempt fallback to environment variable on initialization
        val envCreds = TelegramCredentials.fromEnvironment()
        if (envCreds != null && envCreds.isValid()) {
            credentialsRef.set(envCreds)
        }
    }

    /**
     * Programmatically configures Telegram credentials (e.g. from user settings or tests).
     */
    fun setCredentials(credentials: TelegramCredentials) {
        credentialsRef.set(credentials)
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
     * Clears stored credentials from memory (e.g., on user logout or privacy reset).
     */
    fun clear() {
        credentialsRef.set(null)
    }
}
