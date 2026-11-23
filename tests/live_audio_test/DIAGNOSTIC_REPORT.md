# Diagnostic Report: Real-World Microphone False Positives

**Timestamp:** 2026-11-23 16:55  
**Component:** `tests/live_audio_test/` (Live Microphone Test Harness)  
**Symptom Reported:**
- Repeated false keyword detections: `[KEYWORD MATCHED] Word: 'save me' (EN) | Confidence: 72% - 81%` every ~4 seconds.
- Intermittent false scream detections: `[SCREAM DETECTED] Confidence: 70% - 80% | Score: 0.62 - 0.76` on ambient sounds.

---

## 1. Root Cause Analysis

### Root Cause 1: Global DTW vs. Subsequence Alignment Mismatch
- **Mechanism:** In the baseline live script, the keyword spotter took a 1.2-second sliding audio window ($N \approx 74$ frames) and ran standard **Global Dynamic Time Warping** against a short 3-step profile ($M = 3$ for `"save me"`).
- **The Mathematical Bug:**
  - Standard Global DTW forces a path from `(1,1)` to `(N,M)`.
  - When $N = 74$ and $M = 3$, the algorithm rapidly reaches column $j = 3$ (the final step: `[low: 0.7, mid: 0.2, high: 0.1]`) and stays in that column for the remaining ~70 frames.
  - Normal room ambient sound (PC fan noise, HVAC rumble, microphone self-noise) concentrates almost all its acoustic power below 800 Hz (e.g. `[low: ~0.70, mid: ~0.20, high: ~0.10]`).
  - Because ambient noise closely matches the final step `[0.7, 0.2, 0.1]`, the per-frame Euclidean distance is tiny ($\approx 0.05$).
  - Over 74 frames, the cumulative DTW distance divided by $(N + M)$ yields an average distance of only $\approx 0.15$, producing a synthetic confidence of:
    $$\text{Confidence} = 1.0 - 0.15 = 0.85\text{ (85%!) }$$
  - **Result:** Ambient room silence matches `"save me"` with ~80% confidence on almost every single sliding cycle!

---

### Root Cause 2: Static dBFS Threshold Without Adaptive Noise Floor (VAD)
- **Mechanism:** The baseline code used a fixed gate of `-32.0 dBFS` for speech and `-28.0 dBFS` for screams.
- **The Issue:** Microphones and sound cards have wide variances in hardware analog gain and AGC (Automatic Gain Control):
  - On many built-in laptop and headset microphones, ambient room noise often registers between **-30 dBFS and -24 dBFS**.
  - A fixed threshold of `-32.0 dBFS` means the gate is **permanently open** even in an empty room.
  - Without an adaptive noise floor calculation, the detector treats ambient background hum as active human speech.

---

### Root Cause 3: Scream Persistence & Frequency Banding Over-Sensitivity
- **Mechanism:** The baseline scream detector checked `consecutiveScreamFrames >= 2` (where 2 frames at 40ms hop = **80 ms** total duration).
- **The Issue:**
  - 80 ms is too short to differentiate a human scream from a transient impulse (a mouse click, a chair squeak, a keyboard key stroke, or a throat clear).
  - Mechanical keyboard switches produce high-frequency clicks (2 kHz – 4 kHz) that match the scream resonant band. When clicked, they easily exceed the 80 ms threshold and produce a false `SCREAM DETECTED`.
  - Real human vocal distress screams are sustained physiological events typically lasting **300 ms to 1500 ms** with high Sound Pressure Level ($> -18\text{ dBFS}$ on typical mics).

---

## 2. The Solution & Fix Architecture

To achieve clean, zero-false-positive live detection, three calibrated enhancements are introduced:

1. **Adaptive Ambient Noise Floor (Energy Gate):**
   - Calibrates the room's noise floor ($N_{\text{floor}}$) in real-time.
   - **Speech Gate:** Requires audio energy to be at least **$+12\text{ dB}$ above $N_{\text{floor}}$** (and $> -28\text{ dBFS}$) before running keyword spotting. Silence/ambient noise is discarded immediately.
   - **Scream Gate:** Requires audio energy to be at least **$+22\text{ dB}$ above $N_{\text{floor}}$** (and $> -16\text{ dBFS}$) for scream classification.

2. **Isolated Speech-Burst Segmentation for Keyword Spotting:**
   - Rather than aligning 74 frames of silence, the system detects a discrete **Speech Utterance** (onset $\rightarrow$ syllable energy $\rightarrow$ offset).
   - Only utterances with a valid human speech duration (**250 ms to 900 ms**) are evaluated against keyword templates.
   - Requires balanced progression across all syllable steps (each step must be backed by real acoustic frames, preventing the 1-step stall bug).

3. **Robust Scream Filter:**
   - Increases required duration to at least **250 ms** of sustained high-energy scream-band power ($E_{1.5\text{k}-4.5\text{k}} / E_{\text{total}} > 45\%$) and Spectral Centroid $> 2200\text{ Hz}$.
   - Rejects transient keyboard clicks, coughs, and chair creaks.

---

## 3. Calibrated Test Harnesses

The official live microphone test harness is:
- `tests/live_audio_test/live_audio_test.py` (Official MediaPipe YAMNet live microphone runner with reactive GUI and terminal CLI mode)
- `tests/live_audio_test/evaluate_yamnet_distress.py` (Automated benchmark and calibration suite)
