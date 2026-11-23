# GuardianGrid: Integration Plan & Roadmap

## 1. Overview & Strategy

GuardianGrid is engineered around four independent feature streams that converge at predefined architectural milestones. By maintaining strict interface contracts in `core/interfaces/` and isolated directories in `modules/`, team members can develop their algorithms and drivers in parallel with zero merge friction.

---

## 2. Phased Integration Roadmap

```
Week 1: Phase 1 (Foundation & Contracts)  [COMPLETE]
├── Shared data models (core/models)
├── Subsystem contracts (core/interfaces)
├── Global state & coordinator (core/state)
└── Baseline Jetpack Compose UI & Unit test suite
       │
Week 2-3: Phase 2 (Independent Subsystem Implementation)
├── Member 1 (feature/audio): TFLite classifier, keyword spotter, 2-stage recorder
├── Member 2 (feature/motion): Sensor listeners, impact heuristics, fusion algorithm
├── Member 3 (feature/communication): Telegram HTTP client, SMS manager, SQLite retry queue
└── Member 4 (feature/core-ui): Foreground service, location tracker, discreet gestures
       │
Week 4: Phase 3 (Pairwise Integration)
├── Audio + Core: Audio distress signals flowing into EmergencyCoordinator
├── Motion + Core: Motion distress signals triggering verifying countdown
└── Comm + Core: Emergency state triggering automated multi-channel dispatch
       │
Week 5: Phase 4 (Multimodal Sensor Fusion Integration)
├── Member 2's MultimodalFusionEngine evaluating combined Audio + Motion streams
└── False alarm tuning with benchmark audio clips and physical movement tests
       │
Week 6: Phase 5 (End-to-End Hardening & Resilient Continuity)
├── Network disconnection stress tests (Telegram failure -> SMS fallback -> Offline queue)
├── Bluetooth store-and-forward relay beacon verification
└── Field demonstration and final release candidate
```

---

## 3. Detailed Phase Breakdown

### Phase 1: Common Foundation (Current Milestone)
- **Status:** **Completed & Verified**
- **Deliverables:**
  - Buildable Android application `:app` with Jetpack Compose.
  - Core interfaces (`AudioContracts`, `MotionContracts`, `CommunicationContracts`, `CoreContracts`).
  - Common models (`DistressSignal`, `EmergencyIncident`, `AlertPayload`, `DeliveryReceipt`).
  - Unit test suite (`tests/CoreArchitectureTest.kt`) confirming all contracts.

### Phase 2: Independent Subsystem Engineering (Parallel Branches)
- **Member 1 (`feature/audio`):**
  - Integrate TFLite runtime or ONNX Runtime Mobile.
  - Implement continuous `AudioRecord` streaming with short FFT / Mel-spectrogram processing.
  - Implement rolling in-memory 5-second PCM circular buffer.
  - Write unit tests in `modules/audio/` using recorded scream and speech WAV fixtures.
- **Member 2 (`feature/motion`):**
  - Register `SensorManager` listeners for `Sensor.TYPE_ACCELEROMETER` and `Sensor.TYPE_GYROSCOPE`.
  - Implement peak jerk ($da/dt$) and magnitude vector threshold algorithms.
  - Implement baseline calibration to cancel out user walking patterns.
  - Develop `MultimodalFusionEngine` logic combining audio + motion signals.
- **Member 3 (`feature/communication`):**
  - Implement Telegram Bot API HTTP alert client (using `HttpURLConnection` or lightweight client).
  - Implement Android `SmsManager` fallback dispatch when network is unreachable.
  - Implement persistent local alert queue (SQLite / Room) with retry policies.
  - Implement BLE beacon advertiser and scanner stub.
- **Member 4 (`feature/core-ui`):**
  - Implement Android `ForegroundService` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` and `LOCATION`.
  - Implement discreet volume button triple-click hardware trigger listener.
  - Implement contacts configuration UI and emergency escalation countdown dialog.

### Phase 3: Subsystem Convergence & Integration
- Connect Member 1's `AudioDistressDetector` and Member 2's `MotionAnomalyDetector` to `EmergencyCoordinatorImpl`.
- Verify that `EmergencyStateHolder` emits real-time detection events to the Compose Dashboard.

### Phase 4: Fusion & False-Alarm Benchmarking
- Evaluate cross-modal distress vs. isolated false triggers (e.g. dropping phone on bed without vocal sound).
- Calibrate temporal window (2500ms - 4000ms) to ensure timely escalation within 5 seconds.

### Phase 5: Resilient Field Evaluation
- Execute test scenarios outlined in [docs/DEMO_TEST_CASES.md](DEMO_TEST_CASES.md).
- Verify SMS fallback in airplane mode.
- Demonstrate incident audit trail and forensic audio preservation.
