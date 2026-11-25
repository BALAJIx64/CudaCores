package com.guardiangrid.core.state

import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData

/**
 * Operational mode of GuardianGrid protection.
 */
enum class ProtectionMode {
    STANDBY,
    ACTIVE_MONITORING,
    DISCREET_MONITORING
}

/**
 * Android foreground service and hardware monitoring lifecycle state.
 */
enum class MonitoringServiceState {
    STOPPED,
    STARTING,
    ACTIVE,
    STOPPING,
    ERROR,
    PERMISSION_REQUIRED
}

/**
 * Reactive global application state for GuardianGrid.
 */
data class EmergencyState(
    val mode: ProtectionMode = ProtectionMode.STANDBY,
    val isProtectionActive: Boolean = false,
    val monitoringServiceState: MonitoringServiceState = MonitoringServiceState.STOPPED,
    val monitoringServiceError: String? = null,
    val emergencyLevel: EmergencyLevel = EmergencyLevel.IDLE,
    val activeIncident: EmergencyIncident? = null,
    val recentDistressSignals: List<DistressSignal> = emptyList(),
    val currentLocation: LocationData? = null,
    val trustedContacts: List<EmergencyContact> = emptyList(),
    val recentDeliveryReceipts: List<DeliveryReceipt> = emptyList(),
    val incidentHistory: List<EmergencyIncident> = emptyList(),
    val isAudioMonitoringRunning: Boolean = false,
    val isMotionMonitoringRunning: Boolean = false,
    val isAccelerometerAvailable: Boolean = true,
    val isDiscreetModeEnabled: Boolean = false,
    val isAudioPermissionGranted: Boolean = false,
    val isLocationPermissionGranted: Boolean = false,
    val isNotificationPermissionGranted: Boolean = false,
    val isSmsPermissionGranted: Boolean = false,
    val isGpsEnabled: Boolean = false,
    val statusMessage: String = "GuardianGrid Standby",
    val verificationCountdownRemainingMs: Long = 0L,
    val verificationTotalTimeoutMs: Long = 10000L,
    val lastDetectedSignal: DistressSignal? = null
)
