# GuardianGrid: Project Overview

## 1. Problem Statement

Conventional personal safety applications depend predominantly on manual SOS activation. These solutions assume that the user:
1. Has their smartphone readily accessible in hand.
2. Has the time, composure, and physical freedom to unlock the device and interact with a UI or press physical buttons.
3. Has stable and uninterrupted internet connectivity to dispatch emergency alerts.
4. Operates in an environment where an attacker will not immediately seize the phone, terminate running apps, or destroy evidence.

In reality, violent crimes, physical restraint, kidnappings, and harassment frequently render manual activation impossible. A victim may be pinned, physically overwhelmed, or coerced into silence. Furthermore, emergencies frequently occur in connectivity dead-zones (underground parking, basements, elevators, remote transit corridors). If an attacker disables the phone or network connection before an alert transmits, all evidence and location telemetry are permanently lost.

---

## 2. Proposed Solution

**GuardianGrid** is an intelligent, native Android personal safety application that unifies **multimodal distress detection**, **intelligent emergency verification**, **resilient communication**, and **emergency evidence management**.

GuardianGrid continuously analyzes ambient sound for screams and configurable distress keywords while simultaneously tracking device motion anomalies (sudden drops, impacts, violent struggles). A multimodal verification engine correlates acoustic and inertial signals across sliding temporal windows to eliminate false alarms while guaranteeing rapid escalation during genuine emergencies.

Following an emergency trigger, GuardianGrid activates a **two-stage audio intelligence** protocol:
- **Stage 1 (Preview):** Immediately extracts a rolling in-memory 3–5 second audio segment captured during the suspicious event for automated verification and triage.
- **Stage 2 (Forensic Evidence Stream):** Upon authorized high-confidence escalation, initiates continuous or segmented high-fidelity recording and transmission.

To ensure alerts reach trusted contacts regardless of network conditions, GuardianGrid executes a **tiered, resilient communication strategy**:
1. **Primary Internet Dispatch:** Dispatches rich telemetry (GPS coordinates, trigger summary, audio evidence link) via Telegram Bot API.
2. **Cellular SMS Fallback:** Automatically switches to compact carrier SMS when internet connectivity fails.
3. **Persistent Local Queue:** Persists failed or pending alerts in an encrypted offline queue, retrying automatically with delivery status tracking.
4. **Bluetooth Relay (Foundation):** Broadcasts emergency distress beacons via BLE to nearby GuardianGrid nodes using a store-and-forward mechanism.

---

## 3. Core Technical Contributions

1. **AI-Based Scream & Distress Sound Classification:** Real-time on-device acoustic classification distinguishing human vocal distress from background urban noise.
2. **Configurable Distress Keyword Spotting:** Low-latency, privacy-preserving hotword spotting for predefined and user-customized emergency phrases.
3. **Smartphone Motion Anomaly Detection:** 3-axis accelerometer and gyroscope analytics identifying falls, struggle patterns, and snatch attempts.
4. **Multimodal Sensor Fusion & False-Alarm Management:** Temporal correlation engine that combines acoustic and inertial signals to minimize false positives.
5. **Silent & Discreet Emergency Activation:** Zero-look physical triggers (e.g., volume button sequence) and stealth on-screen activation without alerting an assailant.
6. **Two-Stage Emergency Audio Analysis & Communication:** Rolling buffer capture followed by high-fidelity segmented evidence streaming.
7. **Internet-Based Alerts & SMS Fallback:** Reliable dual-channel emergency dispatch ensuring message delivery under degraded connectivity.
8. **Nearby-Device Bluetooth Store-and-Forward Relay:** Ad-hoc peer-to-peer distress beaconing for zero-connectivity scenarios.
9. **Persistent Emergency Event Management & Delivery Acknowledgment:** Local event persistence with status tracking (`PENDING` $\rightarrow$ `IN_FLIGHT` $\rightarrow$ `DELIVERED`).
10. **Emergency Continuity & Last-Seen Tracking:** Resilient background execution via foreground services, periodic location pings, and interruption logging.
11. **Privacy-Preserving Audio Evidence Management:** On-device circular buffer retention that discards non-emergency audio immediately.
12. **Incident History & Communication Audit Trail:** Verifiable local event log recording timestamps, sensor confidences, and delivery receipts.

---

## 4. Expected Impact

GuardianGrid dramatically reduces reliance on manual SOS interaction and continuous network availability, providing women and vulnerable individuals with an autonomous, resilient safety shield that preserves life-saving telemetry and forensic evidence during critical moments.
