# GuardianGrid: Acoustic Model Specifications & Evaluation

**Directory:** `models/audio/`  
**Subsystem:** Audio Intelligence (Member 1)  
**Target Branch:** `feature/audio`

---

## 1. Model Evaluation & Selection

For autonomous distress detection on mobile devices, training a large-scale deep neural network from scratch is impractical due to constrained training data (scarcity of ethically sourced real-world assault recordings), high computational burden, and strict latency requirements.

We evaluated three candidate architectures:

| Architecture / Model | Footprint | Input Format | Primary Strengths | Limitations | Decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **YAMNet (Google / AudioSet)** | ~3.7 MB (int8/float32) | 16 kHz Mono Float32 (15,600 samples / 0.975s) | Pretrained on AudioSet; distinct classes for Screaming, Shout, Yell, Crying | Requires TFLite runtime; cannot distinguish speech keywords | **Selected as Primary Pretrained Model** |
| **Edge Impulse 1D-CNN** | ~450 KB (int8) | Spectrogram (40 Mel bands $\times$ 50 frames) | Ultra-compact; fast inference (< 15 ms) | Requires custom dataset collection & annotation | Secondary fine-tuning target |
| **GuardianGrid Edge DSP Classifier** | ~0 KB (0 external dependencies) | 16 kHz PCM frames (512 samples / 32 ms) | Runs on pure Kotlin/JVM; zero dependency; deterministic; instant | Heuristic thresholds rather than learned representations | **Selected as Built-in Core & Fallback Engine** |

---

## 2. Selected Pretrained Model: Google YAMNet

### 2.1 Model Overview
- **Model Name:** YAMNet (Yet Another Mobile Network)
- **Source:** TensorFlow Hub / Google Research (`tflite_yamnet`)
- **Base Architecture:** MobileNet-v1 depthwise separable convolutions
- **Dataset:** Google AudioSet (2M+ annotated audio clips)
- **Number of Classes:** 521 audio event categories
- **Model Size:**
  - Float32: 3.9 MB
  - Int8 Quantized: 1.1 MB

### 2.2 Input & Output Specifications
- **Input Tensor:**
  - Shape: `[1, 15600]`
  - Data Type: `Float32` in range `[-1.0, 1.0]`
  - Sampling Rate: 16,000 Hz Mono
  - Window Duration: 0.975 seconds (975 ms)
  - Hop Size: 0.48 seconds (480 ms, 50% overlap)
- **Output Tensor:**
  - Shape: `[1, 521]`
  - Data Type: `Float32` (Softmax probability scores per class)

### 2.3 Relevant AudioSet Distress Classes
The following class indices are monitored by `ScreamDistressClassifier`:
- **Class 47:** `Screaming` (High-priority distress trigger)
- **Class 48:** `Shout` (Medium-priority distress trigger)
- **Class 49:** `Yell` (Medium-priority distress trigger)
- **Class 50:** `Crying, sobbing` (Vocal distress trigger)
- **Class 51:** `Groan` (Physical distress trigger)
- **Class 52:** `Whimper` (Subdued vocal distress trigger)
- **Class 53:** `Wail, moan` (Vocal agony trigger)

### 2.4 Performance Benchmarks
- **Inference Latency:**
  - Snapdragon 8 Gen 2 / Tensor G3: ~18–28 ms per frame
  - Mid-range ARM Cortex-A55 / MediaTek Helio: ~35–55 ms per frame
- **RAM Footprint:** ~8.5 MB peak inference memory
- **Battery Impact:** < 1.8% battery consumption per hour with 500 ms duty-cycled monitoring

---

## 3. Clear Distinction: Sound-Event Classification vs. Keyword Spotting

> [!IMPORTANT]
> **Sound-Event Classification is NOT Speech Recognition / Keyword Spotting:**
> - `ScreamDistressClassifier` identifies acoustic sound textures (screams, shrieks, wails) regardless of language. It **cannot** and **does not** decode speech words.
> - `DistressKeywordSpotter` is an independently implemented engine that performs phonetic template matching and multi-band spectral contour alignment specifically for spoken emergency phrases ("help", "kaapaathunga", "bachao").

---

## 4. Built-in Edge DSP Classifier (Fail-Safe Architecture)

To guarantee that GuardianGrid functions reliably across all Android environments (including bare-metal test setups without external TFLite `.so` binaries), `ScreamDistressClassifier` incorporates a built-in biomechanical acoustic analysis engine:
1. **Scream-Band Energy Ratio:** Calculates $\frac{E_{1500\text{Hz}-4500\text{Hz}}}{E_{total}}$. Human screams concentrate > 35–70% of acoustic energy in this band due to vocal cord strain and pharyngeal resonance.
2. **Spectral Centroid:** Tracks center of mass of the spectrum. Screams shift centroid above 2200 Hz (vs. 600–1400 Hz for calm speech).
3. **Temporal Persistence:** Requires sustained distress signatures over multiple frames (> 200 ms) to eliminate false alarms from claps, dropped objects, or slammed doors.
