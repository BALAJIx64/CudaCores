# GuardianGrid: Acoustic Model Specifications & Evaluation

**Directory:** `models/audio/`  
**Subsystem:** Audio Intelligence (Member 1)  
**Target Branch:** `feature/audio`

---

## 1. Model Evaluation & Selection

For autonomous distress detection on mobile devices, training a large-scale deep neural network from scratch is impractical due to constrained training data (scarcity of ethically sourced real-world assault recordings), high computational burden, and strict latency requirements.

We evaluated candidate architectures:

| Architecture / Model | Footprint | Input Format | Primary Strengths | Limitations | Decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **MediaPipe YAMNet (Google / AudioSet)** | 4.13 MB (float32 / int8 weights) | 16 kHz Mono Float32 (15,600 samples / 0.975s) | Pretrained on AudioSet; distinct class for Screaming (Class 11) | Classifies sound events; cannot decode speech keywords or determine intent | **Primary Pretrained Scream Detection Model** |
| **Edge Impulse 1D-CNN** | ~450 KB (int8) | Spectrogram (40 Mel bands $\times$ 50 frames) | Ultra-compact; fast inference (< 15 ms) | Requires custom dataset collection & annotation | Secondary fine-tuning target |
| **GuardianGrid Edge DSP Classifier** | ~0 KB (0 external dependencies) | 16 kHz PCM frames (512 samples / 32 ms) | Runs on pure Kotlin/JVM; zero dependency; deterministic; instant | Heuristic thresholds rather than learned representations | **Built-in Fallback & Biomechanical DSP Engine** |

---

## 2. Integrated Pretrained Model: Google MediaPipe YAMNet

### 2.1 Model Provenance & Artifacts
- **Model Name:** MediaPipe AudioClassifier YAMNet (`yamnet.tflite`)
- **Repository:** [`litert-community/MediaPipe-AudioClassifier-YAMNet`](https://huggingface.co/litert-community/MediaPipe-AudioClassifier-YAMNet)
- **License:** Apache License, Version 2.0
- **Base Architecture:** MobileNet-v1 depthwise separable convolutions
- **Dataset:** Google AudioSet (2M+ annotated audio clips)
- **Number of Classes:** 521 audio event categories
- **Local File Path:** `models/audio/yamnet.tflite`
- **File Size:** 4,126,810 bytes (4.13 MB)
- **SHA-256 Checksum:** `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`
- **Companion File:** `models/audio/yamnet_label_list.txt` (6,230 bytes, SHA-256 `8e1267a120c1932b7273c0d0e0c5529edbb9a35512b437b1c8982baa59047051`)

### 2.2 Input & Output Specifications
- **Input Tensor (`waveform_binary`):**
  - Shape: `[15600]`
  - Data Type: `Float32` in range `[-1.0, 1.0]`
  - Sampling Rate: 16,000 Hz Mono
  - Window Duration: 0.975 seconds (975 ms)
  - Hop Size: 0.4875 seconds (487.5 ms, 50% overlap in streaming mode)
- **Output Tensor (`tower0/network/layer32/final_output`):**
  - Shape: `[1, 521]`
  - Data Type: `Float32` (probability scores per class in `[0.0, 1.0]`)

### 2.3 AudioSet Class Indices: Scream Detection Only
- **Target Positive Class:**
  - **Index 11:** `Screaming` (Primary acoustic scream signature)
- **Excluded Competitor Classes (Cleanly Rejected):**
  - **Index 19:** `Crying, sobbing`
  - **Index 20:** `Baby cry, infant cry`
  - **Index 0:** `Speech`
  - **Index 6:** `Shout`
  - **Index 9:** `Yell`
  - **Index 10:** `Children shouting`
  - **Index 132:** `Music`

### 2.4 Calibrated Scream Detection Criteria
- **Confidence Threshold:** Score $\ge 0.35$ on `Screaming` (Class 11), with `scream_score > cry_score`.
- **Temporal Persistence:** Sustained across $\ge 2$ consecutive overlapping windows ($\ge 480\text{ ms}$) OR a single high-confidence peak $\ge 0.50$.
- **Cooldown Lockout:** 2.0 seconds between scream alerts to avoid flooding.

---

## 3. Built-in Edge DSP Classifier (Fail-Safe Architecture)

To guarantee that GuardianGrid functions reliably across all Android environments (including bare-metal test setups without external TFLite `.so` binaries), `ScreamDistressClassifier` incorporates a built-in biomechanical acoustic analysis engine:
1. **Scream-Band Energy Ratio:** Calculates $\frac{E_{1500\text{Hz}-4500\text{Hz}}}{E_{\text{total}}}$. Human screams concentrate > 35–70% of acoustic energy in this band due to vocal cord strain and pharyngeal resonance.
2. **Spectral Centroid:** Tracks center of mass of the spectrum. Screams shift centroid above 2200 Hz (vs. 600–1400 Hz for calm speech).
3. **Temporal Persistence:** Requires sustained distress signatures over multiple frames (> 128 ms) to eliminate false alarms from claps, dropped objects, or slammed doors.

---

## 4. Important Operational & Ethical Limitation

> [!WARNING]
> **Acoustic Event Detection is NOT Proof of Danger:**
> YAMNet is an acoustic event classifier trained on web videos. It classifies sound textures into categories. It **cannot establish a person's intent, context, or confirm that they are actually in danger** (e.g. amusement park rides, sports cheering, theatrical performances, movies).
> GuardianGrid must treat positive outputs strictly as **"Scream Detected"** and correlate them with motion sensors, countdown timers, and coordinator dispatch.
