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
class EmergencyCoordinatorImpl : EmergencyCoordinator {

    private val _activeIncident = MutableStateFlow<EmergencyIncident?>(null)
    override val activeIncident: StateFlow<EmergencyIncident?> = _activeIncident.asStateFlow()

    override fun triggerDiscreetSos(triggerSource: String) {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            source = triggerSource,
            metadata = mapOf("trigger" to "manual_discreet")
        )
        EmergencyStateHolder.recordDistressSignal(signal)

        val incident = EmergencyIncident(
            incidentId = "INC-${System.currentTimeMillis()}",
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggeringSignals = listOf(signal),
            isSilent = true,
            notes = "Discreet SOS initiated via $triggerSource"
        )
        _activeIncident.value = incident
        EmergencyStateHolder.setEmergencyIncident(incident)
    }

    override fun cancelEmergency(reason: String) {
        val current = _activeIncident.value
        if (current != null) {
            val cancelledIncident = current.copy(
                level = EmergencyLevel.CANCELLED,
                notes = "Cancelled: $reason"
            )
            EmergencyStateHolder.setEmergencyIncident(cancelledIncident)
            _activeIncident.value = null
        } else {
            EmergencyStateHolder.setEmergencyIncident(null)
        }
    }

    override fun updateLocation(location: LocationData) {
        EmergencyStateHolder.updateLocation(location)
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
