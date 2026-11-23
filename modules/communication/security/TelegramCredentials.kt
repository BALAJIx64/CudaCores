package com.guardiangrid.modules.communication.security

/**
 * Encapsulates credentials required for Telegram Bot API communication.
 *
 * Security Invariants:
 * 1. Tokens must NEVER be hardcoded or committed to git.
 * 2. Logging or toString() must always mask the token.
 */
data class TelegramCredentials(
    val botToken: String,
    val defaultChatId: String? = null
) {
    /**
     * Telegram bot tokens follow the format: `<bot_id>:<secret_hash>`,
     * e.g., `123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ`.
     */
    fun isValid(): Boolean {
        if (botToken.isBlank()) return false
        val parts = botToken.split(":")
        if (parts.size != 2) return false
        val idPart = parts[0]
        val secretPart = parts[1]
        return idPart.all { it.isDigit() } && secretPart.length >= 20
    }

    /**
     * Returns a masked representation of the bot token safe for logging and debugging.
     * Example: `123456789:ABC***xyz`
     */
    fun maskedToken(): String {
        if (botToken.isBlank()) return "[EMPTY]"
        val parts = botToken.split(":")
        if (parts.size != 2) return "[INVALID_FORMAT]"
        val secret = parts[1]
        val prefix = secret.take(3)
        val suffix = secret.takeLast(3)
        return "${parts[0]}:$prefix***$suffix"
    }

    override fun toString(): String {
        return "TelegramCredentials(botToken=${maskedToken()}, defaultChatId=$defaultChatId)"
    }

    companion object {
        const val ENV_BOT_TOKEN = "GUARDIANGRID_TELEGRAM_BOT_TOKEN"
        const val ENV_DEFAULT_CHAT_ID = "GUARDIANGRID_TELEGRAM_CHAT_ID"

        fun fromEnvironment(): TelegramCredentials? {
            val token = System.getenv(ENV_BOT_TOKEN) ?: return null
            val chatId = System.getenv(ENV_DEFAULT_CHAT_ID)
            return TelegramCredentials(botToken = token.trim(), defaultChatId = chatId?.trim())
        }
    }
}
