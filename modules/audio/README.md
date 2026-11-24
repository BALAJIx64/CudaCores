# GuardianGrid: Audio Intelligence Module (`modules/audio`)

**Owner:** Member 1  
**Subsystem:** Acoustic Scream Detection & Two-Stage Audio Evidence Recording  
**Target Branch:** `feature/audio`  

---

## 1. Overview & Architectural Scope

The Audio Intelligence module provides autonomous, privacy-preserving acoustic perception for GuardianGrid. It operates in real-time on ambient sound captured at 16 kHz Mono 16-bit PCM, extracting acoustic scream signatures without transmitting raw audio off-device.

> [!NOTE]
> **Scream Detection Focus:** Per project requirements, this module currently detects **screaming only**. The legacy experimental keyword-spotting (KWS) feature has been cleanly removed from active runtime code and contracts, and will be rebuilt in a dedicated future iteration.

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
    ┌───────────────────────────────┐           ┌──────────────────────────────┐
    │   ScreamDistressClassifier    │           │ TwoStageAudioEvidenceRecorder│
    │  • Pretrained YAMNet Model    │           │ • Stage 1: RAM dump to WAV   │
    │  • Edge DSP Acoustic Engine   │           │ • Stage 2: Segmented stream  │
    │  • Spectral Centroid / Ratio  │           │ • Canonical RIFF WAV encoder │
    │  • Calibrated Scream Filter   │           └──────────────┬───────────────┘
    └───────────────┬───────────────┘                          │
                    │                                          │
                    └───────────────────┬──────────────────────┘
                                        ▼
                         DistressSignal / AudioSegment
                           (Sent to Core Coordinator)
```

---

## 2. Component Structure

| File | Responsibility |
| :--- | :--- |
| `AudioModuleStub.kt` | Unified production facade implementing `AudioDistressDetector` and `AudioEvidenceRecorder`. |
| `AudioCaptureManager.kt` | Real-time `AudioRecord` ingestion (16 kHz, Mono, 16-bit PCM), permission handling, focus loss recovery, and test injector. |
| `CircularAudioBuffer.kt` | Zero-disk in-memory ring buffer (default 5000 ms / 160 KB RAM). Instant snapshot unrolling. |
| `AudioPreprocessor.kt` | PCM normalization, Hann windowing, RMS/dBFS calculation, ZCR, Radix-2 Real FFT, Spectral Centroid, Scream-band ratio, and 16-band Mel filterbank. |
| `ScreamDistressClassifier.kt` | Sound-event classifier (YAMNet compatible + built-in Edge DSP acoustic scream detector). |
| `TwoStageAudioEvidenceRecorder.kt` | Two-stage evidence collection: Stage 1 preview WAV buffer dump and Stage 2 continuous evidence streaming. |
| `WavAudioEncoder.kt` | Pure Kotlin canonical 44-byte RIFF WAVE encoder and header finalizer. |
| `AudioModuleTestHarness.kt` | Automated 6-part unit test suite running without external dependencies. |

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

### 3.2 `AudioEvidenceRecorder`
```kotlin
interface AudioEvidenceRecorder {
    suspend fun captureStageOneBuffer(durationMs: Long = 5000): Result<AudioSegment>
    suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment>
    fun stopRecording()
}
```

---

## 4. Scream Detection Only (YAMNet & Edge DSP)

The detector focuses strictly on acoustic screaming events:
1. **Target Class:** AudioSet Class 11 (`Screaming`).
2. **Rejection of Non-Scream Sounds:** Cleanly rejects crying/sobbing, shouting/yelling, conversational speech, music, traffic, HVAC/fans, and keyboard clicks.
3. **Calibrated Threshold:** Requires a measured confidence threshold ($\ge 0.35$, or single peak $\ge 0.50$) and temporal persistence over $\ge 2$ consecutive windows.
4. **Limitation Notice:** The detector identifies an acoustic scream signature. It cannot determine a person's intent or prove danger.

---

## 5. Two-Stage Audio Evidence Protocol

1. **Stage 1 (Preview Buffer):**
   - Normal ambient sound is stored **only in volatile RAM** in `CircularAudioBuffer`. Non-distress audio is continuously overwritten and discarded.
   - When a scream is detected, `captureStageOneBuffer(5000)` snapshots the past 3–5 seconds and flushes it as `stage1_preview.wav` with a canonical 44-byte RIFF header.
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
5. Two-stage audio evidence recording (Stage 1 RAM dump and Stage 2 file stream).
6. Error handling: permission denial, empty audio buffers, boundary conditions.
