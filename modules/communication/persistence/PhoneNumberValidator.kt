package com.guardiangrid.modules.communication.persistence

/**
 * Result of phone number validation and normalization.
 */
data class PhoneValidationResult(
    val isValid: Boolean,
    val normalizedNumber: String,
    val errorMessage: String? = null
)

/**
 * Validates and normalizes international telephone numbers according to ITU-T E.164.
 *
 * Rules:
 * - Leading '+' is preserved or prefixed if specified.
 * - Whitespace, hyphens, parentheses, and dots are stripped.
 * - Digits must be between 7 and 15 digits (ITU-T E.164 standard).
 * - First non-'+' digit cannot be '0' if international format with '+' is used.
 */
object PhoneNumberValidator {

    private val E164_REGEX = Regex("""^\+?[1-9]\d{6,14}$""")

    fun validateAndNormalize(rawNumber: String): PhoneValidationResult {
        val trimmed = rawNumber.trim()
        if (trimmed.isBlank()) {
            return PhoneValidationResult(
                isValid = false,
                normalizedNumber = "",
                errorMessage = "Phone number cannot be blank"
            )
        }

        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }

        if (digitsOnly.length < 7) {
            return PhoneValidationResult(
                isValid = false,
                normalizedNumber = trimmed,
                errorMessage = "Phone number must contain at least 7 digits (found ${digitsOnly.length})"
            )
        }

        if (digitsOnly.length > 15) {
            return PhoneValidationResult(
                isValid = false,
                normalizedNumber = trimmed,
                errorMessage = "Phone number exceeds maximum international length of 15 digits (found ${digitsOnly.length})"
            )
        }

        val normalized = if (hasPlus) "+$digitsOnly" else digitsOnly

        if (!E164_REGEX.matches(normalized)) {
            return PhoneValidationResult(
                isValid = false,
                normalizedNumber = normalized,
                errorMessage = "Invalid international format. Must follow E.164 standard (e.g. +14155552671 or +919876543210)"
            )
        }

        return PhoneValidationResult(
            isValid = true,
            normalizedNumber = normalized
        )
    }

    fun isValid(rawNumber: String): Boolean = validateAndNormalize(rawNumber).isValid

    fun normalize(rawNumber: String): String = validateAndNormalize(rawNumber).normalizedNumber
}
