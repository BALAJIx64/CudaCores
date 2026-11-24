package com.guardiangrid.modules.communication.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Real Android implementation of [SmsSender] using system Telephony and SmsManager services.
 *
 * Capabilities:
 * - Checks runtime permissions ([Manifest.permission.SEND_SMS]) before attempting dispatch.
 * - Checks cellular radio and SIM card presence via TelephonyManager.
 * - Uses platform-appropriate SmsManager:
 *   - Android 12+ (API 31+): context.getSystemService(SmsManager::class.java)
 *   - Legacy: SmsManager.getDefault()
 * - Automatically divides long text (> 160 characters) into multipart SMS segments.
 * - Captures SecurityException or hardware errors and returns structured [SmsSendResult].
 */
class AndroidSmsSender(
    private val context: Context
) : SmsSender {

    override fun isAvailable(): Boolean {
        // 1. Permission check
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) return false

        // 2. SIM / Radio check
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        return simState != TelephonyManager.SIM_STATE_ABSENT && simState != TelephonyManager.SIM_STATE_UNKNOWN
    }

    override fun sendSms(destinationAddress: String, text: String): SmsSendResult {
        // 1. Verify runtime permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return SmsSendResult(
                isSuccess = false,
                errorMessage = "SMS permission (SEND_SMS) not granted by user"
            )
        }

        if (destinationAddress.isBlank()) {
            return SmsSendResult(
                isSuccess = false,
                errorMessage = "Destination phone address is blank"
            )
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (text.length > 160) {
                val parts = smsManager.divideMessage(text)
                smsManager.sendMultipartTextMessage(
                    destinationAddress,
                    null,
                    parts,
                    null,
                    null
                )
                SmsSendResult(isSuccess = true, partsCount = parts.size)
            } else {
                smsManager.sendTextMessage(
                    destinationAddress,
                    null,
                    text,
                    null,
                    null
                )
                SmsSendResult(isSuccess = true, partsCount = 1)
            }
        } catch (se: SecurityException) {
            SmsSendResult(
                isSuccess = false,
                errorMessage = "SecurityException: SMS permission denied or revoked: ${se.message}"
            )
        } catch (e: Exception) {
            SmsSendResult(
                isSuccess = false,
                errorMessage = "Telephony dispatch error: ${e.message ?: "Failed to transmit SMS"}"
            )
        }
    }
}
