package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.LocationData
import kotlinx.coroutines.flow.StateFlow

/**
 * Core emergency orchestrator and escalation state manager.
 * Owned by Member 4 (core/state and app).
 */
interface EmergencyCoordinator {
    /**
     * Current active incident, or null if system is in normal monitoring mode.
     */
    val activeIncident: StateFlow<EmergencyIncident?>

    /**
     * Trigger a discreet manual SOS alert (e.g., volume button combination, silent UI button).
     */
    fun triggerDiscreetSos(triggerSource: String)

    /**
     * Manually cancel an ongoing alarm or false positive.
     */
    fun cancelEmergency(reason: String)

    /**
     * Manually resolve an ongoing emergency incident.
     */
    fun resolveEmergency(reason: String)

    /**
     * Update location tracking state.
     */
    fun updateLocation(location: LocationData)

    /**
     * Start background protection service.
     */
    fun startProtection()

    /**
     * Stop background protection service.
     */
    fun stopProtection()
}
