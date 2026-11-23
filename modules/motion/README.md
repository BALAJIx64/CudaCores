# GuardianGrid — Motion Anomaly & Multimodal Fusion Module

**Owner:** Member 2  
**Component:** Motion Anomaly Detection & Multimodal Sensor Intelligence  
**Target Git Branch:** `feature/motion`  
**Package:** `com.guardiangrid.modules.motion`

---

## 1. Overview & Architecture

The Motion & Multimodal Intelligence subsystem is GuardianGrid's physical perception and cross-modal reasoning core. It combines high-frequency inertial telemetry (3-axis accelerometer and gyroscope) with acoustic distress evidence from the Audio subsystem (Member 1) to make explainable, low-latency, and false-alarm-resistant emergency escalation decisions.

```
                  ┌───────────────────────────────┐
                  │    Hardware Sensor Stream     │
                  │ (Accelerometer + Gyroscope)   │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │   MotionSensorSource Adapter  │
                  │ (Android / Synthetic Source)  │
                  └───────────────┬───────────────┘
                                  │ MotionSample
                                  ▼
                  ┌───────────────────────────────┐
                  │     MotionFeatureExtractor    │
                  │  • |a|, |ω|, Jerk (da/dt)     │
                  │  • Rolling Window Statistics  │
                  │  • Baseline Calibration       │
                  └───────────────┬───────────────┘
                                  │ MotionFeatures
                                  ▼
                  ┌───────────────────────────────┐
                  │   MotionAnomalyDetectorImpl   │
                  │  • Fall/Impact Valley-Peak    │
                  │  • Struggle / Frantic Shake   │
                  │  • Device Snatch Detection    │
                  └───────┬───────────────┬───────┘
                          │               │
      Candidate Anomaly   │               │
                          ▼               │
                  ┌───────────────┐       │
                  │FalseAlarmFilter│       │ (Suppressed if gait/desk placement)
                  └───────┬───────┘       │
                          │ Verified      │
                          ▼               ▼
                 DistressSignal(FALL_IMPACT / MOTION_ANOMALY)
                                  │
                                  ▼
                 ┌────────────────────────────────┐
Audio Signals ──►│   MultimodalFusionEngineImpl   │◄── Manual SOS /
(SCREAM/KEYWORD) │  • Temporal Window Correlation │    Silent Triggers
                 │  • Explainable Decision Engine │
                 └────────────────┬───────────────┘
                                  │ FusionResult
                                  ▼
                 ┌────────────────────────────────┐
                 │      EmergencyStateMachine     │
                 │ NORMAL ──► VERIFYING ──► ALERTED│
                 │   ▲           │            ▲   │
                 │   └─ CANCELLED│RESOLVED ───┘   │
                 └────────────────────────────────┘
```

---

## 2. Mathematical Formulations & Algorithms

### 2.1 Kinematic Quantities
1. **Acceleration Vector Magnitude:**
   $$|a| = \sqrt{a_x^2 + a_y^2 + a_z^2}$$
2. **Angular Velocity Magnitude (Gyroscope):**
   $$|\omega| = \sqrt{\omega_x^2 + \omega_y^2 + \omega_z^2}$$
3. **Kinematic Jerk (Rate of Acceleration Change):**
   $$j(t) = \frac{\big||a(t)| - |a(t - \Delta t)|\big|}{\Delta t}$$
4. **Dynamic Acceleration (Gravity Compensated):**
   $$a_{\text{dynamic}} = \big| |a| - g_{\text{baseline}} \big|$$
   where $g_{\text{baseline}}$ is adaptively computed during stationary calibration (nominal: $9.80665\text{ m/s}^2$).

### 2.2 Anomaly Classification Criteria
- **Fall Detection (`FALL_IMPACT`):**
  A distinct free-fall valley ($|a| < 3.0\text{ m/s}^2$ for $\ge 80\text{ms}$) followed within $800\text{ms}$ by a sharp impact deceleration peak ($|a| \ge 24.0\text{ m/s}^2$).
- **Violent Struggle / Shaking (`MOTION_ANOMALY`):**
  High magnitude variance ($\sigma^2_{|a|} \ge 14.0\text{ m/s}^2$), rapid directional reversals ($\ge 4$ zero-crossings per window), and high rotational velocity ($|\omega| \ge 3.8\text{ rad/s}$).
- **Device Snatch Pattern (`MOTION_ANOMALY`):**
  Sudden extreme jerk ($j \ge 95.0\text{ m/s}^3$) coupled with high linear acceleration ($|a| \ge 21.0\text{ m/s}^2$).

### 2.3 Multimodal Cross-Modal Fusion Formula
For overlapping signals within the sliding temporal window ($W = 3000\text{ms}$), cross-modal independent probability fusion is applied with temporal proximity weighting:

$$c_{\text{fused}} = 1.0 - (1.0 - c_{\text{audio}}) \cdot (1.0 - c_{\text{motion}}) + \gamma \cdot \left(1.0 - \frac{\Delta t}{W}\right)$$

where $\Delta t = |t_{\text{audio}} - t_{\text{motion}}|$, $\gamma = 0.08$. This guarantees combined confidence $\ge 0.90$ for corroborating audio screams and physical impacts/struggles.

---

## 3. Intelligent False-Alarm Management

A critical safety system requirement is that a random phone shake or gentle movement must **not** trigger emergency escalation. GuardianGrid implements a dedicated `FalseAlarmFilter`:

1. **Walking & Running Gait Suppression:**
   Rhythmic, periodic oscillations with moderate acceleration ($8.0\text{ m/s}^2 \le |a| \le 15.5\text{ m/s}^2$), low jerk, and no free-fall are recognized as normal locomotion and suppressed.
2. **Desk / Table Placement Suppression:**
   Single moderate impact deceleration without prior free-fall ($|a| \le 18.0\text{ m/s}^2$) accompanied by negligible rotation ($|\omega| < 2.0\text{ rad/s}$) is identified as placing the device on a surface and filtered out.
3. **Isolated Motion Penalty:**
   Any isolated motion anomaly (even a severe drop) without corroborating vocal/acoustic evidence is capped at confidence $< 0.80$ and placed in `VERIFYING` or `SUSPECTED_DISTRESS` rather than causing an immediate panic alert.

---

## 4. Emergency State Machine

The system manages a deterministic finite-state machine across seven mandatory lifecycle states:

| State | Operational Definition | Entry Conditions |
| :--- | :--- | :--- |
| `NORMAL` | Baseline background protection | System startup, or reset from `CANCELLED`/`RESOLVED` |
| `SUSPECTED_DISTRESS` | Isolated weak or uncorroborated anomaly | Low-confidence acoustic or motion spike ($0.40 \le c < 0.60$) |
| `VERIFYING` | Distress detected; countdown running | Moderate audio ($0.60 \le c < 0.85$) or uncorroborated fall impact |
| `HIGH_CONCERN` | Elevated risk; countdown nearing expiration | Countdown remaining $\le 2000\text{ms}$ |
| `ALERTED` | Confirmed emergency! Outbound dispatch | `MANUAL_SOS`, strong audio ($\ge 0.85$), multimodal correlation ($\ge 0.80$), or countdown expiry |
| `CANCELLED` | User cancelled countdown or dismissed alert | PIN/User cancellation in `VERIFYING`, `HIGH_CONCERN`, or `ALERTED` |
| `RESOLVED` | Emergency incident officially resolved safe | Responder or user mark-safe action from `ALERTED` |

### Escalation Invariants:
1. **Manual SOS / Silent Trigger:** Immediately transitions to `ALERTED` (confidence $1.0$).
2. **Strong Audio Alone:** A scream or distress keyword with confidence $\ge 0.85$ escalates directly to `ALERTED` without requiring motion confirmation.
3. **Random Phone Shake:** Suppressed by `FalseAlarmFilter` or kept in `SUSPECTED_DISTRESS`; never triggers `ALERTED` alone.

---

## 5. Independent Testing & Verification Scenarios

The module is verified using `MotionDemoScenarioRunner` and unit tests in `tests/MotionModuleIntegrationTest.kt` covering all 7 required scenarios:

1. **Normal Movement:** 20+ walking gait samples processed; state remains `NORMAL`.
2. **Sudden Phone Drop:** Free-fall followed by impact; classified as `FALL_IMPACT`; enters `VERIFYING` without false alert.
3. **Abnormal Shaking:** High variance and direction reversals; classified as `MOTION_ANOMALY`; enters `SUSPECTED_DISTRESS`/`VERIFYING`.
4. **Audio Distress with Normal Motion:** Loud scream ($0.92$) while stationary; escalates directly to `ALERTED`.
5. **Correlated Audio and Motion:** Fall impact followed within $600\text{ms}$ by scream; combined confidence $0.94$; escalates to `ALERTED`.
6. **Manual SOS:** Immediate transition to `ALERTED` with confidence $1.0$.
7. **False-Alarm Cancellation:** Moderate signal enters `VERIFYING`, user issues cancellation; state cleanly transitions to `CANCELLED`.

---

## 6. Directory Structure

```
modules/motion/
├── README.md                               # Complete subsystem documentation
├── MotionModuleStub.kt                     # Bridge preserving backwards compatibility
├── sensors/
│   ├── MotionSensorSource.kt              # Hardware abstraction interface
│   ├── AndroidMotionSensorSource.kt       # SensorManager hardware implementation
│   └── SyntheticMotionSensorSource.kt     # Synthetic injector for pure unit tests
├── features/
│   ├── MotionFeatures.kt                  # Kinematic feature data class
│   ├── MotionFeatureExtractor.kt          # Sliding-window jerk & variance processor
│   └── MotionBaselineCalibrator.kt        # User gait & stationary gravity calibrator
├── anomaly/
│   ├── MotionThresholdConfig.kt           # Sensitivity and threshold configuration
│   └── MotionAnomalyDetectorImpl.kt       # Core MotionAnomalyDetector implementation
├── falsealarm/
│   └── FalseAlarmFilter.kt                # Gait & desk placement suppression engine
├── fusion/
│   └── MultimodalFusionEngineImpl.kt      # Temporal correlation & explainable fusion
├── state/
│   ├── MotionEmergencyState.kt            # 7 mandatory emergency states & mappings
│   └── EmergencyStateMachine.kt           # Deterministic finite-state machine
└── demo/
    └── MotionDemoScenarioRunner.kt        # Programmatic 7-scenario evaluation harness
```
