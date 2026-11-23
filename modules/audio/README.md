# Audio Module (modules/audio)

**Owner: Member 1**  
**Component:** Acoustic Distress Detection & Two-Stage Audio Intelligence  
**Target Branch:** `feature/audio`

## Responsibilities
1. **Scream & Acoustic Distress Classification:**
   - Real-time microphone audio streaming via `AudioRecord`.
   - On-device ML inference (e.g. TFLite model in `models/audio/` such as YAMNet or lightweight CNN).
   - Detects screams, crying, and vocal distress with confidence scoring.
2. **Distress Keyword Spotting:**
   - Listens for configurable trigger hotwords (e.g., "Help", "Bachao", "Save me").
   - Low-power, low-latency wake-word style detection.
3. **Two-Stage Emergency Audio Recording:**
   - **Stage 1 (Rolling Buffer):** Maintains a continuous 3–5 second circular audio buffer in memory. Upon a suspicious event, dumps buffer as a preview segment.
   - **Stage 2 (Emergency Evidence Stream):** When confirmed emergency is declared, begins high-fidelity recording (segmented WAV/AAC) for forensic evidence and upload.

## Interfaces to Implement
- `com.guardiangrid.core.interfaces.AudioDistressDetector`
- `com.guardiangrid.core.interfaces.KeywordSpotter`
- `com.guardiangrid.core.interfaces.AudioEvidenceRecorder`

## Models & Resources
- Place `.tflite` model files in `models/audio/`.
- Ensure strict privacy compliance: raw non-distress audio must be discarded immediately from the rolling buffer.
