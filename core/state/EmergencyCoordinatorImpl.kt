package com.guardiangrid.core.state

import android.util.Log
import com.guardiangrid.core.interfaces.AudioDistressDetector
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.interfaces.EmergencyCoordinator
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Production implementation of EmergencyCoordinator.
 * Coordinates audio detection, motion analysis, and resilient communication dispatch.
 * Owned by Member 4 (core/state).
 *
 * ARCHITECTURAL NOTICE — Background Microphone Constraints:
 * Android 14+ (API 34) enforces strict background microphone execution constraints
 * (FOREGROUND_SERVICE_TYPE_MICROPHONE). Audio capture running inside this
 * Activity/Coordinator scope is active while the app is in the foreground. Background
 * continuous acoustic monitoring requires an Android ForegroundService with a persistent
 * notification, planned for Milestone 2.
 */
class EmergencyCoordinatorImpl(
    private val locationProvider: (() -> LocationData?)? = null,
    private val communicator: EmergencyCommunicator? = null,
    private val audioDetector: AudioDistressDetector? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : EmergencyCoordinator {

    private val _activeIncident = MutableStateFlow<EmergencyIncident?>(null)
    override val activeIncident: StateFlow<EmergencyIncident?> = _activeIncident.asStateFlow()

    private var audioMonitoringJob: Job? = null

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

        // Initiate real multi-channel dispatch if communicator is injected
        communicator?.let { comm ->
            val payload = AlertPayload(
                incidentId = incident.incidentId,
                timestampEpochMs = incident.startTimeEpochMs,
                distressType = type,
                confidence = 1.0f,
                location = loc,
                emergencyMessage = if (isSilent) "DISCREET SOS: Silent distress alert triggered." else "MANUAL SOS: Emergency assistance requested.",
                recipientContacts = EmergencyStateHolder.state.value.trustedContacts
            )
            coroutineScope.launch {
                try {
                    val receipts = comm.dispatchAlert(payload, listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS))
                    receipts.forEach { EmergencyStateHolder.recordDeliveryReceipt(it) }
                } catch (e: Exception) {
                    Log.e("EmergencyCoordinator", "Alert dispatch failed: ${e.message}")
                }
            }
        }
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
        val hasAudioPermission = EmergencyStateHolder.state.value.isAudioPermissionGranted
        EmergencyStateHolder.setSubsystemStatus(audioRunning = hasAudioPermission && audioDetector != null, motionRunning = false)

        if (hasAudioPermission && audioDetector != null) {
            startAudioMonitoring()
        }
    }

    override fun stopProtection() {
        EmergencyStateHolder.setProtectionActive(false, ProtectionMode.STANDBY)
        EmergencyStateHolder.setSubsystemStatus(audioRunning = false, motionRunning = false)
        stopAudioMonitoring()
        _activeIncident.value = null
    }

    private fun startAudioMonitoring() {
        stopAudioMonitoring()
        val detector = audioDetector ?: return
        audioMonitoringJob = coroutineScope.launch {
            try {
                detector.startMonitoring().collect { signal ->
                    handleDistressSignal(signal)
                }
            } catch (e: Exception) {
                Log.w("EmergencyCoordinator", "Audio monitoring interrupted: ${e.message}")
            }
        }
    }

    private fun stopAudioMonitoring() {
        audioMonitoringJob?.cancel()
        audioMonitoringJob = null
        audioDetector?.stopMonitoring()
    }

    private fun handleDistressSignal(signal: DistressSignal) {
        EmergencyStateHolder.recordDistressSignal(signal)

        // Validate signal confidence and distress type before escalating
        when (signal.type) {
            DistressType.SCREAM -> {
                if (signal.confidence >= 0.75f) {
                    triggerEmergency(
                        triggerSource = signal.source,
                        type = DistressType.SCREAM,
                        isSilent = false,
                        notes = "Acoustic scream distress detected (confidence: ${(signal.confidence * 100).toInt()}%)"
                    )
                }
            }
            DistressType.KEYWORD -> {
                // High confidence threshold for automatic keyword dispatch to prevent false alarms from ambient chatter
                if (signal.confidence >= 0.82f) {
                    val kw = signal.metadata["keyword"] ?: "Distress Keyword"
                    triggerEmergency(
                        triggerSource = signal.source,
                        type = DistressType.KEYWORD,
                        isSilent = false,
                        notes = "Emergency keyword '$kw' recognized (confidence: ${(signal.confidence * 100).toInt()}%)"
                    )
                }
            }
            else -> {
                // Motion anomaly signals will be handled by multimodal fusion in Milestone 2
            }
        }
    }
}
