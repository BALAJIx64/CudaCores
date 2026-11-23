# GuardianGrid: Antigravity Master Context Prompt

This document provides complete architectural context, interface boundaries, and operational instructions for AI agents and developer subagents working on the GuardianGrid repository.

---

## System Identity & Purpose

**GuardianGrid** is an intelligent native Android personal safety application built with **Kotlin** and **Jetpack Compose**. It unifies multimodal distress detection (AI scream classification, keyword spotting, accelerometer/gyroscope motion anomaly detection), two-stage audio intelligence, and resilient emergency communication (Telegram Bot API, SMS fallback, local persistence retry queue, and Bluetooth store-and-forward relay).

---

## Strict Repository Structure & Member Ownership

The repository is partitioned into isolated directories to allow four developers (or autonomous agents) to work in parallel on feature branches without git conflicts:

```
GuardianGrid/
├── app/                        # [Member 4] UI, Android Manifest, Application Entry
├── core/
│   ├── models/                # [Member 4] Shared cross-module data entities
│   ├── interfaces/            # [Member 4] Strict contracts across all modules
│   └── state/                 # [Member 4] Global reactive state & coordinator
├── modules/
│   ├── audio/                 # [Member 1] Acoustic classifier & two-stage recorder
│   ├── motion/                # [Member 2] Motion sensors & multimodal fusion engine
│   └── communication/         # [Member 3] Telegram, SMS, offline queue & BLE relay
├── models/
│   └── audio/                 # [Member 1] TFLite/ONNX audio model assets (.tflite)
├── docs/                      # Architectural, setup, contract & testing docs
└── tests/                     # Unit and architectural contract verification tests
```

---

## Immutable Architectural Rules

1. **Do not create competing Android projects:**
   - Do NOT create nested Android applications or independent Gradle builds inside `modules/audio`, `modules/motion`, or `modules/communication`.
   - All modules are compiled under the unified `:app` build via configured Gradle `sourceSets`.
2. **Never change existing interface contracts without consensus:**
   - The interfaces in `core/interfaces/` (`AudioDistressDetector`, `KeywordSpotter`, `AudioEvidenceRecorder`, `MotionAnomalyDetector`, `MultimodalFusionEngine`, `EmergencyCommunicator`, `DeliveryQueueManager`, `BluetoothRelayService`, `EmergencyCoordinator`) are binding contracts.
3. **Respect directory boundaries:**
   - When assigned to Member 1 tasks, touch only `modules/audio/` and `models/audio/`.
   - When assigned to Member 2 tasks, touch only `modules/motion/`.
   - When assigned to Member 3 tasks, touch only `modules/communication/`.
   - When assigned to Member 4 tasks, touch `app/` and `core/`.
   - **Crucial Rule:** Member 4 owns the application entry point, shared core contracts/models, UI and integration coordination. Other members must not independently modify shared application files (`app/`, `core/`, `build.gradle.kts`, `settings.gradle.kts`).
4. **Git Safety:**
   - Never push directly to GitHub without explicit instructions from the user.
   - Do not alter remote configurations.
   - Run `./gradlew testDebugUnitTest` before completing any task.

---

## Subsystem Implementation Guidelines

### Member 1: Audio Intelligence (`modules/audio`)
- Use Android `AudioRecord` (16kHz, 16-bit PCM mono).
- Implement an in-memory circular rolling buffer of 3–5 seconds duration.
- Discard raw audio continuously unless a scream or distress keyword is detected.
- Load TFLite models from `models/audio/` using `InterpreterApi`.

### Member 2: Motion Anomaly & Fusion (`modules/motion`)
- Register `SensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)` and `TYPE_GYROSCOPE`.
- Calculate magnitude vector: $\sqrt{a_x^2 + a_y^2 + a_z^2}$.
- Detect fall patterns: free-fall valley ($< 3\text{ m/s}^2$) followed by high-g impact ($> 25\text{ m/s}^2$).
- In `MultimodalFusionEngine`, correlate audio and motion within a $3000\text{ ms}$ temporal window.

### Member 3: Resilient Communication (`modules/communication`)
- Primary demonstration channel: Telegram Bot API HTTP POST (`https://api.telegram.org/bot<TOKEN>/sendMessage`).
- Fallback channel: `android.telephony.SmsManager.getDefault().sendTextMessage(...)`.
- Enqueue unsent payloads in local persistence; implement exponential backoff retry.
- Prepare BLE advertisement data structures for store-and-forward relay.

### Member 4: Core, UI & System Integration (`app` & `core`)
- Maintain `EmergencyStateHolder` thread-safe `StateFlow`.
- Implement `EmergencyCoordinatorImpl` to orchestrate transitions between `IDLE`, `MONITORING`, `VERIFYING`, and `CONFIRMED_EMERGENCY`.
- Build and refine Jetpack Compose UI under `app/src/main/kotlin/com/guardiangrid/ui/`.
