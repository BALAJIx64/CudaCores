# GuardianGrid
### Multimodal Distress Detection and Resilient Emergency Communication System

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![UI Framework](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Modular%20%7C%20Sensor%20Fusion-orange.svg)](docs/ARCHITECTURE.md)
[![Status](https://img.shields.io/badge/Status-Foundation%20Initialized-brightgreen.svg)](#)

---

## 1. Executive Summary

Conventional personal safety applications depend heavily on manual SOS triggers (e.g. unlocking a screen, opening an app, pressing a button). In real-world violence, physical restraint, sudden assaults, or panic situations, victims frequently cannot access or operate their smartphones. Furthermore, emergency alerts often fail in connectivity blackspots (basements, remote transit corridors), and attackers actively attempt to disable devices or tamper with evidence.

**GuardianGrid** solves these fundamental gaps through an autonomous, native Android safety architecture built upon two core pillars:
1. **Multimodal Distress Detection:** Autonomous detection fusing AI-based scream/distress acoustic classification, configurable hotword spotting, and smartphone motion anomaly recognition (falls, impacts, struggles).
2. **Resilient Emergency Communication:** Tiered multi-channel failover (Telegram Bot API $\rightarrow$ SMS Fallback $\rightarrow$ Nearby-Device Bluetooth Store-and-Forward Relay) backed by offline event queuing, delivery acknowledgment tracking, and tamper-resistant evidence preservation.

---

## 2. Core Pillars & Capabilities

```
                      ┌────────────────────────────────────────┐
                      │          GuardianGrid Engine           │
                      └──────────────────┬─────────────────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
   [ Pillar 1: Distress Detection ]              [ Pillar 2: Resilient Communication ]
   • AI Scream & Acoustic Classifier             • Telegram Bot API Outbound Dispatch
   • Configurable Emergency Hotwords             • Cellular SMS Fallback Channel
   • 3-Axis Accelerometer / Gyroscope Anomaly    • Persistent Offline Queue & Retries
   • Multimodal Fusion (Temporal Correlation)    • Bluetooth Store-and-Forward Relay
   • Silent & Discreet Manual SOS                • Forensic Audio & Location Audit Trail
```

- **Acoustic Intelligence:** Real-time on-device scream detection and configurable hotword spotting ("Help", "Bachao").
- **Motion Anomaly Detection:** Continuous monitoring of sudden deceleration (falls), violent jerking (struggles), and rapid device snatching.
- **Multimodal Verification Engine:** Temporal cross-correlation between audio distress and abnormal motion to minimize false alarms before triggering emergency escalation.
- **Two-Stage Audio Intelligence:**
  - *Stage 1:* Rolling 3–5 second circular audio buffer captured upon suspicious events.
  - *Stage 2:* High-fidelity segmented evidence recording when confirmed emergency escalation occurs.
- **Resilient Multi-Tier Communication:** Internet alerts dispatched via Telegram Bot API with instant fallback to cellular SMS when internet connectivity fails.
- **Local Persistence & Delivery Tracking:** Guaranteed event delivery using local persistence queues with automated retries and delivery status receipts.
- **Bluetooth Relay Foundation:** Store-and-forward relay mechanism over BLE for extreme offline environments.

---

## 3. Four-Member Team Ownership Matrix

The project foundation is engineered specifically for four team members to develop their respective subsystems in parallel without git merge conflicts:

| Member | Subsystem / Module | Ownership Directory | Primary Interfaces | Target Git Branch |
| :--- | :--- | :--- | :--- | :--- |
| **Member 1** | **Audio Intelligence** | `modules/audio/`<br>`models/audio/` | `AudioDistressDetector`<br>`KeywordSpotter`<br>`AudioEvidenceRecorder` | `feature/audio` |
| **Member 2** | **Motion Anomaly & Fusion** | `modules/motion/` | `MotionAnomalyDetector`<br>`MultimodalFusionEngine` | `feature/motion` |
| **Member 3** | **Resilient Communication** | `modules/communication/` | `EmergencyCommunicator`<br>`DeliveryQueueManager`<br>`BluetoothRelayService` | `feature/communication` |
| **Member 4** | **Core, UI & System Integration** | `app/`<br>`core/models/`<br>`core/interfaces/`<br>`core/state/` | `EmergencyCoordinator`<br>`EmergencyStateHolder`<br>`DashboardScreen` | `feature/core-ui` |

> [!IMPORTANT]
> **Ownership Boundary Rule:** Member 4 owns the application entry point, shared core contracts/models, UI and integration coordination. Other members must not independently modify shared application files (`app/`, `core/`, `build.gradle.kts`, `settings.gradle.kts`). Changes to shared interfaces must be coordinated via Member 4.

---

## 4. Repository Structure

```
CudaCores/
├── app/                                # Android Application Module
│   ├── src/main/
│   │   ├── AndroidManifest.xml        # Permissions & application manifest
│   │   ├── kotlin/com/guardiangrid/
│   │   │   ├── GuardianGridApp.kt     # Application entrypoint
│   │   │   ├── MainActivity.kt        # Compose activity
│   │   │   └── ui/                    # Jetpack Compose UI (Dashboard, Theme)
│   │   └── res/                       # Values, colors, themes, strings
│   └── build.gradle.kts               # App-level build configuration
│
├── core/                               # Shared Cross-Module Backbone
│   ├── models/                        # Immutable data models & entities
│   │   ├── DistressModels.kt          # DistressSignal, AudioSegment, MotionSample
│   │   ├── EmergencyModels.kt         # EmergencyIncident, LocationData, Contacts
│   │   └── CommunicationModels.kt     # AlertPayload, DeliveryReceipt, Channels
│   ├── interfaces/                    # Strict Kotlin contracts for all 4 members
│   │   ├── AudioContracts.kt          # AudioDistressDetector, KeywordSpotter
│   │   ├── MotionContracts.kt         # MotionAnomalyDetector, FusionEngine
│   │   ├── CommunicationContracts.kt  # EmergencyCommunicator, QueueManager
│   │   └── CoreContracts.kt           # EmergencyCoordinator
│   └── state/                         # Reactive application state
│       ├── EmergencyState.kt          # Global state data classes
│       ├── EmergencyStateHolder.kt    # Thread-safe StateFlow singleton
│       └── EmergencyCoordinatorImpl.kt# Baseline orchestrator
│
├── modules/                            # Modular Implementation Folders
│   ├── audio/                         # [Member 1] Audio detection & recording
│   ├── motion/                        # [Member 2] Motion sensors & sensor fusion
│   └── communication/                 # [Member 3] Multi-channel alert & relay
│
├── models/
│   └── audio/                         # TFLite/ONNX acoustic models (.tflite)
│
├── docs/                               # Engineering & Contract Documentation
│   ├── PROJECT_OVERVIEW.md            # Problem analysis, solution & contributions
│   ├── ARCHITECTURE.md                # System layers, sequence & dataflow diagrams
│   ├── MODULE_CONTRACTS.md            # Kotlin interface signatures & schemas
│   ├── DEVELOPMENT_SETUP.md           # SDK, JDK, tools, and build guides
│   ├── INTEGRATION_PLAN.md            # Phased roadmap & verification steps
│   ├── DEMO_TEST_CASES.md             # End-to-end evaluation & test scenarios
│   └── ANTIGRAVITY_MASTER_PROMPT.md   # Guidelines for AI development agents
│
├── tests/                              # Unit & Architectural Verification Tests
│   └── CoreArchitectureTest.kt        # Cross-module contract validation suite
│
├── gradle/
│   ├── libs.versions.toml             # Central Gradle Version Catalog
│   └── wrapper/                       # Gradle wrapper configuration
├── build.gradle.kts                   # Root build script
├── settings.gradle.kts                # Project structure settings
├── CONTRIBUTING.md                     # Git workflow & PR instructions
└── README.md                          # Repository overview
```

---

## 5. Technology Stack

- **Platform:** Native Android (Min SDK: `26` / Android 8.0 Oreo, Target/Compile SDK: `35` / Android 15)
- **Language:** Kotlin 2.0.21
- **UI Toolkit:** Jetpack Compose (Material3, Compose BOM 2024.10.01)
- **Build System:** Gradle 8.14 (Kotlin DSL) with Version Catalogs (`libs.versions.toml`)
- **Reactive Concurrency:** Kotlin Coroutines & Asynchronous Flows (`StateFlow`, `SharedFlow`)
- **Persistence:** Local persistence ready (Room / SQLite) for offline retry queues
- **Acoustic Inference:** Mobile-compatible edge inference (TensorFlow Lite / ONNX Runtime)
- **Alert Demonstration:** Telegram Bot API HTTP integration with cellular SMS fallback

---

## 6. Getting Started

### Prerequisites
1. **Android Studio:** Android Studio Ladybug (2024.2+) or Meerkat (2024.3+)
2. **Java Development Kit (JDK):** JDK 17 (recommended: Eclipse Temurin 17 or OpenJDK 17)
3. **Android SDK:** Platform 34 or 35 with Build-Tools 34.0.0+ / 35.0.0

### Build & Run
```bash
# Clone the repository
git clone https://github.com/BALAJIx64/CudaCores.git
cd CudaCores

# Run architectural tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

For complete development environment instructions, see [docs/DEVELOPMENT_SETUP.md](docs/DEVELOPMENT_SETUP.md).

---

## 7. Documentation Index

- [Project Overview & Contributions](docs/PROJECT_OVERVIEW.md)
- [System Architecture & Diagrams](docs/ARCHITECTURE.md)
- [Module Contracts & Kotlin Interfaces](docs/MODULE_CONTRACTS.md)
- [Development Environment Setup](docs/DEVELOPMENT_SETUP.md)
- [Integration Roadmap](docs/INTEGRATION_PLAN.md)
- [Demo Test Cases & Verification](docs/DEMO_TEST_CASES.md)
- [Antigravity Master Prompt](docs/ANTIGRAVITY_MASTER_PROMPT.md)
- [Contribution Guidelines](CONTRIBUTING.md)
