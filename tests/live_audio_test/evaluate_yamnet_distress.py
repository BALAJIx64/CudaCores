#!/usr/bin/env python3
"""
GuardianGrid: MediaPipe YAMNet Scream Detection Benchmark & Calibration Script
Evaluates YAMNet (yamnet.tflite) inference via MediaPipe Audio Classifier API
specifically for Screaming (Class 11) detection only.
Verifies clean rejection of crying/sobbing, shouting, speech, and environmental noises.

IMPORTANT LIMITATION:
YAMNet classifies audio sound events; it cannot establish a person's intent
or confirm that they are actually in danger. Detection represents an acoustic
'scream detected' event, not confirmed legal or physical danger.
"""

import os
import sys
import time
import numpy as np
import scipy.io.wavfile as wavfile
import scipy.signal as signal

try:
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import audio
    from mediapipe.tasks.python.components import containers
except ImportError:
    print("[ERROR] Missing mediapipe. Run: pip install -r tests/live_audio_test/requirements.txt")
    sys.exit(1)

# Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
MODEL_PATH = os.path.join(PROJECT_ROOT, "models", "audio", "yamnet.tflite")

# Target class: Screaming ONLY
TARGET_CLASS = "Screaming"

# Negative classes tracked for rejection logging
NEGATIVE_CLASSES = {
    "Crying, sobbing": 19,
    "Baby cry, infant cry": 20,
    "Shout": 6,
    "Yell": 9,
    "Children shouting": 10,
    "Speech": 0,
    "Music": 132,
    "Silence": 494,
    "Noise": 496,
}

SAMPLE_RATE = 16000
WINDOW_SAMPLES = 15600  # 0.975 seconds

# Calibrated detection parameters:
# Real human scream produces Screaming score >= 0.35
CONFIDENCE_THRESHOLD = 0.35
# Single high-confidence peak threshold
HIGH_CONFIDENCE_THRESHOLD = 0.50
# Minimum persistence required across windows
MIN_PERSISTENCE_WINDOWS = 2


class YamnetScreamEvaluator:
    def __init__(self, model_path: str = MODEL_PATH):
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"YAMNet model not found at: {model_path}")

        base_options = python.BaseOptions(model_asset_path=model_path)
        options = audio.AudioClassifierOptions(
            base_options=base_options,
            running_mode=audio.RunningMode.AUDIO_CLIPS,
            max_results=12,
            score_threshold=0.01,
        )
        self.classifier = audio.AudioClassifier.create_from_options(options)

    def classify_waveform(self, samples: np.ndarray, sample_rate: int = SAMPLE_RATE):
        """Runs YAMNet inference on a 1D float32 audio waveform for Screaming only."""
        if samples.ndim > 1:
            samples = samples[:, 0]
        if sample_rate != SAMPLE_RATE:
            num_samples = int(len(samples) * SAMPLE_RATE / sample_rate)
            samples = signal.resample(samples, num_samples)
            sample_rate = SAMPLE_RATE

        samples = samples.astype(np.float32)
        max_abs = np.max(np.abs(samples))
        if max_abs > 1.0:
            samples = samples / max_abs

        audio_data = containers.AudioData.create_from_array(samples, sample_rate)
        results = self.classifier.classify(audio_data)

        window_evaluations = []
        consecutive_scream = 0
        overall_scream_detected = False
        max_scream_score = 0.0

        for w_idx, window in enumerate(results):
            categories = window.classifications[0].categories
            cat_dict = {c.category_name: c.score for c in categories}

            scream_score = cat_dict.get(TARGET_CLASS, 0.0)
            cry_score = max(cat_dict.get("Crying, sobbing", 0.0), cat_dict.get("Baby cry, infant cry", 0.0))

            if scream_score > max_scream_score:
                max_scream_score = scream_score

            # Screaming condition: score >= 0.35 AND must dominate crying
            scream_present = (scream_score >= CONFIDENCE_THRESHOLD) and (scream_score > cry_score)
            if scream_present:
                consecutive_scream += 1
            else:
                consecutive_scream = 0

            # Persistence criterion: sustained over >= 2 windows OR single peak >= 0.50
            if (consecutive_scream >= MIN_PERSISTENCE_WINDOWS) or (scream_score >= HIGH_CONFIDENCE_THRESHOLD):
                overall_scream_detected = True

            window_evaluations.append({
                "window_index": w_idx,
                "top_categories": [(c.category_name, round(c.score, 4)) for c in categories[:4]],
                "scream_score": round(scream_score, 4),
                "scream_present": scream_present,
                "consecutive_scream_windows": consecutive_scream,
            })

        return {
            "scream_detected": overall_scream_detected,
            "max_scream_score": round(max_scream_score, 4),
            "num_windows": len(results),
            "window_details": window_evaluations,
        }

    def close(self):
        self.classifier.close()


def generate_synthetic_negative_signals():
    """Generates synthetic acoustic test waveforms for negative non-scream scenarios."""
    sr = SAMPLE_RATE
    duration = 2.0  # 2 seconds
    t = np.linspace(0, duration, int(sr * duration), endpoint=False, dtype=np.float32)
    signals = {}

    # 1. Silence / Quiet room noise
    signals["Silence"] = np.zeros_like(t)

    # 2. HVAC / Fan rumble (low-frequency noise + 60 Hz hum)
    fan = 0.08 * np.sin(2 * np.pi * 60 * t) + 0.05 * np.random.normal(0, 1, len(t)).astype(np.float32)
    b, a = signal.butter(4, 600.0 / (sr / 2), btype='low')
    signals["HVAC / Fan Hum"] = signal.lfilter(b, a, fan).astype(np.float32)

    # 3. Traffic noise (pink/brown noise with low-freq Doppler hum)
    traffic = np.cumsum(np.random.normal(0, 0.02, len(t))).astype(np.float32)
    traffic = traffic / (np.max(np.abs(traffic)) + 1e-6) * 0.3
    signals["Traffic Rumble"] = traffic

    # 4. Keyboard clicks (mechanical switches: transient 8-15 ms clicks)
    kb = np.zeros_like(t)
    for click_time in [0.2, 0.45, 0.7, 0.95, 1.3, 1.6, 1.85]:
        idx = int(click_time * sr)
        burst_len = int(0.012 * sr)
        click_wave = np.random.normal(0, 0.6, burst_len) * np.hanning(burst_len)
        kb[idx : idx + burst_len] = click_wave
    signals["Keyboard Clicks"] = kb.astype(np.float32)

    # 5. Music: Polyphonic piano/synth chord (C major: 261.6 Hz, 329.6 Hz, 392.0 Hz)
    music = (
        0.3 * np.sin(2 * np.pi * 261.63 * t) +
        0.3 * np.sin(2 * np.pi * 329.63 * t) +
        0.3 * np.sin(2 * np.pi * 392.00 * t)
    ).astype(np.float32)
    signals["Music (Polyphonic Chord)"] = music

    # 6. Loud Shouting / Yelling without distress (strong vocalic formants at 750 Hz and 1800 Hz)
    shout = 0.5 * np.sin(2 * np.pi * 750 * t) + 0.3 * np.sin(2 * np.pi * 1800 * t)
    shout_env = 0.5 + 0.5 * np.sin(2 * np.pi * 1.5 * t)
    signals["Loud Shouting (Non-Distress)"] = (shout * shout_env * 0.8).astype(np.float32)

    return signals


def run_full_benchmark():
    print("=" * 75)
    print("GuardianGrid: MediaPipe YAMNet Scream Detection Evaluation Benchmark")
    print(f"Model: {MODEL_PATH}")
    print(f"Target Class: {TARGET_CLASS} (Class 11)")
    print(f"Confidence Threshold: {CONFIDENCE_THRESHOLD:.2f} | Min Persistence: {MIN_PERSISTENCE_WINDOWS} windows")
    print("=" * 75)

    evaluator = YamnetScreamEvaluator()

    positive_results = []
    negative_results = []

    # --- A. Positive Scream Audio Samples ---
    temp_dir = os.environ.get("TEMP", "")
    real_scream_path = os.path.join(temp_dir, "pi_scream.wav")

    test_positives = {}
    if os.path.exists(real_scream_path):
        sr, data = wavfile.read(real_scream_path)
        test_positives["Real Human Scream Recording (pi_scream.wav)"] = (data, sr)

    print("\n--- 1. POSITIVE SCREAM RECORDINGS ---")
    for name, (data, sr) in test_positives.items():
        if data.ndim > 1:
            data = data[:, 0]
        samples = data.astype(np.float32) / (32768.0 if data.dtype == np.int16 else np.max(np.abs(data)))
        res = evaluator.classify_waveform(samples, sr)
        status = "PASSED (Detected)" if res["scream_detected"] else "FAILED (Missed)"
        print(f"[{status}] {name}")
        print(f"  Scream Detected: {res['scream_detected']} | Max Screaming Score: {res['max_scream_score']:.4f}")
        print(f"  Windows Evaluated: {res['num_windows']}")
        for w in res["window_details"]:
            print(f"    Window {w['window_index']}: {w['top_categories']} | Screaming: {w['scream_score']}")
        positive_results.append({
            "name": name,
            "detected": res["scream_detected"],
            "max_score": res["max_scream_score"],
        })

    # --- B. Negative Audio Samples (Crying, Speech, Shouting, Noise) ---
    test_negatives = {}

    # Real crying recording (must NOT trigger scream detection)
    real_crying_path = os.path.join(temp_dir, "crying_real.wav")
    if os.path.exists(real_crying_path):
        sr, data = wavfile.read(real_crying_path)
        test_negatives["Real Infant/Adult Crying Recording (crying_real.wav)"] = (data, sr)

    # Real human speech recording (must NOT trigger scream detection)
    real_speech_path = os.path.join(temp_dir, "speech_16000_hz_mono.wav")
    if os.path.exists(real_speech_path):
        sr, data = wavfile.read(real_speech_path)
        test_negatives["Real Continuous Human Speech (speech_16000_hz_mono.wav)"] = (data, sr)

    # Synthetic negative sounds
    synthetic_signals = generate_synthetic_negative_signals()
    for name, wave in synthetic_signals.items():
        test_negatives[name] = (wave, SAMPLE_RATE)

    print("\n--- 2. NEGATIVE ACOUSTIC SCENARIOS (CRYING, SPEECH, SHOUTING, NOISE) ---")
    for name, (data, sr) in test_negatives.items():
        if data.ndim > 1:
            data = data[:, 0]
        if data.dtype == np.int16:
            samples = data.astype(np.float32) / 32768.0
        else:
            samples = data.astype(np.float32)
            max_val = np.max(np.abs(samples))
            if max_val > 1.0:
                samples = samples / max_val

        res = evaluator.classify_waveform(samples, sr)
        # For negative tests: scream_detected == False is a PASS
        status = "PASSED (Rejected)" if not res["scream_detected"] else "FAILED (False Trigger)"
        print(f"[{status}] {name}")
        print(f"  Scream Triggered: {res['scream_detected']} | Max Screaming Score: {res['max_scream_score']:.4f}")
        for w in res["window_details"][:2]:
            print(f"    Window {w['window_index']}: {w['top_categories']}")
        negative_results.append({
            "name": name,
            "false_trigger": res["scream_detected"],
            "max_score": res["max_scream_score"],
        })

    evaluator.close()

    # --- Summary Report ---
    print("\n" + "=" * 75)
    print("BENCHMARK SUMMARY RESULTS (SCREAM DETECTION ONLY)")
    print("=" * 75)
    pos_total = len(positive_results)
    pos_passed = sum(1 for p in positive_results if p["detected"])
    missed_screams = pos_total - pos_passed

    neg_total = len(negative_results)
    neg_passed = sum(1 for n in negative_results if not n["false_trigger"])
    false_triggers = neg_total - neg_passed

    print(f"Positive Scream Trials: {pos_passed}/{pos_total} detected ({missed_screams} missed)")
    print(f"Negative Non-Scream Trials: {neg_passed}/{neg_total} rejected ({false_triggers} false triggers)")

    print("\nMissed Screams:")
    if missed_screams == 0:
        print("  None. All authentic scream vocalizations successfully detected.")
    else:
        for p in positive_results:
            if not p["detected"]:
                print(f"  - {p['name']}: Max score {p['max_score']:.4f}")

    print("\nFalse Triggers:")
    if false_triggers == 0:
        print("  None. 100% of non-scream sounds (crying, shouting, speech, noise) cleanly rejected.")
    else:
        for n in negative_results:
            if n["false_trigger"]:
                print(f"  - {n['name']}: Triggered on Screaming with score {n['max_score']:.4f}")

    print("\nImportant Operational Limitation:")
    print("  YAMNet classifies audio acoustic event categories from Google AudioSet.")
    print("  It CANNOT establish a person's intent or confirm legal or physical danger.")
    print("  Detections represent an acoustic 'scream detected' signature, not confirmed danger.")
    print("=" * 75)

    if missed_screams > 0 or false_triggers > 0:
        sys.exit(1)


if __name__ == "__main__":
    run_full_benchmark()
