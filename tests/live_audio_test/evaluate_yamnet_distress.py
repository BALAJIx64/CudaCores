#!/usr/bin/env python3
"""
GuardianGrid: MediaPipe YAMNet Vocal Distress Benchmark & Calibration Script
Evaluates YAMNet (yamnet.tflite) inference via MediaPipe Audio Classifier API
across positive distress vocalizations and challenging negative acoustic events.

IMPORTANT LIMITATION:
YAMNet classifies audio sound events; it cannot establish a person's intent
or confirm that they are actually in danger. Detection represents a 'possible
distress vocalization' signature, not confirmed legal or physical distress.
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

# Distressed vocalization classes in AudioSet (YAMNet)
DISTRESS_CLASSES = {
    "Screaming": 11,
    "Crying, sobbing": 19,
    "Baby cry, infant cry": 20,
    "Whimper": 21,
    "Wail, moan": 22,
    "Groan": 33,
}

# Non-distress classes that must be reported if active
NON_DISTRESS_CLASSES = {
    "Speech": 0,
    "Shout": 6,
    "Yell": 9,
    "Children shouting": 10,
    "Music": 132,
    "Silence": 494,
    "Noise": 496,
    "Sound effect": 498,
}

SAMPLE_RATE = 16000
WINDOW_SAMPLES = 15600  # 0.975 seconds

# Calibrated detection parameters:
# Screaming / crying in real recordings produces scores >= 0.35
CONFIDENCE_THRESHOLD = 0.35
# Single high-confidence peak threshold
HIGH_CONFIDENCE_THRESHOLD = 0.50
# Minimum persistence required (number of consecutive windows)
MIN_PERSISTENCE_WINDOWS = 2


class YamnetDistressEvaluator:
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
        """Runs YAMNet inference on a 1D float32 audio waveform."""
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
        consecutive_distress = 0
        overall_distress_detected = False
        top_distress_class = None
        max_distress_score = 0.0

        for w_idx, window in enumerate(results):
            categories = window.classifications[0].categories
            cat_dict = {c.category_name: c.score for c in categories}

            # Extract distress scores
            distress_hits = {
                name: cat_dict.get(name, 0.0)
                for name in DISTRESS_CLASSES
                if name in cat_dict
            }
            w_max_distress = max(distress_hits.values()) if distress_hits else 0.0
            w_best_distress_class = (
                max(distress_hits, key=distress_hits.get) if distress_hits else None
            )

            # Check if distress threshold exceeded in this window
            distress_present = w_max_distress >= CONFIDENCE_THRESHOLD
            if distress_present:
                consecutive_distress += 1
                if w_max_distress > max_distress_score:
                    max_distress_score = w_max_distress
                    top_distress_class = w_best_distress_class
            else:
                consecutive_distress = 0

            # Persistence criterion: sustained over >= 2 windows OR single peak >= 0.50
            if (consecutive_distress >= MIN_PERSISTENCE_WINDOWS) or (w_max_distress >= HIGH_CONFIDENCE_THRESHOLD):
                overall_distress_detected = True

            window_evaluations.append({
                "window_index": w_idx,
                "top_categories": [(c.category_name, round(c.score, 4)) for c in categories[:4]],
                "distress_score": round(w_max_distress, 4),
                "distress_class": w_best_distress_class,
                "distress_present": distress_present,
                "consecutive_distress_windows": consecutive_distress,
            })

        return {
            "distress_detected": overall_distress_detected,
            "top_distress_class": top_distress_class,
            "max_distress_score": round(max_distress_score, 4),
            "num_windows": len(results),
            "window_details": window_evaluations,
        }

    def close(self):
        self.classifier.close()


def generate_synthetic_test_signals():
    """Generates synthetic acoustic test waveforms for various scenarios."""
    sr = SAMPLE_RATE
    duration = 2.0  # 2 seconds
    t = np.linspace(0, duration, int(sr * duration), endpoint=False, dtype=np.float32)

    signals = {}

    # 1. Silence / Quiet room noise
    signals["Silence"] = np.zeros_like(t)

    # 2. HVAC / Fan rumble (low-frequency noise + 60 Hz hum)
    fan = 0.08 * np.sin(2 * np.pi * 60 * t) + 0.05 * np.random.normal(0, 1, len(t)).astype(np.float32)
    # Low-pass filter < 600 Hz
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
    print("GuardianGrid: MediaPipe YAMNet Vocal Distress Evaluation Benchmark")
    print(f"Model: {MODEL_PATH}")
    print(f"Confidence Threshold: {CONFIDENCE_THRESHOLD:.2f} | Min Persistence: {MIN_PERSISTENCE_WINDOWS} windows")
    print("=" * 75)

    evaluator = YamnetDistressEvaluator()

    # Track results
    positive_results = []
    negative_results = []

    # --- A. Positive Distress Audio Samples ---
    temp_dir = os.environ.get("TEMP", "")
    real_scream_path = os.path.join(temp_dir, "pi_scream.wav")
    real_crying_path = os.path.join(temp_dir, "crying_real.wav")

    test_positives = {}
    if os.path.exists(real_scream_path):
        sr, data = wavfile.read(real_scream_path)
        test_positives["Real Human Scream Recording (pi_scream.wav)"] = (data, sr)
    if os.path.exists(real_crying_path):
        sr, data = wavfile.read(real_crying_path)
        test_positives["Real Infant/Adult Crying Recording (crying_real.wav)"] = (data, sr)

    print("\n--- 1. POSITIVE DISTRESS VOCALIZATIONS ---")
    for name, (data, sr) in test_positives.items():
        if data.ndim > 1:
            data = data[:, 0]
        samples = data.astype(np.float32) / (32768.0 if data.dtype == np.int16 else np.max(np.abs(data)))
        res = evaluator.classify_waveform(samples, sr)
        status = "PASSED (Detected)" if res["distress_detected"] else "FAILED (Missed)"
        print(f"[{status}] {name}")
        print(f"  Top Distress Class: {res['top_distress_class']} (Score: {res['max_distress_score']:.4f})")
        print(f"  Windows Evaluated: {res['num_windows']}")
        for w in res["window_details"]:
            print(f"    Window {w['window_index']}: {w['top_categories']} | Distress: {w['distress_class']}={w['distress_score']}")
        positive_results.append({
            "name": name,
            "detected": res["distress_detected"],
            "max_score": res["max_distress_score"],
            "class": res["top_distress_class"]
        })

    # --- B. Negative Non-Distress Audio Samples ---
    test_negatives = {}

    # Real speech file
    real_speech_path = os.path.join(temp_dir, "speech_16000_hz_mono.wav")
    if os.path.exists(real_speech_path):
        sr, data = wavfile.read(real_speech_path)
        test_negatives["Real Continuous Human Speech (speech_16000_hz_mono.wav)"] = (data, sr)

    # Synthetic negative sounds
    synthetic_signals = generate_synthetic_test_signals()
    for name, wave in synthetic_signals.items():
        test_negatives[name] = (wave, SAMPLE_RATE)

    print("\n--- 2. NEGATIVE ACOUSTIC SCENARIOS (NON-DISTRESS) ---")
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
        # For negative tests: distress_detected == False is a PASS
        status = "PASSED (Rejected)" if not res["distress_detected"] else "FAILED (False Trigger)"
        print(f"[{status}] {name}")
        print(f"  Distress Triggered: {res['distress_detected']} | Max Distress Score: {res['max_distress_score']:.4f}")
        for w in res["window_details"][:2]:
            print(f"    Window {w['window_index']}: {w['top_categories']}")
        negative_results.append({
            "name": name,
            "false_trigger": res["distress_detected"],
            "max_score": res["max_distress_score"],
            "class": res["top_distress_class"]
        })

    evaluator.close()

    # --- Summary Report ---
    print("\n" + "=" * 75)
    print("BENCHMARK SUMMARY RESULTS")
    print("=" * 75)
    pos_total = len(positive_results)
    pos_passed = sum(1 for p in positive_results if p["detected"])
    missed_detections = pos_total - pos_passed

    neg_total = len(negative_results)
    neg_passed = sum(1 for n in negative_results if not n["false_trigger"])
    false_triggers = neg_total - neg_passed

    print(f"Positive Distress Trials: {pos_passed}/{pos_total} detected ({missed_detections} missed)")
    print(f"Negative Non-Distress Trials: {neg_passed}/{neg_total} rejected ({false_triggers} false triggers)")

    print("\nMissed Detections:")
    if missed_detections == 0:
        print("  None. All authentic distress vocalizations successfully detected.")
    else:
        for p in positive_results:
            if not p["detected"]:
                print(f"  - {p['name']}: Max score {p['max_score']:.4f}")

    print("\nFalse Triggers:")
    if false_triggers == 0:
        print("  None. 100% of non-distress sounds cleanly rejected.")
    else:
        for n in negative_results:
            if n["false_trigger"]:
                print(f"  - {n['name']}: Triggered on {n['class']} with score {n['max_score']:.4f}")

    print("\nImportant Operational Limitation:")
    print("  YAMNet classifies audio acoustic event categories from Google AudioSet.")
    print("  It CANNOT establish a person's intent or confirm legal or physical danger.")
    print("  Detections should be presented as 'Possible Distress Vocalization'.")
    print("=" * 75)


if __name__ == "__main__":
    run_full_benchmark()
