package com.guardiangrid.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.guardiangrid.core.state.EmergencyStateHolder

/**
 * Representation of an individual system permission required by GuardianGrid.
 */
data class SafetyPermission(
    val permission: String,
    val title: String,
    val description: String,
    val isCritical: Boolean,
    val iconName: String
)

/**
 * Utility to query, categorize, and synchronize system permissions.
 */
object PermissionManager {

    val REQUIRED_PERMISSIONS: List<SafetyPermission> = buildList {
        add(
            SafetyPermission(
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Microphone Access",
                description = "Required to identify screams, crying, and vocal distress sounds on-device. Audio is processed locally and discarded immediately unless distress is confirmed.",
                isCritical = true,
                iconName = "mic"
            )
        )
        add(
            SafetyPermission(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Precise GPS Location",
                description = "Required to dispatch exact emergency coordinates to trusted contacts and responders when SOS is triggered.",
                isCritical = true,
                iconName = "location"
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                SafetyPermission(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Emergency Notifications",
                    description = "Required to display ongoing background protection status and emergency escalation progress in the status bar.",
                    isCritical = true,
                    iconName = "notifications"
                )
            )
        }
        add(
            SafetyPermission(
                permission = Manifest.permission.SEND_SMS,
                title = "Emergency SMS Fallback",
                description = "Required to transmit emergency coordinates via cellular SMS when internet or mobile data connectivity is unavailable.",
                isCritical = false,
                iconName = "sms"
            )
        )
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCorePermissions(context: Context): Boolean {
        val audioGranted = isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
        val locationGranted = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true

        return audioGranted && locationGranted && notificationGranted
    }

    fun syncPermissionsWithState(context: Context) {
        val audio = isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
        val location = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true
        val sms = isPermissionGranted(context, Manifest.permission.SEND_SMS)

        EmergencyStateHolder.updatePermissions(
            audio = audio,
            location = location,
            notification = notification,
            sms = sms
        )
    }
}
