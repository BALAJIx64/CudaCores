package com.guardiangrid.core.state

import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe singleton state holder for the application.
 * Provides unified reactive state to Compose UI and background services.
 * Owned by Member 4 (core/state).
 */
object EmergencyStateHolder {

    const val MAX_INCIDENT_HISTORY = 20
    const val MAX_DELIVERY_RECEIPTS = 20
    const val MAX_DISTRESS_SIGNALS = 20

    private val _state = MutableStateFlow(
        EmergencyState(trustedContacts = emptyList())
    )

    val state: StateFlow<EmergencyState> = _state.asStateFlow()

    fun reset() {
        _state.value = EmergencyState(trustedContacts = emptyList())
    }

    fun setProtectionActive(active: Boolean, mode: ProtectionMode = ProtectionMode.ACTIVE_MONITORING) {
        _state.update { current ->
            current.copy(
                isProtectionActive = active,
                monitoringServiceState = if (active) MonitoringServiceState.ACTIVE else MonitoringServiceState.STOPPED,
                mode = if (active) mode else ProtectionMode.STANDBY,
                emergencyLevel = if (active) EmergencyLevel.MONITORING else EmergencyLevel.IDLE,
                statusMessage = if (active) "Active Protection Running" else "Protection Standby"
            )
        }
    }

    fun setMonitoringServiceState(serviceState: MonitoringServiceState, errorMessage: String? = null) {
        _state.update { current ->
            val active = (serviceState == MonitoringServiceState.ACTIVE)
            current.copy(
                monitoringServiceState = serviceState,
                monitoringServiceError = errorMessage,
                isProtectionActive = active,
                mode = if (active) {
                    if (current.isDiscreetModeEnabled) ProtectionMode.DISCREET_MONITORING else ProtectionMode.ACTIVE_MONITORING
                } else if (serviceState == MonitoringServiceState.STARTING) {
                    current.mode
                } else {
                    ProtectionMode.STANDBY
                },
                emergencyLevel = if (active && current.emergencyLevel == EmergencyLevel.IDLE) {
                    EmergencyLevel.MONITORING
                } else if (serviceState == MonitoringServiceState.STOPPED && current.emergencyLevel == EmergencyLevel.MONITORING) {
                    EmergencyLevel.IDLE
                } else {
                    current.emergencyLevel
                },
                statusMessage = when (serviceState) {
                    MonitoringServiceState.ACTIVE -> "Active Protection Running"
                    MonitoringServiceState.STARTING -> "Starting Protection Service..."
                    MonitoringServiceState.STOPPING -> "Stopping Protection Service..."
                    MonitoringServiceState.PERMISSION_REQUIRED -> errorMessage ?: "Microphone Permission Required"
                    MonitoringServiceState.ERROR -> errorMessage ?: "Monitoring Error"
                    MonitoringServiceState.STOPPED -> "Protection Standby"
                }
            )
        }
    }

    fun setDiscreetModeEnabled(enabled: Boolean) {
        _state.update { it.copy(isDiscreetModeEnabled = enabled) }
    }

    fun updateTrustedContacts(contacts: List<EmergencyContact>) {
        _state.update { it.copy(trustedContacts = contacts) }
    }

    fun addTrustedContact(contact: EmergencyContact) {
        _state.update { current ->
            current.copy(trustedContacts = current.trustedContacts + contact)
        }
    }

    fun removeTrustedContact(contactId: String) {
        _state.update { current ->
            current.copy(trustedContacts = current.trustedContacts.filter { it.id != contactId })
        }
    }

    fun recordDistressSignal(signal: DistressSignal) {
        _state.update { current ->
            val updatedList = (listOf(signal) + current.recentDistressSignals).take(MAX_DISTRESS_SIGNALS)
            current.copy(
                recentDistressSignals = updatedList,
                lastDetectedSignal = signal
            )
        }
    }

    fun updateVerificationCountdown(remainingMs: Long) {
        _state.update { it.copy(verificationCountdownRemainingMs = remainingMs) }
    }

    fun updateVerificationTotalTimeout(totalMs: Long) {
        _state.update { it.copy(verificationTotalTimeoutMs = totalMs.coerceAtLeast(1000L)) }
    }

    fun updateMotionHardwareAvailability(accelAvailable: Boolean) {
        _state.update { it.copy(isAccelerometerAvailable = accelAvailable) }
    }

    fun updateStatusMessage(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }

    fun setEmergencyIncident(incident: EmergencyIncident?) {
        _state.update { current ->
            val updatedHistory = if (incident != null && (incident.level == EmergencyLevel.CONFIRMED_EMERGENCY || incident.level == EmergencyLevel.CANCELLED || incident.level == EmergencyLevel.RESOLVED)) {
                (listOf(incident) + current.incidentHistory.filter { it.incidentId != incident.incidentId }).take(MAX_INCIDENT_HISTORY)
            } else {
                current.incidentHistory
            }

            current.copy(
                activeIncident = incident,
                incidentHistory = updatedHistory,
                emergencyLevel = incident?.level ?: if (current.isProtectionActive) EmergencyLevel.MONITORING else EmergencyLevel.IDLE,
                statusMessage = when (incident?.level) {
                    EmergencyLevel.VERIFYING -> "Verifying Multimodal Distress..."
                    EmergencyLevel.CONFIRMED_EMERGENCY -> "EMERGENCY ESCALATED - Alerts Dispatched"
                    EmergencyLevel.SUSPICIOUS -> "Suspicious Activity Detected"
                    EmergencyLevel.CANCELLED -> "Alert Cancelled by User"
                    EmergencyLevel.RESOLVED -> "Emergency Resolved"
                    else -> if (current.isProtectionActive) "Active Protection Running" else "Protection Standby"
                }
            )
        }
    }

    fun updateLocation(location: LocationData) {
        _state.update { it.copy(currentLocation = location) }
    }

    fun updateGpsAvailability(enabled: Boolean) {
        _state.update { it.copy(isGpsEnabled = enabled) }
    }

    fun updatePermissions(
        audio: Boolean,
        location: Boolean,
        notification: Boolean = true,
        sms: Boolean = false
    ) {
        _state.update {
            it.copy(
                isAudioPermissionGranted = audio,
                isLocationPermissionGranted = location,
                isNotificationPermissionGranted = notification,
                isSmsPermissionGranted = sms
            )
        }
    }

    fun recordDeliveryReceipt(receipt: DeliveryReceipt) {
        _state.update { current ->
            current.copy(
                recentDeliveryReceipts = (listOf(receipt) + current.recentDeliveryReceipts).take(MAX_DELIVERY_RECEIPTS)
            )
        }
    }

    fun upsertAudioUploadProgress(entry: AudioEvidenceUploadProgress) {
        _state.update { current ->
            val filtered = current.audioUploadProgress.filterNot {
                it.incidentId == entry.incidentId && it.chunkIndex == entry.chunkIndex
            }
            val updated = (filtered + entry)
                .sortedWith(compareBy<AudioEvidenceUploadProgress> { it.incidentId }.thenBy { it.chunkIndex })
                .takeLast(32)
            current.copy(audioUploadProgress = updated)
        }
    }

    fun clearAudioUploadProgress() {
        _state.update { it.copy(audioUploadProgress = emptyList()) }
    }

    fun setSubsystemStatus(audioRunning: Boolean, motionRunning: Boolean) {
        _state.update {
            it.copy(
                isAudioMonitoringRunning = audioRunning,
                isMotionMonitoringRunning = motionRunning
            )
        }
    }
}
