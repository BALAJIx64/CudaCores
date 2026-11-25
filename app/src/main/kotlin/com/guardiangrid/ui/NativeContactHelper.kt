package com.guardiangrid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.guardiangrid.modules.communication.persistence.PhoneNumberValidator

/**
 * Details resolved from Android's native ContactsContract picker.
 */
data class PickedPhoneContact(
    val displayName: String,
    val rawPhoneNumber: String,
    val normalizedPhoneNumber: String,
    val isPhoneValid: Boolean,
    val validationError: String? = null
)

/**
 * Native Android contact picker & safe cursor resolution utility.
 * Replaces manual phone number entry by picking directly from system Contacts.
 */
object NativeContactHelper {

    fun buildPhonePickerIntent(): Intent {
        return Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
    }

    fun resolvePickedPhoneContact(context: Context, contactUri: Uri): Result<PickedPhoneContact> {
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return Result.failure(IllegalArgumentException("No phone number found on selected contact"))
                }
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                val rawNumber = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""

                if (rawNumber.isBlank()) {
                    return Result.failure(IllegalArgumentException("Selected contact does not have a phone number"))
                }

                // Clean common local formatting (spaces/dashes/parens/trunk '0' on 11-digit local numbers)
                val candidate = normalizeContactBookNumber(rawNumber)
                val validation = PhoneNumberValidator.validateAndNormalize(candidate)

                Result.success(
                    PickedPhoneContact(
                        displayName = name.ifBlank { validation.normalizedNumber },
                        rawPhoneNumber = rawNumber,
                        normalizedPhoneNumber = validation.normalizedNumber,
                        isPhoneValid = validation.isValid,
                        validationError = validation.errorMessage
                    )
                )
            } ?: Result.failure(IllegalStateException("Unable to read selected contact"))
        } catch (se: SecurityException) {
            Result.failure(SecurityException("Permission denied reading selected contact: ${se.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun normalizeContactBookNumber(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        // Handle local trunk '0' prefix (e.g. 09443417407 -> 9443417407) when no '+' is present
        val adjustedDigits = if (!hasPlus && digits.length == 11 && digits.startsWith("0")) {
            digits.substring(1)
        } else {
            digits
        }
        return if (hasPlus) "+$adjustedDigits" else adjustedDigits
    }
}
