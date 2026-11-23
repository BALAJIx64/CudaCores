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

    private val _state = MutableStateFlow(
        EmergencyState(
            trustedContacts = listOf(
                EmergencyContact(
                    id = "contact-placeholder-1",
                    name = "Primary Contact (Placeholder)",
                    phoneNumber = "+00000000000",
                    telegramChatId = "@placeholder_channel",
                    isPrimary = true
                )
            )
        )
    )

    val state: StateFlow<EmergencyState> = _state.asStateFlow()

    fun setProtectionActive(active: Boolean, mode: ProtectionMode = ProtectionMode.ACTIVE_MONITORING) {
        _state.update { current ->
            current.copy(
                isProtectionActive = active,
                mode = if (active) mode else ProtectionMode.STANDBY,
                emergencyLevel = if (active) EmergencyLevel.MONITORING else EmergencyLevel.IDLE,
                statusMessage = if (active) "Active Protection Running" else "Protection Standby"
            )
        }
    }

    fun recordDistressSignal(signal: DistressSignal) {
        _state.update { current ->
            val updatedList = (listOf(signal) + current.recentDistressSignals).take(20)
            current.copy(recentDistressSignals = updatedList)
        }
    }

    fun setEmergencyIncident(incident: EmergencyIncident?) {
        _state.update { current ->
            current.copy(
                activeIncident = incident,
                emergencyLevel = incident?.level ?: if (current.isProtectionActive) EmergencyLevel.MONITORING else EmergencyLevel.IDLE,
                statusMessage = when (incident?.level) {
                    EmergencyLevel.VERIFYING -> "Verifying Multimodal Distress..."
                    EmergencyLevel.CONFIRMED_EMERGENCY -> "EMERGENCY ESCALATED - Alerts Dispatched"
                    EmergencyLevel.SUSPICIOUS -> "Suspicious Activity Detected"
                    EmergencyLevel.CANCELLED -> "Alert Cancelled by User"
                    EmergencyLevel.RESOLVED -> "Emergency Resolved"
                    else -> current.statusMessage
                }
            )
        }
    }

    fun updateLocation(location: LocationData) {
        _state.update { it.copy(currentLocation = location) }
    }

    fun recordDeliveryReceipt(receipt: DeliveryReceipt) {
        _state.update { current ->
            current.copy(
                recentDeliveryReceipts = (listOf(receipt) + current.recentDeliveryReceipts).take(10)
            )
        }
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
