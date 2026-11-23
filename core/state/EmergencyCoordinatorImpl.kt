package com.guardiangrid.core.state

import com.guardiangrid.core.interfaces.EmergencyCoordinator
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Baseline implementation of EmergencyCoordinator.
 * Coordinates audio detection, motion analysis, and communication dispatch.
 * Member 4 maintains and expands this orchestrator.
 */
class EmergencyCoordinatorImpl(
    private val locationProvider: (() -> LocationData?)? = null
) : EmergencyCoordinator {

    private val _activeIncident = MutableStateFlow<EmergencyIncident?>(null)
    override val activeIncident: StateFlow<EmergencyIncident?> = _activeIncident.asStateFlow()

    override fun triggerDiscreetSos(triggerSource: String) {
        triggerEmergency(
            triggerSource = triggerSource,
            type = DistressType.SILENT_ACTIVATION,
            isSilent = true,
            notes = "Discreet SOS activated via $triggerSource"
        )
    }

    /**
     * Trigger a standard manual SOS alert (audible / visible).
     */
    fun triggerManualSos(triggerSource: String = "Manual_SOS_Button", locationOverride: LocationData? = null) {
        triggerEmergency(
            triggerSource = triggerSource,
            type = DistressType.MANUAL_SOS,
            isSilent = false,
            notes = "Manual SOS initiated via $triggerSource",
            locationOverride = locationOverride
        )
    }

    private fun triggerEmergency(
        triggerSource: String,
        type: DistressType,
        isSilent: Boolean,
        notes: String,
        locationOverride: LocationData? = null
    ) {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = type,
            confidence = 1.0f,
            source = triggerSource,
            metadata = mapOf(
                "trigger" to triggerSource,
                "silent" to isSilent.toString()
            )
        )
        EmergencyStateHolder.recordDistressSignal(signal)

        val loc = locationOverride ?: locationProvider?.invoke() ?: EmergencyStateHolder.state.value.currentLocation

        val incident = EmergencyIncident(
            incidentId = "INC-${System.currentTimeMillis()}",
            startTimeEpochMs = System.currentTimeMillis(),
            lastUpdatedEpochMs = System.currentTimeMillis(),
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggeringSignals = listOf(signal),
            location = loc,
            isSilent = isSilent,
            notes = notes
        )
        _activeIncident.value = incident
        EmergencyStateHolder.setDiscreetModeEnabled(isSilent)
        val targetMode = if (isSilent) ProtectionMode.DISCREET_MONITORING else ProtectionMode.ACTIVE_MONITORING
        EmergencyStateHolder.setProtectionActive(true, targetMode)
        EmergencyStateHolder.setEmergencyIncident(incident)
    }

    override fun cancelEmergency(reason: String) {
        val current = _activeIncident.value
        EmergencyStateHolder.setDiscreetModeEnabled(false)
        if (current != null) {
            val cancelledIncident = current.copy(
                level = EmergencyLevel.CANCELLED,
                lastUpdatedEpochMs = System.currentTimeMillis(),
                notes = "Cancelled: $reason"
            )
            EmergencyStateHolder.setEmergencyIncident(cancelledIncident)
            _activeIncident.value = null
        }
        EmergencyStateHolder.setEmergencyIncident(null)
        EmergencyStateHolder.setProtectionActive(false, ProtectionMode.STANDBY)
    }

    override fun updateLocation(location: LocationData) {
        EmergencyStateHolder.updateLocation(location)
        _activeIncident.value?.let { current ->
            val updated = current.copy(
                location = location,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
            _activeIncident.value = updated
            EmergencyStateHolder.setEmergencyIncident(updated)
        }
    }

    override fun startProtection() {
        EmergencyStateHolder.setProtectionActive(true, ProtectionMode.ACTIVE_MONITORING)
        EmergencyStateHolder.setSubsystemStatus(audioRunning = true, motionRunning = true)
    }

    override fun stopProtection() {
        EmergencyStateHolder.setProtectionActive(false, ProtectionMode.STANDBY)
        EmergencyStateHolder.setSubsystemStatus(audioRunning = false, motionRunning = false)
        _activeIncident.value = null
    }
}
