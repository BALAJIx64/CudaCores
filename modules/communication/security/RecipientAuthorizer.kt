package com.guardiangrid.modules.communication.security

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.EmergencyContact

/**
 * Enforces recipient authorization and privacy invariants.
 *
 * Security Invariants:
 * 1. Audio evidence references are NEVER transmitted to unauthorized or secondary recipients.
 * 2. Contact phone numbers and chat IDs are masked in logs and diagnostics.
 */
object RecipientAuthorizer {

    /**
     * Filters or sanitizes an AlertPayload for a specific recipient.
     * If the recipient is not authorized for audio evidence, the audioSegmentPath is stripped.
     */
    fun sanitizePayloadForRecipient(
        payload: AlertPayload,
        recipient: EmergencyContact?
    ): AlertPayload {
        val isAuthorizedForAudio = recipient?.isPrimary == true

        return if (!isAuthorizedForAudio && payload.audioSegmentPath != null) {
            payload.copy(audioSegmentPath = null)
        } else {
            payload
        }
    }

    /**
     * Masks phone numbers for secure logging and diagnostics.
     * Example: `+1234567890` -> `+123****890`
     */
    fun maskPhoneNumber(phoneNumber: String): String {
        if (phoneNumber.length <= 4) return "****"
        val prefix = phoneNumber.take(4)
        val suffix = phoneNumber.takeLast(3)
        return "$prefix****$suffix"
    }

    /**
     * Masks Telegram chat ID for secure logging.
     * Example: `-100123456789` -> `-100****6789`
     */
    fun maskChatId(chatId: String): String {
        if (chatId.length <= 4) return "****"
        val prefix = chatId.take(4)
        val suffix = chatId.takeLast(3)
        return "$prefix****$suffix"
    }
}
