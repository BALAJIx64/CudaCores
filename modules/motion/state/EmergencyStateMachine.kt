package com.guardiangrid.modules.motion.state

import com.guardiangrid.core.interfaces.FusionResult
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * State record describing an emergency transition event.
 */
data class StateTransitionRecord(
    val fromState: MotionEmergencyState,
    val toState: MotionEmergencyState,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val reason: String,
    val triggeringSignal: DistressSignal? = null,
    val fusionResult: FusionResult? = null
)

/**
 * Internal Motion & Multimodal Verification State Machine.
 * Manages deterministic state transitions across:
 * NORMAL -> SUSPECTED_DISTRESS -> VERIFYING -> HIGH_CONCERN -> ALERTED -> CANCELLED -> RESOLVED.
 * Does not replace application-wide coordinator; maps to EmergencyLevel for integration.
 */
class EmergencyStateMachine(
    private val fusionEngine: MultimodalFusionEngineImpl = MultimodalFusionEngineImpl(),
    val verificationTimeoutMs: Long = 5000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(MotionEmergencyState.NORMAL)
    val state: StateFlow<MotionEmergencyState> = _state.asStateFlow()

    private val _transitionHistory = Collections.synchronizedList(mutableListOf<StateTransitionRecord>())
    val transitionHistory: List<StateTransitionRecord>
        get() = synchronized(_transitionHistory) { _transitionHistory.toList() }

    private val signalBuffer = mutableListOf<DistressSignal>()

    @Volatile var activeCountdownRemainingMs: Long = 0L
        private set

    /** Session ID to correlate active verification sessions and reject delayed timer callbacks. */
    @Volatile var activeVerificationSessionId: Long = 0L
        private set

    @Volatile private var lastCancelledTimestampMs: Long = 0L
    @Volatile private var lastResolvedTimestampMs: Long = 0L

    /**
     * Evaluate incoming distress signal against current state.
     */
    @Synchronized
    fun onSignal(signal: DistressSignal): MotionEmergencyState {
        val now = clock()

        // Reject invalid future timestamps (> 1000ms ahead of clock)
        if (signal.timestampEpochMs > now + 1000L) {
            return _state.value
        }

        // Immediate check for Manual SOS or Silent trigger: immediately initiates emergency
        // even from CANCELLED, RESOLVED, or VERIFYING states
        if (signal.type == DistressType.MANUAL_SOS || signal.type == DistressType.SILENT_ACTIVATION) {
            activeVerificationSessionId++
            activeCountdownRemainingMs = 0L
            signalBuffer.clear()
            transitionTo(
                to = MotionEmergencyState.ALERTED,
                reason = "Manual SOS initiated: ${signal.source}",
                triggeringSignal = signal
            )
            return _state.value
        }

        // A resolved incident cannot be reopened by stale sensory signals
        // Requires an explicit resetToNormal() before new sensory anomalies can be evaluated
        if (_state.value == MotionEmergencyState.RESOLVED) {
            return _state.value
        }

        // Signals that were generated before or at the time of the last cancellation or resolution are stale
        if (signal.timestampEpochMs <= lastCancelledTimestampMs || signal.timestampEpochMs <= lastResolvedTimestampMs) {
            return _state.value
        }

        signalBuffer.add(signal)

        // Prune buffer to temporal correlation window (3000ms), purging stale signals
        val cutoff = now - 3000L
        signalBuffer.removeAll { it.timestampEpochMs < cutoff }

        // Run multimodal fusion evaluation
        val fusionResult = fusionEngine.evaluateSignals(signalBuffer, 3000L)
        return onFusionResult(fusionResult, signal)
    }

    /**
     * Evaluate a multimodal fusion result and drive state escalation.
     */
    @Synchronized
    fun onFusionResult(result: FusionResult, originatingSignal: DistressSignal? = null): MotionEmergencyState {
        val currentState = _state.value

        // If already ALERTED or RESOLVED, do not downgrade or reopen via sensory fusion
        if (currentState == MotionEmergencyState.ALERTED || currentState == MotionEmergencyState.RESOLVED) {
            return currentState
        }

        when {
            // Immediate escalation triggered (e.g. correlated audio+motion or strong scream >= 0.85)
            result.isEscalationTriggered -> {
                activeVerificationSessionId++
                activeCountdownRemainingMs = 0L
                transitionTo(
                    to = MotionEmergencyState.ALERTED,
                    reason = result.explanation,
                    triggeringSignal = originatingSignal,
                    fusionResult = result
                )
            }

            // Moderate confidence (0.60 to 0.79) -> Enter VERIFYING state with countdown
            result.combinedConfidence in 0.60f..0.79f -> {
                if (currentState == MotionEmergencyState.NORMAL || currentState == MotionEmergencyState.SUSPECTED_DISTRESS) {
                    activeVerificationSessionId++
                    activeCountdownRemainingMs = verificationTimeoutMs
                    transitionTo(
                        to = MotionEmergencyState.VERIFYING,
                        reason = "Distress verification countdown started: ${result.explanation}",
                        triggeringSignal = originatingSignal,
                        fusionResult = result
                    )
                }
            }

            // Low / Isolated confidence (0.40 to 0.59) -> Mark SUSPECTED_DISTRESS if in NORMAL
            result.combinedConfidence in 0.40f..0.59f -> {
                if (currentState == MotionEmergencyState.NORMAL) {
                    transitionTo(
                        to = MotionEmergencyState.SUSPECTED_DISTRESS,
                        reason = "Suspicious movement or audio noted: ${result.explanation}",
                        triggeringSignal = originatingSignal,
                        fusionResult = result
                    )
                }
            }
        }

        return _state.value
    }

    /**
     * Immediate Manual SOS trigger from UI button or hardware discreet key.
     */
    @Synchronized
    fun triggerManualSos(triggerSource: String = "Manual_SOS_Button"): MotionEmergencyState {
        val sosSignal = DistressSignal(
            id = "manual-${clock()}",
            type = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            timestampEpochMs = clock(),
            source = triggerSource,
            metadata = mapOf("discreet" to "false")
        )
        return onSignal(sosSignal)
    }

    /**
     * Verification countdown tick handler associated with an optional session ID.
     * Transitions from VERIFYING -> HIGH_CONCERN when remaining time is low (<= 2000ms).
     */
    @Synchronized
    fun onCountdownTick(remainingMs: Long, sessionId: Long? = null): MotionEmergencyState {
        if (sessionId != null && sessionId != activeVerificationSessionId) {
            return _state.value
        }
        if (_state.value != MotionEmergencyState.VERIFYING && _state.value != MotionEmergencyState.HIGH_CONCERN) {
            return _state.value
        }

        activeCountdownRemainingMs = remainingMs
        if (_state.value == MotionEmergencyState.VERIFYING && remainingMs in 1..2000L) {
            transitionTo(
                to = MotionEmergencyState.HIGH_CONCERN,
                reason = "Verification countdown nearing expiration (${remainingMs}ms remaining)"
            )
        }
        return _state.value
    }

    /**
     * Verification countdown expired without user cancellation -> Escalate to ALERTED!
     * Protects against delayed callbacks from cancelled or previous verification sessions.
     */
    @Synchronized
    fun onVerificationTimeout(sessionId: Long? = null): MotionEmergencyState {
        if (sessionId != null && sessionId != activeVerificationSessionId) {
            return _state.value
        }
        val current = _state.value
        if (current == MotionEmergencyState.VERIFYING || current == MotionEmergencyState.HIGH_CONCERN) {
            activeCountdownRemainingMs = 0L
            transitionTo(
                to = MotionEmergencyState.ALERTED,
                reason = "Verification countdown expired without cancellation. Automatic emergency dispatch confirmed."
            )
        }
        return _state.value
    }

    /**
     * User or authenticated pin cancellation -> Transitions to CANCELLED.
     * Invalidates active verification session to prevent delayed timeout callbacks.
     */
    @Synchronized
    fun cancelEmergency(reason: String = "User cancelled emergency countdown"): MotionEmergencyState {
        val current = _state.value
        if (current == MotionEmergencyState.VERIFYING ||
            current == MotionEmergencyState.HIGH_CONCERN ||
            current == MotionEmergencyState.ALERTED ||
            current == MotionEmergencyState.SUSPECTED_DISTRESS
        ) {
            activeVerificationSessionId++
            activeCountdownRemainingMs = 0L
            lastCancelledTimestampMs = clock()
            signalBuffer.clear()
            transitionTo(
                to = MotionEmergencyState.CANCELLED,
                reason = reason
            )
        }
        return _state.value
    }

    /**
     * Emergency incident marked resolved and safe.
     * Invalidates active verification session and purges buffered evidence.
     */
    @Synchronized
    fun resolveEmergency(reason: String = "Emergency incident resolved by user/responders"): MotionEmergencyState {
        if (_state.value == MotionEmergencyState.ALERTED) {
            activeVerificationSessionId++
            activeCountdownRemainingMs = 0L
            lastResolvedTimestampMs = clock()
            signalBuffer.clear()
            transitionTo(
                to = MotionEmergencyState.RESOLVED,
                reason = reason
            )
        }
        return _state.value
    }

    /**
     * Reset state back to NORMAL baseline monitoring.
     */
    @Synchronized
    fun resetToNormal(): MotionEmergencyState {
        activeVerificationSessionId++
        activeCountdownRemainingMs = 0L
        signalBuffer.clear()
        transitionTo(
            to = MotionEmergencyState.NORMAL,
            reason = "Reset to normal baseline protection"
        )
        return _state.value
    }

    private fun transitionTo(
        to: MotionEmergencyState,
        reason: String,
        triggeringSignal: DistressSignal? = null,
        fusionResult: FusionResult? = null
    ) {
        val from = _state.value
        if (from == to) return

        val record = StateTransitionRecord(
            fromState = from,
            toState = to,
            timestampEpochMs = clock(),
            reason = reason,
            triggeringSignal = triggeringSignal,
            fusionResult = fusionResult
        )

        _transitionHistory.add(record)
        _state.value = to
    }
}
