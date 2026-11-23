# GuardianGrid: Module Contracts & Interface Specifications

This document defines the strict, binding Kotlin interface contracts, shared data models, and behavioral specifications across all four modules.

---

## 1. Shared Data Models (`core/models`)

### 1.1 `DistressType.kt`
```kotlin
package com.guardiangrid.core.models

enum class DistressType {
    SCREAM,
    KEYWORD,
    MOTION_ANOMALY,
    FALL_IMPACT,
    MANUAL_SOS,
    SILENT_ACTIVATION
}
```

### 1.2 `DistressSignal.kt`
```kotlin
package com.guardiangrid.core.models

data class DistressSignal(
    val id: String,
    val type: DistressType,
    val confidence: Float,                   // 0.0f .. 1.0f
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val source: String,                      // e.g. "TFLiteScreamClassifier"
    val rawScore: Float = confidence,
    val metadata: Map<String, String> = emptyMap()
)
```

### 1.3 `EmergencyLevel.kt`
```kotlin
package com.guardiangrid.core.models

enum class EmergencyLevel {
    IDLE,
    MONITORING,
    SUSPICIOUS,
    VERIFYING,
    CONFIRMED_EMERGENCY,
    RESOLVED,
    CANCELLED
}
```

### 1.4 `LocationData.kt`
```kotlin
package com.guardiangrid.core.models

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val speedMps: Float = 0f,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val provider: String = "fused"
)
```

### 1.5 `AlertPayload.kt` & Delivery Entities
```kotlin
package com.guardiangrid.core.models

enum class DeliveryChannel {
    TELEGRAM,
    SMS,
    INTERNET_API,
    BLUETOOTH_RELAY
}

enum class DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED_RETRYING,
    ABANDONED
}

data class AlertPayload(
    val incidentId: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val distressType: DistressType,
    val confidence: Float,
    val location: LocationData?,
    val emergencyMessage: String,
    val audioSegmentPath: String? = null,
    val recipientContacts: List<EmergencyContact> = emptyList()
)

data class DeliveryReceipt(
    val channel: DeliveryChannel,
    val status: DeliveryStatus,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val recipient: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)
```

---

## 2. Subsystem Interface Contracts (`core/interfaces`)

### 2.1 Audio Module Contract (Owned by Member 1)
Located at `core/interfaces/AudioContracts.kt`:

```kotlin
package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.DistressSignal
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AudioDistressDetector {
    fun startMonitoring(): Flow<DistressSignal>
    fun stopMonitoring()
    fun isMonitoring(): Boolean
    fun setSensitivity(sensitivity: Float)
}

interface KeywordSpotter {
    fun startListening(targetKeywords: List<String>): Flow<DistressSignal>
    fun stopListening()
    fun isListening(): Boolean
}

interface AudioEvidenceRecorder {
    suspend fun captureStageOneBuffer(durationMs: Long = 5000): Result<AudioSegment>
    suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment>
    fun stopRecording()
}
```

### 2.2 Motion Module Contract (Owned by Member 2)
Located at `core/interfaces/MotionContracts.kt`:

```kotlin
package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.MotionSample
import kotlinx.coroutines.flow.Flow

data class FusionResult(
    val combinedConfidence: Float,
    val isEscalationTriggered: Boolean,
    val contributingSignals: List<DistressSignal>,
    val explanation: String
)

interface MotionAnomalyDetector {
    fun startMonitoring(): Flow<DistressSignal>
    fun stopMonitoring()
    fun isMonitoring(): Boolean
    fun calibrateBaseline()
    fun observeMotionSamples(): Flow<MotionSample>
}

interface MultimodalFusionEngine {
    fun evaluateSignals(
        signals: List<DistressSignal>,
        timeWindowMs: Long = 3000L
    ): FusionResult
}
```

### 2.3 Communication Module Contract (Owned by Member 3)
Located at `core/interfaces/CommunicationContracts.kt`:

```kotlin
package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.QueuedEmergencyEvent
import kotlinx.coroutines.flow.Flow

interface EmergencyCommunicator {
    suspend fun dispatchAlert(
        payload: AlertPayload,
        channels: List<DeliveryChannel> = listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS)
    ): List<DeliveryReceipt>

    suspend fun checkChannelAvailability(channel: DeliveryChannel): Boolean
}

interface DeliveryQueueManager {
    suspend fun enqueue(payload: AlertPayload, channel: DeliveryChannel): String
    fun observeQueue(): Flow<List<QueuedEmergencyEvent>>
    suspend fun retryPendingEvents(): List<DeliveryReceipt>
}

interface BluetoothRelayService {
    fun startBroadcastingRelay(payload: AlertPayload)
    fun startRelayScanner(): Flow<AlertPayload>
    fun stopRelay()
    fun isRelayActive(): Boolean
}
```

### 2.4 Core Coordinator Contract (Owned by Member 4)
Located at `core/interfaces/CoreContracts.kt`:

```kotlin
package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.LocationData
import kotlinx.coroutines.flow.StateFlow

interface EmergencyCoordinator {
    val activeIncident: StateFlow<EmergencyIncident?>
    fun triggerDiscreetSos(triggerSource: String)
    fun cancelEmergency(reason: String)
    fun updateLocation(location: LocationData)
    fun startProtection()
    fun stopProtection()
}
```

---

## 3. Concurrency, Threading & Error Handling Guarantees

1. **Non-Blocking Flows:** All sensor monitoring (`startMonitoring`) must return cold or hot coroutine Flows and must not block the calling thread.
2. **Cancellation Transparency:** Cancellation of coroutine scopes must promptly unregister Android listeners and free hardware buffers.
3. **Fail-Safe Dispatch:** If an outbound channel (e.g. Telegram) throws an `IOException` or HTTP 5xx, the `EmergencyCommunicator` must catch the exception, produce a `DeliveryReceipt(status = FAILED_RETRYING)`, and transition to the next channel in the fallback list without crashing.
