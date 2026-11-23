# GuardianGrid: Demonstration Test Cases & Evaluation

This document outlines the acceptance criteria, verification scenarios, and end-to-end evaluation procedures for demonstrating GuardianGrid's capabilities.

---

## Test Case 1: Manual Discreet SOS Triggering
- **Objective:** Verify immediate silent emergency activation without UI dependencies or audible feedback.
- **Preconditions:** Protection mode is `ACTIVE_MONITORING` or `STANDBY`.
- **Action:** User presses the prominent on-screen SOS button OR activates the physical triple-click volume sequence.
- **Expected Outcome:**
  1. `EmergencyCoordinator` immediately generates an `EmergencyIncident` with `level = CONFIRMED_EMERGENCY`.
  2. UI banner transitions to high-contrast red: `EMERGENCY ESCALATED`.
  3. `EmergencyCommunicator` dispatches an `AlertPayload` via Telegram Bot API and SMS.
  4. Outbound delivery receipts appear in the Audit Trail within $< 2\text{ seconds}$.

---

## Test Case 2: Autonomous Acoustic Distress & Two-Stage Buffer
- **Objective:** Verify acoustic scream/distress classification and Stage 1 buffer capture.
- **Preconditions:** Phone is running background protection with microphone active.
- **Action:** Play a 3-second loud scream audio clip (or trigger via simulation button).
- **Expected Outcome:**
  1. `AudioDistressDetector` emits `DistressSignal(type = SCREAM, confidence >= 0.85)`.
  2. System transitions to `VERIFYING` state.
  3. `AudioEvidenceRecorder.captureStageOneBuffer(5000)` executes, preserving the rolling in-memory buffer into internal storage (`stage1_preview.wav`).
  4. If corroborated or uncancelled within countdown ($5\text{s}$), escalates to `CONFIRMED_EMERGENCY` and initiates Stage 2 evidence streaming.

---

## Test Case 3: Multimodal Audio-Motion Fusion vs. False Alarms
- **Objective:** Verify that cross-modal sensor correlation prevents false alarms while escalating genuine incidents.
- **Scenarios:**
  - **Scenario 3A (Isolated Fall):** Phone dropped onto a soft surface (motion anomaly detected, but audio is silent).
    - *Expected:* Motion signal emitted ($0.65$ confidence); fusion score remains $< 0.80$; no emergency alert dispatched; status logged as suspicious or discarded.
  - **Scenario 3B (Isolated Vocal Shout):** Loud speech in environment without phone movement.
    - *Expected:* Audio signal emitted ($0.75$ confidence); initiates verification countdown without immediate escalation.
  - **Scenario 3C (Correlated Assault / Struggle):** Violent shaking and impact accompanied by distress scream within $3000\text{ ms}$.
    - *Expected:* `MultimodalFusionEngine` computes combined confidence $\ge 0.90$; immediate escalation triggered; emergency alerts dispatched to trusted contacts.

---

## Test Case 4: Connectivity Resilience (Telegram Failover to SMS)
- **Objective:** Validate multi-channel failover when internet access is disrupted.
- **Preconditions:** Device is switched to Airplane Mode or Wi-Fi/Mobile Data disabled, with cellular radio active.
- **Action:** Trigger SOS alert.
- **Expected Outcome:**
  1. Telegram Bot API HTTP connection times out or fails with network unreachable.
  2. `EmergencyCommunicator` generates `DeliveryReceipt(channel = TELEGRAM, status = FAILED_RETRYING)`.
  3. System automatically switches to Tier 2: `DeliveryChannel.SMS`.
  4. Emergency message containing GPS coordinates is sent via Android `SmsManager`.
  5. Failed Telegram alert is persisted in `DeliveryQueueManager` local queue and retried once internet connectivity is restored.

---

## Test Case 5: Offline Persistence & Recovery
- **Objective:** Ensure emergency event records survive device power interruption or process termination.
- **Preconditions:** Device completely disconnected from all networks.
- **Action:** Trigger emergency; verify item enqueued; force close app or reboot device.
- **Expected Outcome:**
  1. Upon restart, `EmergencyStateHolder` loads pending queue events from local persistence.
  2. Once network returns, automatic retry executes.
  3. Delivery acknowledgment is recorded and reflected in audit trail.

---

## Test Case 6: Bluetooth Store-and-Forward Relay
- **Objective:** Validate BLE distress beaconing when no cellular or internet connection is available.
- **Preconditions:** Two test devices with GuardianGrid installed; Device A disconnected from internet/cellular; Device B connected to internet.
- **Action:** Device A initiates emergency alert.
- **Expected Outcome:**
  1. Device A broadcasts BLE emergency advertisement with compact incident hash and GPS payload.
  2. Device B scanner detects beacon, extracts payload, and forwards alert to Telegram Bot API on Device A's behalf.
  3. Device A marks beacon as acknowledged upon receipt of confirmation.
