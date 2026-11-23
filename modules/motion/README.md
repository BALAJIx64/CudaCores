# Motion Module (modules/motion)

**Owner: Member 2**  
**Component:** Motion Anomaly Detection & Multimodal Sensor Fusion  
**Target Branch:** `feature/motion`

## Responsibilities
1. **Smartphone Motion Anomaly Detection:**
   - Captures high-frequency 3-axis accelerometer and gyroscope data.
   - Detects sudden impacts (falls), violent jerks/struggles, running distress, and device snatch patterns.
   - Baseline calibration to suppress common false triggers (walking, normal pocket jostling, placing phone on a desk).
2. **Multimodal Sensor Fusion Engine:**
   - Correlates incoming distress signals from Audio (Member 1) and Motion (Member 2) across a configurable sliding time window (e.g., 3000ms).
   - Generates unified confidence score:
     - Audio scream + sudden impact/struggle $\rightarrow$ Immediate emergency escalation ($\ge 0.85$).
     - Audio scream without motion $\rightarrow$ Suspicious verification countdown / Stage 1 buffer.
     - Isolated motion spike without audio $\rightarrow$ Minor threshold check to eliminate false alarm.

## Interfaces to Implement
- `com.guardiangrid.core.interfaces.MotionAnomalyDetector`
- `com.guardiangrid.core.interfaces.MultimodalFusionEngine`
