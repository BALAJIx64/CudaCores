# GuardianGrid: Audio Intelligence Module (`modules/audio`)

**Owner:** Member 1  
**Subsystem:** Acoustic Distress Detection, Multi-Language Keyword Spotting & Two-Stage Audio Evidence Recording  
**Target Branch:** `feature/audio`  

---

## 1. Overview & Architectural Scope

The Audio Intelligence module provides autonomous, privacy-preserving acoustic perception for GuardianGrid. It operates in real-time on ambient sound captured at 16 kHz Mono 16-bit PCM, extracting acoustic and phonetic signatures without transmitting raw audio off-device.

```
                               ┌──────────────────────────────────┐
                               │   AudioCaptureManager (16kHz)    │
                               │  (AudioRecord / Mock Fallback)   │
                               └────────────────┬─────────────────┘
                                                │
                                                ▼
                               ┌──────────────────────────────────┐
                               │       CircularAudioBuffer        │
                               │  (3-5 sec In-Memory Ring Buffer) │
                               └───────┬──────────────────┬───────┘
                                       │                  │
                    ┌──────────────────┴────────┐         │
                    ▼                           ▼         ▼
    ┌───────────────────────────────┐ ┌───────────────────────────┐ ┌──────────────────────────────┐
    │   ScreamDistressClassifier    │ │  DistressKeywordSpotter   │ │ TwoStageAudioEvidenceRecorder│
    │  • Pretrained YAMNet Model    │ │  • English: "help", "save"│ │ • Stage 1: RAM dump to WAV   │
    │  • Edge DSP Acoustic Engine   │ │  • Tamil: "kaapaathunga"  │ │ • Stage 2: Segmented stream  │
    │  • Spectral Centroid / Ratio  │ │  • Hindi: "bachao"        │ │ • Canonical RIFF WAV encoder │
    └───────────────┬───────────────┘ └─────────────┬─────────────┘ └──────────────┬───────────────┘
                    │                               │                              │
                    └───────────────────────┬───────┴──────────────────────────────┘
                                            ▼
                             DistressSignal / AudioSegment
                               (Sent to Core Coordinator)
```

---

## 2. Component Structure

| File | Responsibility |
| :--- | :--- |
| `AudioModuleStub.kt` | Unified production facade implementing `AudioDistressDetector`, `KeywordSpotter`, and `AudioEvidenceRecorder`. |
| `AudioCaptureManager.kt` | Real-time `AudioRecord` ingestion (16 kHz, Mono, 16-bit PCM), permission handling, focus loss recovery, and test injector. |
| `CircularAudioBuffer.kt` | Zero-disk in-memory ring buffer (default 5000 ms / 160 KB RAM). Instant snapshot unrolling. |
| `AudioPreprocessor.kt` | PCM normalization, Hann windowing, RMS/dBFS calculation, ZCR, Radix-2 Real FFT, Spectral Centroid, Scream-band ratio, and 16-band Mel filterbank. |
| `ScreamDistressClassifier.kt` | Sound-event classifier (YAMNet compatible + built-in Edge DSP acoustic screamer detector). |
| `DistressKeywordSpotter.kt` | Independent speech keyword spotter using phonetic profile matching across English, Tamil, and Hindi. |
| `TwoStageAudioEvidenceRecorder.kt` | Two-stage evidence collection: Stage 1 preview WAV buffer dump and Stage 2 continuous evidence streaming. |
| `WavAudioEncoder.kt` | Pure Kotlin canonical 44-byte RIFF WAVE encoder and header finalizer. |
| `AudioModuleTestHarness.kt` | Automated 7-part unit test suite running without external dependencies. |

---

## 3. Implemented Interface Contracts

The module implements the exact bindings defined in `core/interfaces/AudioContracts.kt`:

### 3.1 `AudioDistressDetector`
```kotlin
interface AudioDistressDetector {
    fun startMonitoring(): Flow<DistressSignal>
    fun stopMonitoring()
    fun isMonitoring(): Boolean
    fun setSensitivity(sensitivity: Float) // 0.0f .. 1.0f
}
```
- Emits: `DistressSignal(type = DistressType.SCREAM, confidence, source = "GuardianGrid_AcousticDistressClassifier", ...)`

### 3.2 `KeywordSpotter`
```kotlin
interface KeywordSpotter {
    fun startListening(targetKeywords: List<String>): Flow<DistressSignal>
    fun stopListening()
    fun isListening(): Boolean
}
```
- Emits: `DistressSignal(type = DistressType.KEYWORD, confidence, metadata = mapOf("keyword" to matched, "language" to lang))`

### 3.3 `AudioEvidenceRecorder`
```kotlin
interface AudioEvidenceRecorder {
    suspend fun captureStageOneBuffer(durationMs: Long = 5000): Result<AudioSegment>
    suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment>
    fun stopRecording()
}
```

---

## 4. Multi-Language Keyword Spotting

The `DistressKeywordSpotter` operates independently of generic sound-event classification, analyzing phonetic energy distributions across three frequency bands (Low: 100–800 Hz, Mid: 800–2500 Hz, High: 2500–6000 Hz) using dynamic alignment:

1. **English:**
   - `"help"` / `"help me"`: Aspirated onset $\rightarrow$ open-mid vowel /e/ $\rightarrow$ plosive stop /p/.
   - `"save me"`: High-frequency sibilant /s/ $\rightarrow$ diphthong /eɪ/ $\rightarrow$ voiced nasal /m/ $\rightarrow$ front vowel /i/.
   - `"stop"`: Sibilant /s/ $\rightarrow$ alveolar plosive /t/ $\rightarrow$ open vowel /ɒ/ $\rightarrow$ bilabial stop /p/.
2. **Tamil:**
   - `"kaapaathunga"` (காப்பாத்துங்க): 4-syllable rhythmic contour (`kaa - paa - thun - ga`). Distinct emergency call in Southern India.
   - `"udhavi"` (உதவி): 3-syllable open distress word (`u - dha - vi`).
3. **Hindi:**
   - `"bachao"` (बचाओ): Bilabial onset $\rightarrow$ open central vowel $\rightarrow$ rising affricate/diphthong.

---

## 5. Two-Stage Audio Evidence Protocol

1. **Stage 1 (Preview Buffer):**
   - Normal ambient sound is stored **only in volatile RAM** in `CircularAudioBuffer`. Non-distress audio is continuously overwritten and discarded.
   - When a suspicious or distress event is detected, `captureStageOneBuffer(5000)` snapshots the past 3–5 seconds and flushes it as `stage1_preview.wav` with a canonical 44-byte RIFF header.
2. **Stage 2 (Forensic Evidence Stream):**
   - When emergency escalation is confirmed, `startStageTwoRecording(outputFile)` initiates high-fidelity recording directly to disk.
   - Periodically emits `AudioSegment(stage = STAGE_2_EVIDENCE)` chunks for transmission via Telegram/API.
   - Finalizes WAV header with exact byte counts upon `stopRecording()`.

---

## 6. Testing & Verification

### 6.1 Automated Unit Test Suite (`AudioModuleTestHarness`)
To execute the comprehensive self-contained test suite:
```kotlin
val result = AudioModuleTestHarness.runAllTests()
println("Passed ${result.passed} / ${result.total} tests")
```
The suite verifies:
1. Canonical 44-byte RIFF WAV header encoding and file integrity.
2. Circular audio buffer ring rollover and snapshot unrolling.
3. Audio preprocessing: RMS, dBFS, ZCR, FFT, spectral centroid, scream ratio, and Mel filterbank.
4. Acoustic scream classifier on synthetic scream vs silence vs normal speech.
5. Multi-language keyword spotter matching for English and Tamil.
6. Two-stage audio evidence recording (Stage 1 RAM dump and Stage 2 file stream).
7. Error handling: permission denial, empty audio buffers, boundary conditions.

### 6.2 Architectural Verification
Run standard project unit tests:
```powershell
.\gradlew.bat testDebugUnitTest
```

### 6.3 Live Microphone Testing on Android Device / Emulator
1. **Permissions:** Ensure `RECORD_AUDIO` is granted in system settings or requested by the app.
2. **Emulator Testing:**
   - Open Android Studio Emulator Extended Controls ($\dots$) $\rightarrow$ **Microphone**.
   - Enable "Virtual microphone uses host audio input".
   - Speak "Help" or play a test distress sound into your computer microphone.
3. **Verification:**
   - The Compose Dashboard reflects `STATUS: VERIFYING` or `CONFIRMED_EMERGENCY`.
   - `stage1_preview.wav` appears in the app's cache directory with playable audio.
