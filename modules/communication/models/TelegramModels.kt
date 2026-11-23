package com.guardiangrid.modules.communication.models

/**
 * Telegram Bot API inline keyboard button representation.
 */
data class TelegramInlineKeyboardButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null
) {
    fun toJson(): String {
        val parts = mutableListOf<String>()
        parts.add("\"text\": \"${escapeJson(text)}\"")
        callbackData?.let { parts.add("\"callback_data\": \"${escapeJson(it)}\"") }
        url?.let { parts.add("\"url\": \"${escapeJson(it)}\"") }
        return "{${parts.joinToString(", ")}}"
    }

    private fun escapeJson(input: String): String =
        input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

/**
 * Telegram Bot API inline keyboard markup.
 */
data class TelegramInlineKeyboardMarkup(
    val inlineKeyboard: List<List<TelegramInlineKeyboardButton>>
) {
    fun toJson(): String {
        val rows = inlineKeyboard.joinToString(", ") { row ->
            "[" + row.joinToString(", ") { it.toJson() } + "]"
        }
        return "{\"inline_keyboard\": [$rows]}"
    }
}

/**
 * Payload sent to Telegram Bot API `sendMessage` endpoint.
 */
data class TelegramSendMessagePayload(
    val chatId: String,
    val text: String,
    val parseMode: String = "Markdown",
    val disableWebPagePreview: Boolean = false,
    val replyMarkup: TelegramInlineKeyboardMarkup? = null
) {
    fun toJson(): String {
        val parts = mutableListOf<String>()
        parts.add("\"chat_id\": \"${escapeJson(chatId)}\"")
        parts.add("\"text\": \"${escapeJson(text)}\"")
        parts.add("\"parse_mode\": \"${escapeJson(parseMode)}\"")
        parts.add("\"disable_web_page_preview\": $disableWebPagePreview")
        replyMarkup?.let {
            parts.add("\"reply_markup\": ${it.toJson()}")
        }
        return "{${parts.joinToString(", ")}}"
    }

    private fun escapeJson(input: String): String =
        input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

/**
 * Generic response wrapper for Telegram Bot API calls.
 */
data class TelegramApiResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val errorCode: Int? = null,
    val description: String? = null
)

/**
 * Information about a sent Telegram message.
 */
data class TelegramMessageResult(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String?,
    val dateEpochSec: Long,
    val text: String?
)

/**
 * Acknowledgment update extracted from Telegram `getUpdates`.
 */
data class TelegramAcknowledgment(
    val updateId: Long,
    val incidentId: String,
    val acknowledgedByUserId: Long,
    val acknowledgedByUserName: String,
    val timestampEpochMs: Long,
    val callbackQueryId: String? = null
)
