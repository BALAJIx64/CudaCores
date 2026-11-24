package com.guardiangrid.core.state

import android.util.Log
import com.guardiangrid.core.interfaces.AudioDistressDetector
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.interfaces.EmergencyCoordinator
import com.guardiangrid.core.interfaces.MotionAnomalyDetector
import com.guardiangrid.core.interfaces.MultimodalFusionEngine
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import com.guardiangrid.modules.motion.state.EmergencyStateMachine
import com.guardiangrid.modules.motion.state.MotionEmergencyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Production implementation of EmergencyCoordinator.
 * Coordinates audio detection, motion analysis, multimodal fusion, and resilient communication dispatch.
 * Reconciles the seven-state EmergencyStateMachine with the global EmergencyStateHolder.
 * Owned by Member 4 (core/state).
 *
 * ARCHITECTURAL NOTICE — Background Microphone Constraints:
 * Android 14+ (API 34) enforces strict background microphone execution constraints
 * (FOREGROUND_SERVICE_TYPE_MICROPHONE). Audio capture running inside this
 * Activity/Coordinator scope is active while the app is in the foreground. Background
 * continuous acoustic monitoring requires an Android ForegroundService with a persistent
 * notification.
 */
class EmergencyCoordinatorImpl(
    private val locationProvider: (() -> LocationData?)? = null,
    private val communicator: EmergencyCommunicator? = null,
    private val audioDetector: AudioDistressDetector? = null,
    private val motionDetector: MotionAnomalyDetector? = null,
    private val fusionEngine: MultimodalFusionEngine = MultimodalFusionEngineImpl(),
    val stateMachine: EmergencyStateMachine = EmergencyStateMachine(
        fusionEngine = if (fusionEngine is MultimodalFusionEngineImpl) fusionEngine else MultimodalFusionEngineImpl()
    ),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : EmergencyCoordinator {

    private val _activeIncident = MutableStateFlow<EmergencyIncident?>(null)
    override val activeIncident: StateFlow<EmergencyIncident?> = _activeIncident.asStateFlow()

    private var audioMonitoringJob: Job? = null
    private var motionMonitoringJob: Job? = null
    private var verificationCountdownJob: Job? = null

    @Volatile
    private var activeIncidentDispatched = false

    override fun triggerDiscreetSos(triggerSource: String) {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.SILENT_ACTIVATION,
            confidence = 1.0f,
            source = triggerSource,
            metadata = mapOf("trigger" to triggerSource, "silent" to "true")
        )
        EmergencyStateHolder.recordDistressSignal(signal)
        val nextState = stateMachine.onSignal(signal)
        reconcileState(nextState, signal, isSilent = true)
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
            metadata = mapOf("trigger" to triggerSource, "silent" to isSilent.toString())
        )
        EmergencyStateHolder.recordDistressSignal(signal)
        val nextState = if (type == DistressType.MANUAL_SOS) {
            stateMachine.triggerManualSos(triggerSource)
        } else {
            stateMachine.onSignal(signal)
        }
        reconcileState(nextState, signal, isSilent = isSilent, locationOverride = locationOverride)
    }

    override fun cancelEmergency(reason: String) {
        cancelVerificationCountdown()
        stateMachine.cancelEmergency(reason)
        EmergencyStateHolder.setDiscreetModeEnabled(false)

        val current = _activeIncident.value
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
        EmergencyStateHolder.updateVerificationCountdown(0L)
        activeIncidentDispatched = false
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
        stateMachine.resetToNormal()
        activeIncidentDispatched = false

        val hasAudioPermission = EmergencyStateHolder.state.value.isAudioPermissionGranted
        val hasMotion = motionDetector != null

        EmergencyStateHolder.setSubsystemStatus(
            audioRunning = hasAudioPermission && audioDetector != null,
            motionRunning = hasMotion
        )

        if (hasAudioPermission && audioDetector != null) {
            startAudioMonitoring()
        }

        if (hasMotion) {
            startMotionMonitoring()
        }
    }

    override fun stopProtection() {
        EmergencyStateHolder.setProtectionActive(false, ProtectionMode.STANDBY)
        EmergencyStateHolder.setSubsystemStatus(audioRunning = false, motionRunning = false)
        cancelVerificationCountdown()
        stopAudioMonitoring()
        stopMotionMonitoring()
        stateMachine.resetToNormal()
        _activeIncident.value = null
        activeIncidentDispatched = false
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

    private fun startMotionMonitoring() {
        stopMotionMonitoring()
        val detector = motionDetector ?: return
        motionMonitoringJob = coroutineScope.launch {
            try {
                detector.startMonitoring().collect { signal ->
                    handleDistressSignal(signal)
                }
            } catch (e: Exception) {
                Log.w("EmergencyCoordinator", "Motion monitoring interrupted: ${e.message}")
            }
        }
    }

    private fun stopMotionMonitoring() {
        motionMonitoringJob?.cancel()
        motionMonitoringJob = null
        motionDetector?.stopMonitoring()
    }

    fun handleDistressSignal(signal: DistressSignal) {
        // Record signal in state history
        EmergencyStateHolder.recordDistressSignal(signal)

        if (signal.type == DistressType.SCREAM && _activeIncident.value == null) {
            triggerEmergency(
                triggerSource = signal.source,
                type = DistressType.SCREAM,
                isSilent = false,
                notes = "Autonomous acoustic scream detected by ${signal.source} (Confidence: ${(signal.confidence * 100).toInt()}%)"
            )
        }

        // Evaluate signal through unified state machine & multimodal fusion engine
        val nextState = stateMachine.onSignal(signal)
        reconcileState(nextState, signal)
    }

    private fun reconcileState(
        nextState: MotionEmergencyState,
        triggeringSignal: DistressSignal?,
        isSilent: Boolean = (triggeringSignal?.type == DistressType.SILENT_ACTIVATION),
        locationOverride: LocationData? = null
    ) {
        when (nextState) {
            MotionEmergencyState.ALERTED -> {
                cancelVerificationCountdown()
                escalateToConfirmedEmergency(triggeringSignal, isSilent = isSilent, locationOverride = locationOverride)
            }
            MotionEmergencyState.VERIFYING, MotionEmergencyState.HIGH_CONCERN -> {
                val isAlreadyVerifying = verificationCountdownJob?.isActive == true
                val emergencyLevel = EmergencyLevel.VERIFYING

                val loc = locationOverride ?: locationProvider?.invoke() ?: EmergencyStateHolder.state.value.currentLocation
                val notes = "Verifying multimodal distress (${triggeringSignal?.type?.name ?: "ANOMALY"} ${(triggeringSignal?.confidence?.times(100))?.toInt() ?: 0}%)..."

                val incident = _activeIncident.value?.copy(
                    level = emergencyLevel,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    location = loc,
                    notes = notes
                ) ?: EmergencyIncident(
                    incidentId = "INC-${System.currentTimeMillis()}",
                    startTimeEpochMs = System.currentTimeMillis(),
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    level = emergencyLevel,
                    triggeringSignals = listOfNotNull(triggeringSignal),
                    location = loc,
                    isSilent = isSilent,
                    notes = notes
                )

                _activeIncident.value = incident
                EmergencyStateHolder.setEmergencyIncident(incident)

                if (!isAlreadyVerifying) {
                    startVerificationCountdown(
                        sessionId = stateMachine.activeVerificationSessionId,
                        timeoutMs = stateMachine.verificationTimeoutMs,
                        triggeringSignal = triggeringSignal,
                        isSilent = isSilent
                    )
                }
            }
            MotionEmergencyState.SUSPECTED_DISTRESS -> {
                EmergencyStateHolder.updateStatusMessage("Suspicious anomaly observed. Heightened monitoring.")
            }
            MotionEmergencyState.CANCELLED -> {
                cancelVerificationCountdown()
                _activeIncident.value?.let { current ->
                    val cancelled = current.copy(
                        level = EmergencyLevel.CANCELLED,
                        lastUpdatedEpochMs = System.currentTimeMillis(),
                        notes = "Alert cancelled by user."
                    )
                    EmergencyStateHolder.setEmergencyIncident(cancelled)
                    _activeIncident.value = null
                }
                EmergencyStateHolder.updateVerificationCountdown(0L)
            }
            MotionEmergencyState.RESOLVED -> {
                cancelVerificationCountdown()
                _activeIncident.value?.let { current ->
                    val resolved = current.copy(
                        level = EmergencyLevel.RESOLVED,
                        lastUpdatedEpochMs = System.currentTimeMillis(),
                        notes = "Emergency marked resolved."
                    )
                    EmergencyStateHolder.setEmergencyIncident(resolved)
                    _activeIncident.value = null
                }
                EmergencyStateHolder.updateVerificationCountdown(0L)
            }
            MotionEmergencyState.NORMAL -> {
                cancelVerificationCountdown()
                EmergencyStateHolder.updateVerificationCountdown(0L)
            }
        }
    }

    private fun startVerificationCountdown(
        sessionId: Long,
        timeoutMs: Long,
        triggeringSignal: DistressSignal?,
        isSilent: Boolean = false
    ) {
        cancelVerificationCountdown()
        verificationCountdownJob = coroutineScope.launch {
            var remaining = timeoutMs
            EmergencyStateHolder.updateVerificationCountdown(remaining)

            val stepMs = 250L
            while (remaining > 0 && isActive) {
                delay(stepMs)
                remaining = (remaining - stepMs).coerceAtLeast(0L)
                stateMachine.onCountdownTick(remaining, sessionId)
                EmergencyStateHolder.updateVerificationCountdown(remaining)
            }

            if (isActive && remaining <= 0L) {
                EmergencyStateHolder.updateVerificationCountdown(0L)
                val timeoutState = stateMachine.onVerificationTimeout(sessionId)
                if (timeoutState == MotionEmergencyState.ALERTED) {
                    escalateToConfirmedEmergency(triggeringSignal, isSilent = isSilent, isTimeout = true)
                }
            }
        }
    }

    private fun cancelVerificationCountdown() {
        verificationCountdownJob?.cancel()
        verificationCountdownJob = null
        EmergencyStateHolder.updateVerificationCountdown(0L)
    }

    private fun escalateToConfirmedEmergency(
        triggeringSignal: DistressSignal?,
        isSilent: Boolean = false,
        isTimeout: Boolean = false,
        locationOverride: LocationData? = null
    ) {
        cancelVerificationCountdown()

        val currentIncident = _activeIncident.value
        if (currentIncident != null && currentIncident.level == EmergencyLevel.CONFIRMED_EMERGENCY && activeIncidentDispatched) {
            return
        }

        val type = triggeringSignal?.type ?: DistressType.MANUAL_SOS
        val notes = when {
            isSilent -> "Discreet SOS activated via ${triggeringSignal?.source ?: "Hardware"}"
            type == DistressType.MANUAL_SOS -> "Manual SOS initiated via ${triggeringSignal?.source ?: "Button"}"
            isTimeout -> "Verification countdown expired without cancellation. Automatic emergency dispatch confirmed."
            type == DistressType.SCREAM -> "Acoustic scream distress confirmed (confidence: ${(triggeringSignal?.confidence?.times(100))?.toInt()}%)"
            type == DistressType.KEYWORD -> "Emergency keyword '${triggeringSignal?.metadata?.get("keyword") ?: "distress"}' confirmed (confidence: ${(triggeringSignal?.confidence?.times(100))?.toInt()}%)"
            type == DistressType.FALL_IMPACT -> "Fall impact distress confirmed (confidence: ${(triggeringSignal?.confidence?.times(100))?.toInt()}%)"
            type == DistressType.MOTION_ANOMALY -> "Motion struggle anomaly confirmed (confidence: ${(triggeringSignal?.confidence?.times(100))?.toInt()}%)"
            else -> "Multimodal distress confirmed."
        }

        val loc = locationOverride ?: locationProvider?.invoke() ?: EmergencyStateHolder.state.value.currentLocation
        val incident = currentIncident?.copy(
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            location = loc,
            isSilent = isSilent,
            notes = notes
        ) ?: EmergencyIncident(
            incidentId = "INC-${System.currentTimeMillis()}",
            startTimeEpochMs = System.currentTimeMillis(),
            lastUpdatedEpochMs = System.currentTimeMillis(),
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggeringSignals = listOfNotNull(triggeringSignal),
            location = loc,
            isSilent = isSilent,
            notes = notes
        )

        _activeIncident.value = incident
        EmergencyStateHolder.setDiscreetModeEnabled(isSilent)
        val targetMode = if (isSilent) ProtectionMode.DISCREET_MONITORING else ProtectionMode.ACTIVE_MONITORING
        EmergencyStateHolder.setProtectionActive(true, targetMode)
        EmergencyStateHolder.setEmergencyIncident(incident)

        // Dispatch alert once per incident
        if (!activeIncidentDispatched) {
            activeIncidentDispatched = true
            communicator?.let { comm ->
                val payload = AlertPayload(
                    incidentId = incident.incidentId,
                    timestampEpochMs = incident.startTimeEpochMs,
                    distressType = type,
                    confidence = triggeringSignal?.confidence ?: 1.0f,
                    location = loc,
                    emergencyMessage = if (isSilent) "DISCREET SOS: Silent distress alert triggered." else "EMERGENCY ALERT: $notes",
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
    }
}
