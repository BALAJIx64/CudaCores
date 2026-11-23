# GuardianGrid: Live Microphone Scream Detection Verifier

**Directory:** `tests/live_audio_test/`  
**Subsystem:** Audio Intelligence (Member 1)  
**Target Branch:** `feature/audio`

This suite provides real-world, live verification tools for GuardianGrid's Audio Intelligence module using your physical microphone or pre-recorded audio benchmarks.

---

## Architecture & Detection Engine

### MediaPipe YAMNet Scream Classifier (`live_audio_test.py`)
- **Model:** MediaPipe AudioClassifier YAMNet (`yamnet.tflite`, 4.13 MB, Apache 2.0).
- **Inference API:** Official Google MediaPipe Tasks Audio (`mediapipe.tasks.python.audio.AudioClassifier`).
- **Input Window:** 0.975 seconds (15,600 samples @ 16 kHz Mono Float32).
- **Streaming Cadence:** Evaluated every ~480 ms (50% window overlap).
- **Target Event:** `Screaming` (AudioSet Class 11).
- **Calibrated Criteria:**
  - Confidence Threshold $\ge 0.35$ sustained over $\ge 2$ consecutive windows ($\ge 480\text{ ms}$) OR single peak $\ge 0.50$.
  - Strict dominance over crying (`scream_score > cry_score`).
  - Clean rejection of non-scream sounds: Crying, sobbing, loud speech, shouting, fans, HVAC, traffic, keyboard clicks, and music.
  - Spoken keywords ("help", "stop", "save me") do **not** trigger scream detection.

---

## Visual Trigger (Green Screen GUI)

The manual test harness features a reactive GUI:
- **Default State:** Sleek dark window displaying `"Listening..."`, live audio level meter, and SNR telemetry.
- **Triggered State:** Instantly turns the entire window **bright emerald green** (`#00E676`) for 2.5 seconds, displaying:
  - Trigger Header: `🚨 SCREAM DETECTED!`
  - Matched event: `Scream Detected`
  - Confidence percentage (e.g. `50.0%`)
  - Full details and timestamp.
- Automatically reverts to `"Listening..."` after 2.5 seconds.
- Terminal simultaneously outputs clean, real-time diagnostic logs.

---

## How to Run

### Option A: Python MediaPipe YAMNet Live Microphone Test (`live_audio_test.py`)

#### 1. Graphical Green-Screen GUI Mode (Recommended)
```powershell
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests\live_audio_test\live_audio_test.py
```

#### 2. Terminal CLI Console Mode
```powershell
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests\live_audio_test\live_audio_test.py --cli
```

### Option B: Offline Benchmark & Calibration Suite (`evaluate_yamnet_distress.py`)

Runs the positive scream and negative non-scream test battery through the MediaPipe YAMNet model without needing a microphone:

```powershell
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests\live_audio_test\evaluate_yamnet_distress.py
```

---

## Verification Test Matrix

| Test Scenario | Acoustic Characteristics | Expected Behavior | Measured Result |
| :--- | :--- | :--- | :--- |
| **Real Human Scream** | High resonant energy in 1.5–4.5 kHz, F0 > 900 Hz | Trigger `Scream Detected` | **PASSED** (Score: 0.50) |
| **Real Crying / Sobbing** | 450–600 Hz harmonic weeping with sob inhalations | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.26 < 0.35 threshold & dominated) |
| **Continuous Speech** | Conversational speech, F0 100–180 Hz | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **Loud Shouting** | High loudness without distress vocal tract strain | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **HVAC / Fan Hum** | Steady low-frequency noise < 500 Hz | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **Traffic Rumble** | Low-frequency pink noise + vehicle Doppler | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **Keyboard Clicks** | Mechanical switch clicks (< 15 ms transients) | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **Music (Polyphonic)** | Harmonic chords (C major chord) | REJECTED (Zero scream trigger) | **PASSED** (Score: 0.000) |
| **Spoken Keywords** | "help", "stop", "save me" | REJECTED (Zero scream trigger) | **PASSED** (Zero trigger) |

---

## Important Limitation Notice

> [!WARNING]
> **YAMNet classifies acoustic sound events; it cannot establish intent or confirm that a person is actually in danger.**
> Its output indicates an acoustic "scream detected" event. GuardianGrid correlates this acoustic event with other multi-modal sensor inputs (motion impact, countdown timers, and coordinator dispatch) before confirming an emergency.
