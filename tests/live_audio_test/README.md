# GuardianGrid: Live Microphone Audio Intelligence & Distress Verifier

**Directory:** `tests/live_audio_test/`  
**Subsystem:** Audio Intelligence (Member 1)  
**Target Branch:** `feature/audio`

This suite provides real-world, live verification tools for GuardianGrid's Audio Intelligence module using your physical microphone or pre-recorded audio benchmarks.

---

## Architecture & Detection Engines

### 1. MediaPipe YAMNet Pretrained Vocal Distress Classifier (`live_audio_test.py`)
- **Model:** MediaPipe AudioClassifier YAMNet (`yamnet.tflite`, 4.13 MB, Apache 2.0).
- **Inference API:** Official Google MediaPipe Tasks Audio (`mediapipe.tasks.python.audio.AudioClassifier`).
- **Input Window:** 0.975 seconds (15,600 samples @ 16 kHz Mono Float32).
- **Streaming Cadence:** Evaluated every ~480 ms (50% window overlap).
- **Monitored Distress Classes:**
  - `Screaming` (AudioSet Class 11)
  - `Crying, sobbing` (AudioSet Class 19)
  - `Baby cry, infant cry` (AudioSet Class 20)
  - `Wail, moan` (AudioSet Class 22)
  - `Groan` (AudioSet Class 33)
- **Calibrated Criteria:**
  - Confidence Threshold $\ge 0.35$ sustained over $\ge 2$ consecutive windows ($\ge 480\text{ ms}$) OR single peak $\ge 0.50$.
  - Clean rejection of non-distress sounds: Loud speech, shouting, fans, HVAC, traffic, keyboard clicks, and music.
  - Preempts and locks out keyword spotting for 2.0 seconds upon distress detection.

### 2. Speech-Endpointed Keyword Spotter (KWS)
- **VAD State Machine:** Uses real-time adaptive noise floor tracking ($N_{\text{floor}}$) to detect speech onset ($N_{\text{floor}} + 8\text{ dB}$) and endpointing trailing silence ($225\text{ ms}$), isolating isolated words ($160\text{ ms} - 1350\text{ ms}$).
- **Orthogonal Acoustic-Phonetic Classification:**
  - **`"help"`**: 1 syllable, duration 200–550 ms, no initial `/s/` sibilant.
  - **`"stop"`**: 1 syllable, duration 200–580 ms, strong initial `/s/` sibilant (initial ZCR $\ge 0.17$).
  - **`"save me"`**: 2 syllables, duration 420–950 ms, strong initial `/s/` sibilant (initial ZCR $\ge 0.17$).
  - **`"bachao"`**: 2 syllables, duration 300–780 ms, no initial `/s/` sibilant.
  - **`"udhavi"`**: 3 syllables, duration 380–850 ms, initial low vowel `/u/`.
  - **`"kaapaathunga"`**: 3–4 rhythmic syllables, duration 550–1300 ms.

---

## Visual Trigger (Green Screen GUI)

Both the Python and Java test harnesses feature a reactive GUI:
- **Default State:** Sleek dark window displaying `"Listening..."`, live audio level meter, and SNR telemetry.
- **Triggered State:** Instantly turns the entire window **bright emerald green** (`#00E676`) for 2.5 seconds, displaying:
  - Trigger Type: `🚨 VOCAL DISTRESS DETECTED!` or `🗣️ KEYWORD DETECTED!`
  - Matched event: e.g. `Possible Vocal Distress: Screaming` or `'help' (EN)`
  - Confidence percentage (e.g. `88.0%`)
  - Full details and timestamp.
- Automatically reverts to `"Listening..."` after 2.5 seconds.
- Terminal simultaneously outputs clean, real-time diagnostic logs.

---

## How to Run

### Option A: Python MediaPipe YAMNet Live Test (`live_audio_test.py`)

Requires Python 3.9–3.11 with `mediapipe`, `numpy`, `sounddevice`, `scipy`:

```powershell
pip install -r tests/live_audio_test/requirements.txt
python tests/live_audio_test/live_audio_test.py
```

*Using the embeddable Python in this workspace:*
```powershell
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests/live_audio_test/live_audio_test.py
```

### Option B: Offline Benchmark & Calibration Suite (`evaluate_yamnet_distress.py`)

Runs the positive and negative test battery through the MediaPipe YAMNet model without needing a microphone:

```powershell
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests/live_audio_test/evaluate_yamnet_distress.py
```

### Option C: Standalone Java Live Test (`LiveAudioTestRunner.java`)

Zero-install runner using the JDK bundled in Android Studio:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" tests\live_audio_test\LiveAudioTestRunner.java
```

---

## Verification Test Matrix

| Test Scenario | Acoustic Characteristics | Expected Behavior | Measured Result |
| :--- | :--- | :--- | :--- |
| **Real Human Scream** | High resonant energy in 1.5–4.5 kHz, F0 > 900 Hz | Trigger `Possible Vocal Distress: Screaming` | **PASSED** (Score: 0.50) |
| **Real Crying / Sobbing** | 450–600 Hz harmonic weeping with sob inhalations | Trigger `Possible Vocal Distress: Crying, sobbing` | **PASSED** (Score: 0.957) |
| **Continuous Speech** | Conversational speech, F0 100–180 Hz | REJECTED (Zero false distress trigger) | **PASSED** (Score: 0.000) |
| **Loud Shouting** | High loudness without distress vocal tract strain | REJECTED (Distress score below threshold) | **PASSED** (Score: 0.000) |
| **HVAC / Fan Hum** | Steady low-frequency noise < 500 Hz | REJECTED (Zero false trigger) | **PASSED** (Score: 0.000) |
| **Traffic Rumble** | Low-frequency pink noise + vehicle Doppler | REJECTED (Zero false trigger) | **PASSED** (Score: 0.000) |
| **Keyboard Clicks** | Mechanical switch clicks (< 15 ms transients) | REJECTED (Zero false trigger) | **PASSED** (Score: 0.000) |
| **Music (Polyphonic)** | Harmonic chords (C major chord) | REJECTED (Zero false trigger) | **PASSED** (Score: 0.000) |
| **Spoken "help"** | 1 syllable, 200–550 ms, no initial /s/ | Trigger `KEYWORD: 'help' (EN)` | **PASSED** |
| **Spoken "stop"** | 1 syllable, 200–580 ms, initial /s/ | Trigger `KEYWORD: 'stop' (EN)` | **PASSED** |
| **Spoken "save me"** | 2 syllables, 420–950 ms, initial /s/ | Trigger `KEYWORD: 'save me' (EN)` | **PASSED** |
| **Spoken "bachao"** | 2 syllables, 300–780 ms, no initial /s/ | Trigger `KEYWORD: 'bachao' (HI)` | **PASSED** |
| **Spoken "udhavi"** | 3 syllables, 380–850 ms, initial low vowel | Trigger `KEYWORD: 'udhavi' (TA)` | **PASSED** |
| **Spoken "kaapaathunga"** | 3–4 syllables, 550–1300 ms | Trigger `KEYWORD: 'kaapaathunga' (TA)` | **PASSED** |

---

## Important Limitation Notice

> [!WARNING]
> **YAMNet classifies acoustic sound events; it cannot establish intent or confirm that a person is actually in danger.**
> Its output indicates a "possible distress vocalization signature". GuardianGrid correlates this acoustic event with other multi-modal sensor inputs (motion impact, countdown timers, and coordinator dispatch) before confirming an emergency.
