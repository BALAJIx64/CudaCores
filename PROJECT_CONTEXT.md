# GuardianGrid: Project Context & Architectural Index

> **Operational Mandate for AI Agents & Developers:**
> 1. **Read This File First:** Always consult `PROJECT_CONTEXT.md` to orient yourself before executing any task.
> 2. **Targeted Reads Only:** Do NOT re-scan or re-read unrelated files across the repository for minor changes. Use the File & Interface Map below to open only the exact file(s) required.
> 3. **Maintenance Rule:** Update `PROJECT_CONTEXT.md` only when files are added/removed, architecture evolves, or core interface contracts are modified.
> 4. **Do Not Touch Core/App When Working on Modules:** Follow the 4-member ownership boundaries. Do not introduce competing Gradle builds or modify shared contracts without cross-team consensus.

---

## 1. Project Overview

- **Repository / Project Name:** GuardianGrid (Root folder: `CudaCores`, Package: `com.guardiangrid`)
- **Core Purpose:** Autonomous, native Android personal safety and emergency response system. Unifies multimodal distress detection (AI scream detection, configurable distress hotwords, 3-axis motion anomaly/fall recognition), temporal cross-modal sensor fusion, two-stage privacy-preserving audio evidence capture, and resilient multi-tier emergency dispatch (Telegram Bot API $\rightarrow$ Cellular SMS fallback $\rightarrow$ Persistent offline queue $\rightarrow$ Nearby-device Bluetooth LE store-and-forward relay).
- **Primary Tech Stack:**
  - **Platform:** Native Android (Min SDK: `26` / Android 8.0 Oreo, Target SDK: `35`, Compile SDK: `35` / Android 15)
  - **Language:** Kotlin 2.0.21 (`jvmTarget = "17"`, Java 17 toolchain compatibility)
  - **UI Toolkit:** Jetpack Compose (Material3, Compose BOM `2024.10.01`, Material Icons Extended)
  - **Concurrency & Reactivity:** Kotlin Coroutines & Asynchronous Flows (`1.9.0`, `StateFlow`, `SharedFlow`)
  - **Build System:** Gradle 8.14 (Kotlin DSL) with Version Catalog (`gradle/libs.versions.toml`)
  - **Edge Inference:** On-device ML compatible (TFLite / ONNX Runtime Mobile models under `models/audio/`)
  - **Emergency Communication:** Telegram Bot API (HTTP REST), Android `SmsManager`, BLE Advertising & Scanning
- **Primary Entry Points:**
  - Application Entry: `app/src/main/kotlin/com/guardiangrid/GuardianGridApp.kt` (`GuardianGridApp : Application`)
  - UI Activity Host: `app/src/main/kotlin/com/guardiangrid/MainActivity.kt` (`MainActivity : ComponentActivity`)
  - Main Screen Composable: `app/src/main/kotlin/com/guardiangrid/ui/DashboardScreen.kt` (`DashboardScreen(...)`)
  - State Orchestration Singleton: `core/state/EmergencyStateHolder.kt` (`EmergencyStateHolder`)
  - Baseline Coordinator: `core/state/EmergencyCoordinatorImpl.kt` (`EmergencyCoordinatorImpl`)

---

## 2. Directory & File Map

```
CudaCores/
├── build.gradle.kts                          # Root build script defining Android/Kotlin plugins (apply false)
├── settings.gradle.kts                       # Root project settings, plugin repos, includes single ':app' module
├── gradle.properties                         # JVM memory (-Xmx2048m), AndroidX flags, official Kotlin code style
├── CONTRIBUTING.md                           # Team ownership rules, branch boundaries, Git workflow, conventions
├── README.md                                 # Project overview, subsystem matrix, technical pillars, quickstart
├── PROJECT_CONTEXT.md                        # [THIS FILE] Persistent reference map & architectural index
│
├── gradle/
│   ├── libs.versions.toml                    # Central Version Catalog (AGP 8.7.2, Kotlin 2.0.21, Compose, Coroutines)
│   └── wrapper/
│       ├── gradle-wrapper.jar                # Gradle wrapper execution binary
│       └── gradle-wrapper.properties         # Gradle distribution configuration (v8.14.8)
│
├── app/                                      # Android Application Module (Owned by Member 4)
│   ├── build.gradle.kts                      # Module build script; configures SDK 35, Compose, and multi-dir sourceSets
│   ├── proguard-rules.pro                    # ProGuard / R8 optimization and obfuscation rules
│   └── src/main/
│       ├── AndroidManifest.xml               # App manifest, permissions (Audio, Sensors, Location, SMS, BLE, FG Services)
│       ├── kotlin/com/guardiangrid/
│       │   ├── GuardianGridApp.kt            # Application class; bootstrap logging and lifecycle initialization
│       │   ├── MainActivity.kt               # Main ComponentActivity; initializes coordinator and stubs, sets Compose UI
│       │   └── ui/
│       │       ├── DashboardScreen.kt        # Jetpack Compose dashboard: SOS button, status banner, simulation triggers
│       │       └── theme/
│       │           ├── Color.kt              # App color palette (EmergencyRed, SafeGreen, WarningAmber, DarkSurface)
│       │           └── Theme.kt              # Material3 dark theme configuration for GuardianGrid
│       └── res/
│           └── values/
│               ├── colors.xml                # Android theme fallback colors
│               ├── strings.xml               # String resources (app name: GuardianGrid)
│               └── themes.xml                # Base Android system themes
│
├── core/                                     # Shared Cross-Module Architectural Backbone (Owned by Member 4)
│   ├── models/
│   │   ├── DistressModels.kt                 # DistressType, AudioStage, DistressSignal, AudioSegment, MotionSample
│   │   ├── EmergencyModels.kt                # EmergencyLevel, LocationData, EmergencyContact, EmergencyIncident
│   │   └── CommunicationModels.kt            # DeliveryChannel, DeliveryStatus, AlertPayload, DeliveryReceipt, QueuedEmergencyEvent
│   ├── interfaces/
│   │   ├── AudioContracts.kt                 # Contracts: AudioDistressDetector, AudioEvidenceRecorder (KWS removed for rebuild)
│   │   ├── MotionContracts.kt                # Contracts: MotionAnomalyDetector, MultimodalFusionEngine, FusionResult
│   │   ├── CommunicationContracts.kt         # Contracts: EmergencyCommunicator, DeliveryQueueManager, BluetoothRelayService
│   │   └── CoreContracts.kt                  # Contract: EmergencyCoordinator
│   └── state/
│       ├── EmergencyState.kt                 # ProtectionMode enum & EmergencyState immutable reactive UI state class
│       ├── EmergencyStateHolder.kt           # Thread-safe StateFlow singleton for app state management
│       └── EmergencyCoordinatorImpl.kt       # Baseline implementation of EmergencyCoordinator orchestrator
│
├── modules/                                  # Independent Feature Implementation Subsystems
│   ├── audio/                                # [Member 1 - feature/audio] Acoustic Intelligence & Recording
│   │   ├── AudioModuleStub.kt                # Production facade implementing AudioDistressDetector, AudioEvidenceRecorder
│   │   ├── AudioCaptureManager.kt            # Android AudioRecord manager with permission handling and fallback mock loop
│   │   ├── CircularAudioBuffer.kt            # Thread-safe in-memory rolling ring buffer (3-5s, 16kHz 16-bit PCM mono)
│   │   ├── AudioPreprocessor.kt              # FFT, Spectral Centroid, Scream-Band ratio, ZCR, RMS/dBFS, 16-band Mel filterbank
│   │   ├── ScreamDistressClassifier.kt       # Dual-engine sound classifier (YAMNet compatible + built-in Edge DSP screamer engine)
│   │   ├── TwoStageAudioEvidenceRecorder.kt  # Two-stage recording: Stage 1 RAM dump to WAV, Stage 2 continuous/segmented recording
│   │   ├── WavAudioEncoder.kt                # Pure Kotlin standard 44-byte RIFF WAVE header and PCM audio encoder
│   │   ├── AudioModuleTestHarness.kt         # Self-contained 6-part automated unit and sample test suite
│   │   └── README.md                         # Subsystem guide for scream detection and 2-stage audio buffer
│   ├── motion/                               # [Member 2 - feature/motion] Motion Anomaly & Multimodal Fusion
│   │   ├── MotionModuleStub.kt               # Baseline stub implementing MotionAnomalyDetector and MultimodalFusionEngine
│   │   └── README.md                         # Subsystem guide for accelerometer/gyroscope filters and temporal correlation
│   └── communication/                        # [Member 3 - feature/communication] Multi-Tier Alert Dispatch & Offline Queue
│       ├── EmergencyCommunicatorStub.kt      # Baseline stub implementing EmergencyCommunicator, DeliveryQueueManager, BluetoothRelayService
│       └── README.md                         # Subsystem guide for Telegram HTTP, SMS fallback, retry queues, and BLE relay
│
├── models/
│   └── audio/                                # [Member 1] Pretrained TFLite/ONNX acoustic models (YAMNet) & documentation
│
├── tests/                                    # Unit & Architectural Verification Tests
│   └── CoreArchitectureTest.kt               # JUnit tests validating StateFlow lifecycle, contracts, and stub integrations
│
└── docs/                                     # Comprehensive Engineering Documentation
    ├── ANTIGRAVITY_MASTER_PROMPT.md          # AI agent constraints, team ownership matrix, and implementation invariants
    ├── ARCHITECTURE.md                       # Deep architectural documentation, Mermaid layer & state diagrams, 2-stage audio
    ├── DEMO_TEST_CASES.md                    # 6 end-to-end evaluation scenarios (SOS, Scream, Fusion, SMS fallback, Queue, BLE)
    ├── DEVELOPMENT_SETUP.md                  # Android Studio, JDK 17, SDK paths, local.properties, and Telegram Bot setup
    ├── INTEGRATION_PLAN.md                   # 5-phase integration roadmap from baseline contracts to field release
    ├── MODULE_CONTRACTS.md                   # Complete Kotlin interface specifications and thread-safety requirements
    └── PROJECT_OVERVIEW.md                   # Executive summary, problem analysis, technical contributions, and impact
```

---

## 3. Architecture & Data Flow

### 3.1 Unified SourceSet Compilation (Crucial Structural Pattern)
GuardianGrid does **not** use multiple Gradle subprojects. Instead, `app/build.gradle.kts` configures custom `sourceSets` mapping external root folders into `:app`:
```kotlin
sourceSets {
    getByName("main") {
        java.srcDirs(
            "src/main/kotlin",
            "../core/models",
            "../core/interfaces",
            "../core/state",
            "../modules/audio",
            "../modules/motion",
            "../modules/communication"
        )
    }
    getByName("test") {
        java.srcDirs("src/test/kotlin", "../tests")
    }
}
```
*Rule:* Do NOT add `include(":modules:audio")` to `settings.gradle.kts` or create nested Android projects. All code compiles under `:app`.

### 3.2 End-to-End Multimodal Data Flow

```
                      [ Physical Sensors & Inputs ]
                     /              |              \
           Microphone          3-Axis IMU         Discreet / UI SOS
          (16kHz Mono)      (Accel / Gyro)         (Hardware / Touch)
               │                    │                      │
               ▼                    ▼                      │
       [ Member 1: Audio ]  [ Member 2: Motion ]          │
       AudioDistressDetector MotionAnomalyDetector         │
       KeywordSpotter               │                      │
               \                    /                      │
          DistressSignal       DistressSignal              │
                 \                /                        │
                  ▼              ▼                         │
               [ MultimodalFusionEngine ]                  │
              (Sliding Window Correlation)                 │
                           │                               │
                      FusionResult                         │
               (Combined Conf >= 0.85)                     │
                           \                               /
                            ▼                             ▼
                      [ Member 4: EmergencyCoordinator ]
                                   │
                ┌──────────────────┼──────────────────┐
                ▼                  ▼                  ▼
      [ State Machine ]   [ 2-Stage Audio ]   [ Dispatch Alert ]
      EmergencyStateHolder AudioEvidenceRecorder EmergencyCommunicator
      StateFlow -> UI     Stage 1: RAM dump   (Member 3)
                          Stage 2: Continuous         │
                                                      ├─► Tier 1: Telegram Bot API
                                                      ├─► Tier 2: Cellular SMS
                                                      ├─► Tier 3: Offline Queue
                                                      └─► Tier 4: BLE Relay
```

### 3.3 State Machine Progression
`EmergencyLevel` transitions:
1. `IDLE` $\rightarrow$ System standby.
2. `MONITORING` $\rightarrow$ Background protection running (`AudioRecord` + sensor listeners active).
3. `SUSPICIOUS` $\rightarrow$ Single-modality low/medium confidence signal (triggers Stage 1 audio buffer snapshot).
4. `VERIFYING` $\rightarrow$ Multimodal trigger or single high-confidence scream initiates user cancellation window.
5. `CONFIRMED_EMERGENCY` $\rightarrow$ Fusion confidence $\ge 0.85$ or manual SOS trigger; initiates Stage 2 evidence recording and multi-channel dispatch.
6. `RESOLVED` / `CANCELLED` $\rightarrow$ User deactivates emergency or authenticates PIN cancellation.

### 3.4 Two-Stage Audio Intelligence Protocol
- **Stage 1 (Rolling Buffer):** Continuous in-memory circular buffer (3–5 seconds, 16 kHz 16-bit PCM mono). Overwritten in RAM. Discarded continuously. If a suspicious trigger fires, dumped to `stage1_preview.wav`.
- **Stage 2 (Forensic Evidence Stream):** Activated only when `EmergencyLevel` reaches `CONFIRMED_EMERGENCY`. Records high-fidelity segmented evidence (`evidence_segment_N.aac`) preserved with timestamp and SHA-256 integrity hash for transmission and local audit trail.

### 3.5 Resilient Multi-Tier Communication Strategy
When `EmergencyCommunicator.dispatchAlert(payload, channels)` is called:
1. **Tier 1 (Telegram Bot API):** Dispatches structured emergency markdown with GPS coordinates, Google Maps link (`https://maps.google.com/?q=LAT,LON`), and forensic audio links.
2. **Tier 2 (Cellular SMS Fallback):** If HTTP fails (no internet/timeout), falls back to Android `SmsManager.sendTextMessage(...)` with compact emergency text.
3. **Tier 3 (Persistent Offline Queue):** Failed or unacknowledged alerts are stored in `DeliveryQueueManager` (SQLite / Room) with exponential backoff retry cycles.
4. **Tier 4 (Bluetooth Store-and-Forward Relay):** In complete network blackouts, broadcasts encrypted emergency beacons over BLE (`BluetoothRelayService`) for peer nodes to forward once connectivity is found.

---

## 4. Key Symbols & Interfaces

### 4.1 Shared Data Models (`core/models/`)
| Symbol | Kind | File Path | Key Properties / Responsibility |
| :--- | :--- | :--- | :--- |
| `DistressType` | `enum` | `core/models/DistressModels.kt` | `SCREAM`, `KEYWORD`, `MOTION_ANOMALY`, `FALL_IMPACT`, `MANUAL_SOS`, `SILENT_ACTIVATION` |
| `AudioStage` | `enum` | `core/models/DistressModels.kt` | `STAGE_1_PREVIEW` (3-5s rolling buffer), `STAGE_2_EVIDENCE` (continuous/segmented) |
| `DistressSignal` | `data class` | `core/models/DistressModels.kt` | `id`, `type`, `confidence` (0.0–1.0), `timestampEpochMs`, `source`, `rawScore`, `metadata` |
| `AudioSegment` | `data class` | `core/models/DistressModels.kt` | `id`, `filePath`, `durationMs`, `sampleRate`, `stage`, `timestampEpochMs`, `fileSizeBytes` |
| `MotionSample` | `data class` | `core/models/DistressModels.kt` | `timestampEpochMs`, `ax, ay, az`, `gx, gy, gz`, `magnitude` ($\sqrt{a_x^2 + a_y^2 + a_z^2}$) |
| `EmergencyLevel` | `enum` | `core/models/EmergencyModels.kt` | `IDLE`, `MONITORING`, `SUSPICIOUS`, `VERIFYING`, `CONFIRMED_EMERGENCY`, `RESOLVED`, `CANCELLED` |
| `LocationData` | `data class` | `core/models/EmergencyModels.kt` | `latitude`, `longitude`, `accuracyMeters`, `altitudeMeters`, `speedMps`, `timestampEpochMs`, `provider` |
| `EmergencyContact` | `data class` | `core/models/EmergencyModels.kt` | `id`, `name`, `phoneNumber`, `telegramChatId`, `isPrimary` |
| `EmergencyIncident` | `data class` | `core/models/EmergencyModels.kt` | `incidentId`, `startTimeEpochMs`, `level`, `triggeringSignals`, `location`, `audioSegments`, `isSilent` |
| `DeliveryChannel` | `enum` | `core/models/CommunicationModels.kt` | `TELEGRAM`, `SMS`, `INTERNET_API`, `BLUETOOTH_RELAY` |
| `DeliveryStatus` | `enum` | `core/models/CommunicationModels.kt` | `PENDING`, `IN_FLIGHT`, `DELIVERED`, `FAILED_RETRYING`, `ABANDONED` |
| `AlertPayload` | `data class` | `core/models/CommunicationModels.kt` | Outbound alert: `incidentId`, `distressType`, `confidence`, `location`, `emergencyMessage`, `audioSegmentPath` |
| `DeliveryReceipt` | `data class` | `core/models/CommunicationModels.kt` | Delivery receipt: `channel`, `status`, `messageId`, `recipient`, `latencyMs`, `errorMessage` |
| `QueuedEmergencyEvent`| `data class`| `core/models/CommunicationModels.kt` | Queue item: `queueId`, `payload`, `targetChannel`, `status`, `retryCount`, `nextRetryEpochMs` |

### 4.2 Subsystem Contracts (`core/interfaces/`)
| Interface / Type | Methods & Signatures | File Path | Owner |
| :--- | :--- | :--- | :--- |
| `AudioDistressDetector` | `startMonitoring(): Flow<DistressSignal>`, `stopMonitoring()`, `isMonitoring(): Boolean`, `setSensitivity(Float)` | `core/interfaces/AudioContracts.kt` | Member 1 |
| `AudioEvidenceRecorder` | `suspend captureStageOneBuffer(Long): Result<AudioSegment>`, `suspend startStageTwoRecording(File): Flow<AudioSegment>`, `stopRecording()` | `core/interfaces/AudioContracts.kt` | Member 1 |
| `MotionAnomalyDetector` | `startMonitoring(): Flow<DistressSignal>`, `stopMonitoring()`, `isMonitoring(): Boolean`, `calibrateBaseline()`, `observeMotionSamples(): Flow<MotionSample>` | `core/interfaces/MotionContracts.kt` | Member 2 |
| `MultimodalFusionEngine`| `evaluateSignals(List<DistressSignal>, Long): FusionResult` | `core/interfaces/MotionContracts.kt` | Member 2 |
| `FusionResult` | `combinedConfidence: Float`, `isEscalationTriggered: Boolean`, `contributingSignals: List<DistressSignal>`, `explanation: String` | `core/interfaces/MotionContracts.kt` | Member 2 |
| `EmergencyCommunicator` | `suspend dispatchAlert(AlertPayload, List<DeliveryChannel>): List<DeliveryReceipt>`, `suspend checkChannelAvailability(DeliveryChannel): Boolean` | `core/interfaces/CommunicationContracts.kt` | Member 3 |
| `DeliveryQueueManager` | `suspend enqueue(AlertPayload, DeliveryChannel): String`, `observeQueue(): Flow<List<QueuedEmergencyEvent>>`, `suspend retryPendingEvents(): List<DeliveryReceipt>` | `core/interfaces/CommunicationContracts.kt` | Member 3 |
| `BluetoothRelayService` | `startBroadcastingRelay(AlertPayload)`, `startRelayScanner(): Flow<AlertPayload>`, `stopRelay()`, `isRelayActive(): Boolean` | `core/interfaces/CommunicationContracts.kt` | Member 3 |
| `EmergencyCoordinator` | `val activeIncident: StateFlow<EmergencyIncident?>`, `triggerDiscreetSos(String)`, `cancelEmergency(String)`, `updateLocation(LocationData)`, `startProtection()`, `stopProtection()` | `core/interfaces/CoreContracts.kt` | Member 4 |

### 4.3 State Management & UI Symbols
| Symbol | Kind | File Path | Responsibility |
| :--- | :--- | :--- | :--- |
| `ProtectionMode` | `enum` | `core/state/EmergencyState.kt` | `STANDBY`, `ACTIVE_MONITORING`, `DISCREET_MONITORING` |
| `EmergencyState` | `data class` | `core/state/EmergencyState.kt` | Immutable state holding mode, level, active incident, signals, location, contacts, receipts |
| `EmergencyStateHolder` | `object` | `core/state/EmergencyStateHolder.kt` | Thread-safe singleton with `_state = MutableStateFlow(...)` and public `val state: StateFlow<EmergencyState>` |
| `EmergencyCoordinatorImpl` | `class` | `core/state/EmergencyCoordinatorImpl.kt` | Implements `EmergencyCoordinator`, mutates `EmergencyStateHolder` |
| `DashboardScreen` | `@Composable` | `app/src/main/kotlin/com/guardiangrid/ui/DashboardScreen.kt` | Main UI hosting SOS button, mode switches, module indicators, simulation buttons, audit trail |
| `StatusBanner` | `@Composable` | `app/src/main/kotlin/com/guardiangrid/ui/DashboardScreen.kt` | Color-coded status card reflecting `EmergencyLevel` |
| `SubsystemIndicator` | `@Composable` | `app/src/main/kotlin/com/guardiangrid/ui/DashboardScreen.kt` | Small cards for Audio, Motion, and Comm subsystem status |
| `GuardianGridTheme` | `@Composable` | `app/src/main/kotlin/com/guardiangrid/ui/theme/Theme.kt` | Custom dark theme wrapper |

### 4.4 Subsystem Implementations & Stubs
| Symbol | Implemented Interfaces | File Path | Owner |
| :--- | :--- | :--- | :--- |
| `AudioModuleStub` | `AudioDistressDetector`, `AudioEvidenceRecorder` | `modules/audio/AudioModuleStub.kt` | Member 1 |
| `AudioCaptureManager` | Hardware AudioRecord & simulation loop | `modules/audio/AudioCaptureManager.kt` | Member 1 |
| `CircularAudioBuffer` | 3–5s in-memory ring buffer (RAM only) | `modules/audio/CircularAudioBuffer.kt` | Member 1 |
| `AudioPreprocessor` | DSP & Mel feature extraction | `modules/audio/AudioPreprocessor.kt` | Member 1 |
| `ScreamDistressClassifier` | `AudioDistressDetector` | `modules/audio/ScreamDistressClassifier.kt` | Member 1 |
| `TwoStageAudioEvidenceRecorder` | `AudioEvidenceRecorder` | `modules/audio/TwoStageAudioEvidenceRecorder.kt` | Member 1 |
| `WavAudioEncoder` | Canonical 44-byte RIFF WAV encoder | `modules/audio/WavAudioEncoder.kt` | Member 1 |
| `AudioModuleTestHarness` | Complete automated 6-part test suite | `modules/audio/AudioModuleTestHarness.kt` | Member 1 |
| `MotionModuleStub` | `MotionAnomalyDetector`, `MultimodalFusionEngine` | `modules/motion/MotionModuleStub.kt` | Member 2 |
| `EmergencyCommunicatorStub` | `EmergencyCommunicator`, `DeliveryQueueManager`, `BluetoothRelayService` | `modules/communication/EmergencyCommunicatorStub.kt` | Member 3 |

---

## 5. Team Ownership Matrix & Git Workflow

| Member | Subsystem | Target Branch | Directory Scope | Owned Contracts |
| :--- | :--- | :--- | :--- | :--- |
| **Member 1** | Audio Intelligence | `feature/audio` | `modules/audio/`<br>`models/audio/` | `AudioDistressDetector`, `AudioEvidenceRecorder` |
| **Member 2** | Motion Anomaly & Fusion | `feature/motion` | `modules/motion/` | `MotionAnomalyDetector`, `MultimodalFusionEngine` |
| **Member 3** | Resilient Communication | `feature/communication` | `modules/communication/` | `EmergencyCommunicator`, `DeliveryQueueManager`, `BluetoothRelayService` |
| **Member 4** | Core Engine, UI & Integration | `feature/core-ui` | `app/`<br>`core/models/`<br>`core/interfaces/`<br>`core/state/` | `EmergencyCoordinator`, `EmergencyStateHolder`, `DashboardScreen` |

### Invariant Ownership Rules:
1. **Member 4 Exclusivity:** Member 4 exclusively owns `app/`, `core/`, `build.gradle.kts`, and `settings.gradle.kts`. Members 1, 2, and 3 must never touch shared application files or change interface signatures in `core/interfaces/` independently.
2. **Branching Model:** Never push or commit directly to `main`. All work must occur on `feature/<subsystem>` branches.
3. **Conventional Commits:** Format commits as `feat(audio): ...`, `feat(motion): ...`, `feat(comm): ...`, `feat(core): ...`, `test: ...`, or `fix: ...`.

---

## 6. Conventions & Constraints

### 6.1 Coding & Formatting Rules
- **Language Level:** Idiomatic Kotlin with official code style (`kotlin.code.style=official` in `gradle.properties`).
- **Immutability:** Always prefer `val` over `var`, immutable collections (`List`, `Map`), and `data class.copy(...)` updates.
- **Coroutines & Flows:**
  - Monitoring functions (`startMonitoring`, `startListening`) must return non-blocking cold or hot `Flow<T>`.
  - Never run blocking I/O or network on `Dispatchers.Main`; use `Dispatchers.IO`.
  - Cancellation must promptly unregister Android listeners (`SensorEventListener`, `AudioRecord`).

### 6.2 Error Handling & Fail-Safe Dispatch
- **Zero Crashes on Alert Failure:** If Telegram HTTP or network dispatch fails (e.g. `IOException`, HTTP 5xx, timeouts), the `EmergencyCommunicator` must catch the exception, generate a `DeliveryReceipt(status = FAILED_RETRYING)`, and seamlessly trigger the next fallback tier (SMS) without crashing or hanging.
- **Offline Safety:** All alerts must be enqueued locally to guarantee persistence across process death or battery exhaustion.

### 6.3 Privacy & Resource Constraints
- **Zero Disk Ambient Audio:** Normal ambient audio must remain exclusively in RAM circular buffers. Discard audio continuously. Never write raw audio to disk or transmit audio off-device unless a verified distress threshold is exceeded.
- **Hardware Sensor Lifecycle:** Always unregister `SensorEventListener` and call `AudioRecord.stop()` / `release()` when protection stops.
- **WakeLock Discipline:** Acquire WakeLocks only during active emergency escalation or critical background processing; release immediately upon cancellation or resolution.

---

## 7. Build, Test & Environment Instructions

### Prerequisites
- **JDK:** JDK 17 (Recommended: Eclipse Temurin 17 or OpenJDK 17). Android Studio's bundled JBR can be used (`C:\Program Files\Android\Android Studio\jbr`).
- **Android SDK:** Platform 35 (or 34) located at `sdk.dir` in `local.properties` (e.g. `C:\Users\<User>\AppData\Local\Android\Sdk`).

### Verification Commands
```powershell
# Set JDK 17 in terminal session if not set globally
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Run all unit and contract verification tests
.\gradlew.bat testDebugUnitTest

# Assemble Debug APK (outputs to app/build/outputs/apk/debug/app-debug.apk)
.\gradlew.bat assembleDebug
```
