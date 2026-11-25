package com.guardiangrid.location

import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.modules.communication.sms.SmsSender
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * UI-observable state for standalone live location sharing (separate from SOS).
 */
data class LiveLocationShareState(
    val isSharing: Boolean = false,
    val isStarting: Boolean = false,
    val sessionId: String? = null,
    val shareUrl: String? = null,
    val durationMinutes: Int = 60,
    val expiresAtEpochMs: Long = 0L,
    val lastUpdatedEpochMs: Long = 0L,
    val recipientNames: List<String> = emptyList(),
    val smsSentCount: Int = 0,
    val smsFailedCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Manages user-initiated, time-bounded live location sharing.
 *
 * Completely isolated from SOS, scream detection, motion detection, emergency alerts,
 * and audio evidence recording. Requires a configured live backend endpoint so recipients
 * receive a genuine, auto-updating, auto-expiring live viewer link rather than a static map pin.
 */
class LiveLocationShareManager(
    private val locationManager: GuardianLocationManager,
    private val backendClient: SupabaseEmergencyBackendClient,
    private val smsSender: SmsSender,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateIntervalMs: Long = 10_000L
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var updateJob: Job? = null

    private val _state = MutableStateFlow(LiveLocationShareState())
    val state: StateFlow<LiveLocationShareState> = _state.asStateFlow()

    /**
     * Starts a standalone live location sharing session for [durationMinutes] and sends
     * the private live viewer link via SMS to each selected contact.
     */
    suspend fun startSharing(
        contacts: List<EmergencyContact>,
        durationMinutes: Int
    ): Result<LiveLocationShareState> {
        if (contacts.isEmpty()) {
            val err = "Select at least one trusted contact to share your live location."
            _state.value = _state.value.copy(errorMessage = err)
            return Result.failure(IllegalArgumentException(err))
        }

        if (!backendClient.isConfigured()) {
            val err = "Live location sharing requires Supabase backend configuration in Communication Settings."
            _state.value = _state.value.copy(errorMessage = err)
            return Result.failure(IllegalStateException(err))
        }

        if (!smsSender.isAvailable()) {
            val err = "SMS permission (SEND_SMS) and active cellular SIM are required to send the live link."
            _state.value = _state.value.copy(errorMessage = err)
            return Result.failure(IllegalStateException(err))
        }

        _state.value = _state.value.copy(isStarting = true, errorMessage = null)

        val currentLoc = locationManager.lastLocation.value
            ?: locationManager.getLastKnownLocation()
            ?: locationManager.acquireFreshLocation(2500L)

        if (currentLoc == null) {
            val err = "Current location unavailable. Ensure Location permission and GPS are enabled."
            _state.value = _state.value.copy(isStarting = false, errorMessage = err)
            return Result.failure(IllegalStateException(err))
        }

        val sessionId = "live-${UUID.randomUUID()}"
        val startResult = withContext(ioDispatcher) {
            backendClient.startLiveLocationSession(
                sessionId = sessionId,
                durationMinutes = durationMinutes,
                recipientPhones = contacts.map { it.phoneNumber },
                latitude = currentLoc.latitude,
                longitude = currentLoc.longitude,
                accuracyMeters = currentLoc.accuracyMeters
            )
        }

        if (startResult.isFailure) {
            val msg = startResult.exceptionOrNull()?.message
                ?: "Unable to create live location session on backend."
            _state.value = _state.value.copy(isStarting = false, errorMessage = msg)
            return Result.failure(startResult.exceptionOrNull() ?: IllegalStateException(msg))
        }

        val session = startResult.getOrThrow()
        val durationLabel = if (durationMinutes >= 60) {
            val hrs = durationMinutes / 60
            "$hrs ${if (hrs == 1) "hour" else "hours"}"
        } else {
            "$durationMinutes minutes"
        }

        val smsText = "GuardianGrid Live Location: I'm sharing my live location with you for $durationLabel. " +
            "View updates here (expires automatically): ${session.shareUrl}"

        var sentCount = 0
        var failCount = 0
        var lastSmsErr: String? = null
        withContext(ioDispatcher) {
            for (contact in contacts) {
                val res = smsSender.sendSms(contact.phoneNumber, smsText)
                if (res.isSuccess) sentCount++ else {
                    failCount++
                    lastSmsErr = res.errorMessage
                }
            }
        }

        if (sentCount == 0) {
            withContext(ioDispatcher) {
                backendClient.stopLiveLocationSession(sessionId)
            }
            val err = lastSmsErr ?: "Failed to send live location SMS to selected contacts."
            _state.value = _state.value.copy(isStarting = false, errorMessage = err)
            return Result.failure(IllegalStateException(err))
        }

        val now = System.currentTimeMillis()
        val activeState = LiveLocationShareState(
            isSharing = true,
            isStarting = false,
            sessionId = session.sessionId,
            shareUrl = session.shareUrl,
            durationMinutes = durationMinutes,
            expiresAtEpochMs = session.expiresAtEpochMs,
            lastUpdatedEpochMs = now,
            recipientNames = contacts.map { it.name },
            smsSentCount = sentCount,
            smsFailedCount = failCount,
            errorMessage = if (failCount > 0) "$failCount contact(s) could not be reached via SMS." else null
        )
        _state.value = activeState

        startUpdateLoop(session.sessionId, session.expiresAtEpochMs)
        return Result.success(activeState)
    }

    private fun startUpdateLoop(sessionId: String, expiresAtEpochMs: Long) {
        updateJob?.cancel()
        locationManager.startLocationUpdates(minTimeMs = updateIntervalMs, minDistanceM = 3f)

        updateJob = scope.launch {
            while (isActive && System.currentTimeMillis() < expiresAtEpochMs) {
                delay(updateIntervalMs)
                if (System.currentTimeMillis() >= expiresAtEpochMs) break

                val loc = locationManager.lastLocation.value ?: locationManager.getLastKnownLocation()
                if (loc != null) {
                    val res = backendClient.updateLiveLocationSession(
                        sessionId = sessionId,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracyMeters
                    )
                    if (res.isSuccess) {
                        _state.value = _state.value.copy(lastUpdatedEpochMs = System.currentTimeMillis())
                    }
                }
            }
            // Expired naturally — end sharing state cleanly
            if (_state.value.sessionId == sessionId) {
                _state.value = LiveLocationShareState()
            }
        }
    }

    /**
     * Stops live location sharing early and revokes the backend link immediately.
     */
    fun stopSharing() {
        val activeSessionId = _state.value.sessionId
        updateJob?.cancel()
        updateJob = null
        _state.value = LiveLocationShareState()

        if (!activeSessionId.isNullOrBlank()) {
            scope.launch {
                backendClient.stopLiveLocationSession(activeSessionId)
            }
        }
    }
}
