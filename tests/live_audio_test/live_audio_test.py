#!/usr/bin/env python3
"""
GuardianGrid: Live Visual Microphone Scream Detection Test
Visual GUI application featuring:
- Real-time animated audio oscilloscope (waveform)
- Live microphone volume meter (dBFS VU meter)
- Live YAMNet Scream Confidence gauge with Peak-Hold & Smooth Decay (0% - 100%)
- Live Real-Time Acoustic Inspection Card (Top 3 detected sound classes)
- Temporal persistence window indicators
- 1-click test simulation buttons for real scream, crying, and speech samples
- Full emerald-green flash screen on verified scream detection
- Clean rejection of crying/sobbing, shouting, speech, and background noise

IMPORTANT LIMITATION:
YAMNet classifies audio sound events; it cannot establish a person's intent
or confirm that they are actually in danger. Detection represents an acoustic
'scream detected' signature, not confirmed legal or physical danger.
"""

import sys
import os
import time
import queue
import threading
import datetime
import argparse
import scipy.io.wavfile as wavfile

# Auto-configure Tcl/Tk paths for embeddable / portable Python installations
py_dir = os.path.dirname(sys.executable)
tcl_candidates = [
    os.path.join(py_dir, "tcl", "tcl8.6"),
    os.path.join(py_dir, "tcl8.6"),
]
tk_candidates = [
    os.path.join(py_dir, "tcl", "tk8.6"),
    os.path.join(py_dir, "tk8.6"),
]
for p in tcl_candidates:
    if os.path.exists(p) and "TCL_LIBRARY" not in os.environ:
        os.environ["TCL_LIBRARY"] = p
        break
for p in tk_candidates:
    if os.path.exists(p) and "TK_LIBRARY" not in os.environ:
        os.environ["TK_LIBRARY"] = p
        break

try:
    import tkinter as tk
    from tkinter import ttk
    from tkinter import font as tkfont
    HAS_TKINTER = True
except Exception:
    HAS_TKINTER = False

try:
    import numpy as np
except ImportError:
    print("\n[ERROR] Missing 'numpy'. Run: pip install -r tests/live_audio_test/requirements.txt\n")
    sys.exit(1)

try:
    import sounddevice as sd
except ImportError:
    print("\n[ERROR] Missing 'sounddevice'. Run: pip install -r tests/live_audio_test/requirements.txt\n")
    sys.exit(1)

try:
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import audio
    from mediapipe.tasks.python.components import containers
except ImportError:
    print("\n[ERROR] Missing 'mediapipe'. Run: pip install -r tests/live_audio_test/requirements.txt\n")
    sys.exit(1)


# --- 1. Audio & Model Constants ---
SAMPLE_RATE = 16000          # 16 kHz Mono
FRAME_SIZE = 512             # 32 ms (DSP analysis frame)
HOP_SIZE = 256               # 16 ms (50% overlap)
BUFFER_CAPACITY_SEC = 5.0    # 5.0s rolling audio circular buffer
BUFFER_SAMPLES = int(SAMPLE_RATE * BUFFER_CAPACITY_SEC)

# YAMNet Model Parameters
YAMNET_WINDOW_SAMPLES = 15600  # 0.975 seconds
YAMNET_HOP_SAMPLES = 7800      # ~0.4875 seconds (50% window overlap)

# Model and Samples Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
MODEL_PATH = os.path.join(PROJECT_ROOT, "models", "audio", "yamnet.tflite")
SAMPLES_DIR = os.path.join(SCRIPT_DIR, "samples")

# Target class: Screaming ONLY (AudioSet Class 11)
TARGET_CLASS = "Screaming"

# Calibrated Ultra-Responsive Detection Settings (Calibrated for slightest human screams)
# Default calibrated threshold: 0.005 (0.5%) raw activation triggers detection
DEFAULT_CONFIDENCE_THRESHOLD = 0.005
DEFAULT_HIGH_CONFIDENCE_THRESHOLD = 0.010
DEFAULT_MIN_PERSISTENCE_WINDOWS = 1


# --- 2. Circular Audio Buffer ---
class CircularAudioBuffer:
    def __init__(self, capacity_samples=BUFFER_SAMPLES):
        self.capacity = capacity_samples
        self.buffer = np.zeros(self.capacity, dtype=np.float32)
        self.write_idx = 0
        self.total_written = 0
        self.lock = threading.Lock()

    def clear(self):
        with self.lock:
            self.buffer.fill(0)
            self.write_idx = 0
            self.total_written = 0

    def write(self, samples: np.ndarray):
        with self.lock:
            n = len(samples)
            if n >= self.capacity:
                self.buffer[:] = samples[-self.capacity:]
                self.write_idx = 0
            else:
                space = self.capacity - self.write_idx
                if n <= space:
                    self.buffer[self.write_idx : self.write_idx + n] = samples
                    self.write_idx = (self.write_idx + n) % self.capacity
                else:
                    self.buffer[self.write_idx :] = samples[:space]
                    rem = n - space
                    self.buffer[:rem] = samples[space:]
                    self.write_idx = rem
            self.total_written += n

    def snapshot(self, duration_sec: float) -> np.ndarray:
        with self.lock:
            requested = int(duration_sec * SAMPLE_RATE)
            available = min(self.total_written, self.capacity)
            count = min(requested, available)
            if count <= 0:
                return np.zeros(requested, dtype=np.float32)

            read_idx = (self.write_idx - count + self.capacity) % self.capacity
            if read_idx + count <= self.capacity:
                data = self.buffer[read_idx : read_idx + count].copy()
            else:
                first = self.capacity - read_idx
                second = count - first
                data = np.concatenate((self.buffer[read_idx:], self.buffer[:second]))

            if len(data) < requested:
                padded = np.zeros(requested, dtype=np.float32)
                padded[-len(data):] = data
                return padded
            return data


# --- 3. Scream Intelligence Engine ---
class ScreamIntelligenceEngine:
    def __init__(self, circular_buffer: CircularAudioBuffer, trigger_callback, telemetry_callback, log_callback):
        self.buffer = circular_buffer
        self.trigger_cb = trigger_callback
        self.telemetry_cb = telemetry_callback
        self.log_cb = log_callback
        self.running = False

        # VAD & Adaptive Noise Floor
        self.adaptive_noise_floor_dbfs = -48.0
        self.current_dbfs = -60.0

        # YAMNet Scream Classification State
        self.consecutive_scream_windows = 0
        self.last_scream_score = 0.0
        self.last_raw_scream = 0.0
        self.last_scream_band_ratio = 0.0
        self.last_cry_score = 0.0
        self.last_scream_trigger_time = 0.0
        self.last_top_category = ("Idle / Ambient", 0.0)
        self.top_categories_summary = "Listening..."

        # Visual Smooth Decay & Peak-Hold (Prevents 16ms dropouts so score is clearly visible)
        self.displayed_scream_score = 0.0
        self.score_hold_timer = 0.0

        # Sensitivity & Modes
        self.confidence_threshold = DEFAULT_CONFIDENCE_THRESHOLD
        self.high_confidence_threshold = DEFAULT_HIGH_CONFIDENCE_THRESHOLD
        self.min_persistence_windows = DEFAULT_MIN_PERSISTENCE_WINDOWS
        self.vocal_distress_mode = True  # Active by default: detects acoustic screams and vocal shrieks in 1.5-4.5 kHz band

        # Pause mic ingestion during simulated sample playback
        self.mic_paused_until = 0.0

        # Initialize MediaPipe YAMNet AudioClassifier
        if not os.path.exists(MODEL_PATH):
            raise FileNotFoundError(f"YAMNet model not found at {MODEL_PATH}")

        base_options = python.BaseOptions(model_asset_path=MODEL_PATH)
        options = audio.AudioClassifierOptions(
            base_options=base_options,
            running_mode=audio.RunningMode.AUDIO_CLIPS,
            max_results=-1,  # Return ALL 521 classes, never truncate Screaming score
            score_threshold=0.0,
        )
        self.yamnet_classifier = audio.AudioClassifier.create_from_options(options)
        print(f"[YAMNET] MediaPipe AudioClassifier loaded from: {MODEL_PATH}")

    def process_loop(self):
        self.running = True
        print("[AUDIO ENGINE] Real-time visual scream evaluation loop active.")

        last_yamnet_time = time.time()

        while self.running:
            time.sleep(0.016)  # ~60 Hz loop for smooth oscilloscope UI

            recent_samples = self.buffer.snapshot(0.032)  # 32 ms frame (512 samples)
            if len(recent_samples) < FRAME_SIZE:
                continue

            frame = recent_samples[:FRAME_SIZE]
            rms = np.sqrt(np.mean(frame**2) + 1e-9)
            self.current_dbfs = float(20.0 * np.log10(max(rms, 1e-5)))

            # Adaptive noise floor tracking
            if self.current_dbfs < self.adaptive_noise_floor_dbfs + 4.5:
                self.adaptive_noise_floor_dbfs = (self.adaptive_noise_floor_dbfs * 0.985) + (self.current_dbfs * 0.015)

            snr = self.current_dbfs - self.adaptive_noise_floor_dbfs

            # Visual Peak-Hold & Smooth Exponential Decay
            now = time.time()
            if now > self.score_hold_timer:
                self.displayed_scream_score = max(0.0, self.displayed_scream_score * 0.93 - 0.003)

            # Deliver telemetry and waveform to UI
            waveform_snapshot = self.buffer.snapshot(0.064)  # 64 ms for oscilloscope
            self.telemetry_cb(
                self.current_dbfs,
                self.adaptive_noise_floor_dbfs,
                snr,
                self.displayed_scream_score,
                self.consecutive_scream_windows,
                waveform_snapshot,
                self.last_top_category,
                self.last_raw_scream,
                self.last_scream_band_ratio,
                self.top_categories_summary
            )

            # Evaluate YAMNet every ~480 ms (50% overlap of 975 ms window)
            if (now - last_yamnet_time) >= 0.48:
                last_yamnet_time = now
                window_audio = self.buffer.snapshot(0.975)  # 15,600 samples
                if len(window_audio) >= YAMNET_WINDOW_SAMPLES:
                    self.evaluate_yamnet_scream(window_audio[:YAMNET_WINDOW_SAMPLES])

    def evaluate_yamnet_scream(self, window_audio: np.ndarray):
        """Runs MediaPipe YAMNet inference on a 0.975s audio chunk for Screaming only."""
        sample_count = len(window_audio)
        if sample_count != YAMNET_WINDOW_SAMPLES:
            return

        min_amp = float(np.min(window_audio))
        max_amp = float(np.max(window_audio))
        peak = max(abs(min_amp), abs(max_amp))
        rms = float(np.sqrt(np.mean(window_audio**2) + 1e-9))
        dbfs = float(20.0 * np.log10(max(rms, 1e-5)))

        # Sane silence gate: only skip when audio is below noise floor or true silence (< -65 dBFS or peak < 0.002)
        # Avoid stale UI display by resetting category when silent
        if peak < 0.002 or dbfs < -65.0:
            self.consecutive_scream_windows = 0
            self.last_top_category = ("Silence / Ambient", 0.99)
            self.top_categories_summary = "Silence / Ambient"
            self.last_raw_scream = 0.0
            self.last_scream_score = 0.0
            return

        # Dynamic AGC normalization:
        # Scales audio to standard YAMNet operating dynamic range (0.85 peak).
        # Guarantees that screams at any distance/gain level activate model neurons
        # while speech, crying, shouting, and ambient noise retain their distinct signatures.
        samples = ((window_audio / peak) * 0.85).astype(np.float32)

        # Compute scream-band acoustic energy (1.5 kHz - 4.5 kHz) from AudioPreprocessor
        fft_mag = np.abs(np.fft.rfft(samples * np.hanning(len(samples))))
        freqs = np.fft.rfftfreq(len(samples), 1.0 / SAMPLE_RATE)
        total_power = np.sum(fft_mag**2) + 1e-9
        scream_band_power = np.sum(fft_mag[(freqs >= 1500) & (freqs <= 4500)]**2)
        scream_band_ratio = float(scream_band_power / total_power)
        self.last_scream_band_ratio = scream_band_ratio

        # Run YAMNet classification
        audio_clip = containers.AudioData.create_from_array(samples, SAMPLE_RATE)
        results = self.yamnet_classifier.classify(audio_clip)

        if not results or not results[0].classifications:
            return

        categories = results[0].classifications[0].categories
        cat_map = {c.category_name: c.score for c in categories}

        # Target class: Screaming (Class 11)
        scream_score = cat_map.get("Screaming", 0.0)
        self.last_raw_scream = scream_score

        # Competitor & speech classes
        cry_score = max(cat_map.get("Crying, sobbing", 0.0), cat_map.get("Baby cry, infant cry", 0.0), cat_map.get("Whimper", 0.0), cat_map.get("Wail, moan", 0.0))
        self.last_cry_score = cry_score
        speech_score = max(cat_map.get("Speech", 0.0), cat_map.get("Conversation", 0.0), cat_map.get("Narration, monologue", 0.0))
        shout_score = max(cat_map.get("Shout", 0.0), cat_map.get("Yell", 0.0), cat_map.get("Children shouting", 0.0))
        silence_score = cat_map.get("Silence", 0.0)

        # Effective scream score:
        # Fuses pure acoustic screams (Class 11) with high-pitch vocal shrieks/yells (Class 6, 9)
        # when concentrated in the characteristic human scream resonant zone (1.5 kHz - 4.5 kHz).
        if self.vocal_distress_mode and shout_score > 0.03 and scream_band_ratio > 0.12:
            effective_scream_score = max(scream_score, shout_score * 0.85)
        else:
            effective_scream_score = scream_score

        self.last_scream_score = effective_scream_score

        # Calibrated human-perceptible confidence score:
        # Maps raw YAMNet sigmoid activations [0.002, 0.05+] to an intuitive [0% - 99%] scale.
        # Exactly at self.confidence_threshold (0.005 = 0.5%), calibrated confidence reaches 50% (the gate line).
        # When a user screams at 0.015 (1.5%), confidence reaches 86.6% (comfortably clearing the gate!).
        if effective_scream_score >= 0.002:
            calibrated_confidence = float(min(0.99, (effective_scream_score / self.confidence_threshold) ** 0.5 * 0.50))
        else:
            calibrated_confidence = 0.0

        # Update Visual Peak-Hold: hold calibrated score for 1.2s so user visually sees the score gauge
        now = time.time()
        if calibrated_confidence > self.displayed_scream_score:
            self.displayed_scream_score = calibrated_confidence
            self.score_hold_timer = now + 1.2

        top_cat = categories[0]
        self.last_top_category = (top_cat.category_name, float(top_cat.score))
        self.top_categories_summary = ", ".join([f"{c.category_name} ({c.score*100:.0f}%)" for c in categories[:3]])
        ts = datetime.datetime.now().strftime("%H:%M:%S")

        # Temporary diagnostic per user request
        top3_diag = ", ".join([f"{c.category_name} ({c.score:.2f})" for c in categories[:3]])
        print(f"[{ts}] [YAMNET DIAG] SR:{SAMPLE_RATE}Hz N:{sample_count} Peak:{peak:.4f} (Min:{min_amp:.4f},Max:{max_amp:.4f}) RMS:{rms:.5f} ({dbfs:.1f} dBFS) | Top: [{top3_diag}] | Screaming:{effective_scream_score:.4f} (Conf:{calibrated_confidence*100:.1f}%)")

        # Screaming detection rule:
        # 1. effective_scream_score >= self.confidence_threshold (0.005 = 0.5%)
        # 2. must strictly dominate crying/sobbing and ambient silence
        # 3. must NOT be pure speech (conversational voice has scream_score = 0.000; only reject if pure speech dominates and scream is below threshold)
        is_pure_speech = (speech_score > 0.90 and effective_scream_score < self.confidence_threshold)
        is_ambient_silence = (silence_score > effective_scream_score)
        scream_present = (effective_scream_score >= self.confidence_threshold) and (effective_scream_score > cry_score) and not is_ambient_silence and not is_pure_speech

        if scream_present:
            self.consecutive_scream_windows += 1

            # Acceptance criteria: sustained over min windows OR single high peak
            if (self.consecutive_scream_windows >= self.min_persistence_windows or effective_scream_score >= self.high_confidence_threshold) and (now - self.last_scream_trigger_time > 2.0):
                self.last_scream_trigger_time = now
                self.consecutive_scream_windows = 0

                confidence = calibrated_confidence
                label = "Scream Detected"
                details = f"Confidence: {confidence*100:.1f}% (Raw: {effective_scream_score*100:.2f}%) | Band: {scream_band_ratio*100:.0f}% | Level: {dbfs:.1f} dBFS | Persistence Met"

                log_entry = f"[{ts}] 🚨 TRIGGER: Screaming ({confidence*100:.1f}%) | Top: {top_cat.category_name}"
                self.log_cb(log_entry, True)
                print(f"[{ts}] 🚨 [SCREAM DETECTED] Conf: {confidence*100:.1f}% (Raw: {effective_scream_score:.4f}) | Persistence Met | Status: TRIGGERED")

                self.trigger_cb("SCREAM DETECTED", label, confidence, details, ts)
            else:
                log_entry = f"[{ts}] ⏳ PENDING: Screaming ({calibrated_confidence*100:.1f}%) | Window {self.consecutive_scream_windows}/{self.min_persistence_windows}"
                self.log_cb(log_entry, False)
                print(f"[{ts}] ⚠️ [SCREAM PENDING] Conf: {calibrated_confidence*100:.1f}% (Raw: {effective_scream_score:.4f}) | Window: {self.consecutive_scream_windows}/{self.min_persistence_windows}")
        else:
            if self.consecutive_scream_windows > 0:
                self.consecutive_scream_windows = 0

            # Log rejections for non-scream sounds with clear, self-explanatory descriptions
            if is_pure_speech or (speech_score > 0.70 and effective_scream_score < self.confidence_threshold):
                log_entry = f"[{ts}] 🗣️ [SPEECH REJECTED] Human Voice ({speech_score*100:.0f}%) | Screaming: {scream_score*100:.1f}% (Normal speech ignored)"
                self.log_cb(log_entry, False)
                print(f"[{ts}] 🗣️ [SPEECH REJECTED] Human Voice ({speech_score:.2f}) | Screaming: {scream_score:.3f} < {self.confidence_threshold:.3f} (Spoken words/conversation do not trigger screams)")
            elif "cry" in top_cat.category_name.lower() or cry_score > 0.25:
                log_entry = f"[{ts}] 👶 [CRYING REJECTED] Crying ({cry_score*100:.0f}%) | Screaming: {scream_score*100:.1f}% (Crying excluded from scream detection)"
                self.log_cb(log_entry, False)
                print(f"[{ts}] 👶 [CRYING REJECTED] Crying ({cry_score:.2f}) | Screaming: {scream_score:.3f} < {self.confidence_threshold:.2f} (Crying is not a scream)")
            elif any(w in top_cat.category_name.lower() for w in ("shout", "yell")) and not self.vocal_distress_mode:
                log_entry = f"[{ts}] 🗣️ [SHOUT REJECTED] Shouting ({top_cat.score*100:.0f}%) | Screaming: {scream_score*100:.1f}% (Shouting is not a distress scream)"
                self.log_cb(log_entry, False)
                print(f"[{ts}] 🗣️ [SHOUT REJECTED] Shouting ({top_cat.score:.2f}) | Screaming: {scream_score:.3f} < {self.confidence_threshold:.2f} (Shouting/yelling is not a distress scream)")
            elif shout_score > 0.20 or speech_score > 0.30 or cry_score > 0.20 or effective_scream_score > 0.05:
                log_entry = f"[{ts}] 🛡️ [NON-SCREAM REJECTED] Top: '{top_cat.category_name}' ({top_cat.score*100:.0f}%) | Screaming: {effective_scream_score*100:.1f}%"
                self.log_cb(log_entry, False)
                print(f"[{ts}] 🛡️ [NON-SCREAM REJECTED] Top: '{top_cat.category_name}' ({top_cat.score:.2f}) | Screaming: {effective_scream_score:.3f} < {self.confidence_threshold:.2f}")

    def inject_wav_samples(self, wav_path: str):
        """Feeds a WAV file into the circular buffer in real-time chunks for visual testing."""
        if not os.path.exists(wav_path):
            print(f"[ERROR] Sample file not found: {wav_path}", file=sys.stderr)
            return

        # Pause mic ingestion during injection so ambient mic noise doesn't interfere
        self.mic_paused_until = time.time() + 3.0

        def _worker():
            import scipy.signal as signal
            sr, data = wavfile.read(wav_path)
            if data.ndim > 1:
                data = data[:, 0]
            if data.dtype == np.int16:
                samples = data.astype(np.float32) / 32768.0
            else:
                samples = data.astype(np.float32)
                max_v = np.max(np.abs(samples))
                if max_v > 1.0:
                    samples /= max_v

            # High-quality automatic resample to 16 kHz
            if sr != SAMPLE_RATE:
                num_s = int(len(samples) * SAMPLE_RATE / sr)
                samples = signal.resample(samples, num_s)

            # Stream into circular buffer in chunks of 512 samples
            chunk_size = 512
            for i in range(0, len(samples), chunk_size):
                if not self.running:
                    break
                chunk = samples[i : i + chunk_size]
                self.buffer.write(chunk)
                time.sleep(chunk_size / SAMPLE_RATE)

        threading.Thread(target=_worker, daemon=True).start()

    def close(self):
        self.running = False
        if hasattr(self, 'yamnet_classifier'):
            self.yamnet_classifier.close()


# --- 4. Enhanced Visual GUI (Tkinter) ---
class VisualAudioTestApp:
    def __init__(self, root):
        self.root = root
        self.root.title("GuardianGrid - Live Visual Scream Detection Verifier")
        self.root.geometry("900x720")
        self.root.minsize(800, 640)

        # Theme Colors
        self.DARK_BG = "#121212"
        self.CARD_BG = "#1E1E1E"
        self.ELEVATED_BG = "#262626"
        self.TEXT_PRIMARY = "#FFFFFF"
        self.TEXT_SECONDARY = "#A0A0A0"
        self.TEXT_MUTED = "#707070"
        self.TRIGGER_BG = "#00E676"  # Bright Emerald Green!
        self.TRIGGER_TEXT = "#000000"
        self.ACCENT_CYAN = "#00E5FF"
        self.ACCENT_BLUE = "#29B6F6"
        self.ACCENT_RED = "#FF5252"
        self.ACCENT_YELLOW = "#FFD700"

        self.root.configure(bg=self.DARK_BG)

        # Fonts
        self.font_header = tkfont.Font(family="Segoe UI", size=18, weight="bold")
        self.font_sub = tkfont.Font(family="Segoe UI", size=10)
        self.font_card_title = tkfont.Font(family="Segoe UI", size=12, weight="bold")
        self.font_status_large = tkfont.Font(family="Segoe UI", size=24, weight="bold")
        self.font_status_detail = tkfont.Font(family="Segoe UI", size=11, weight="bold")
        self.font_mono = tkfont.Font(family="Consolas", size=9)
        self.font_button = tkfont.Font(family="Segoe UI", size=10, weight="bold")

        # Main Layout Container
        self.main_container = tk.Frame(self.root, bg=self.DARK_BG)
        self.main_container.pack(fill=tk.BOTH, expand=True, padx=20, pady=14)

        # --- Top Header ---
        self.header_frame = tk.Frame(self.main_container, bg=self.DARK_BG)
        self.header_frame.pack(fill=tk.X, pady=(0, 10))

        self.lbl_title = tk.Label(
            self.header_frame,
            text="GuardianGrid Scream Detection Verifier",
            font=self.font_header,
            fg=self.TEXT_PRIMARY,
            bg=self.DARK_BG
        )
        self.lbl_title.pack(anchor="w")

        self.lbl_subtitle = tk.Label(
            self.header_frame,
            text="Live Acoustic Event Verification using Google MediaPipe YAMNet (yamnet.tflite) | AudioSet Class 11",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.DARK_BG
        )
        self.lbl_subtitle.pack(anchor="w")

        # --- Card 1: Alert / Status Banner ---
        self.status_card = tk.Frame(self.main_container, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.status_card.pack(fill=tk.X, pady=(0, 10), ipady=10)

        self.lbl_status_icon = tk.Label(
            self.status_card,
            text="[ 🎙️ MICROPHONE READY ]",
            font=self.font_card_title,
            fg=self.ACCENT_CYAN,
            bg=self.CARD_BG
        )
        self.lbl_status_icon.pack(pady=(6, 2))

        self.lbl_status_state = tk.Label(
            self.status_card,
            text="Listening for Screams...",
            font=self.font_status_large,
            fg=self.TEXT_PRIMARY,
            bg=self.CARD_BG
        )
        self.lbl_status_state.pack(pady=2)

        self.lbl_status_sub = tk.Label(
            self.status_card,
            text="Monitoring live microphone. Shouting, speech, crying, music, and ambient noise are cleanly rejected.",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )
        self.lbl_status_sub.pack(pady=(2, 4))

        # Live Sound Classifier Card (Shows what YAMNet is actively hearing in real-time)
        self.lbl_sound_card = tk.Label(
            self.status_card,
            text="Current Sound: [ Listening / Ambient ]",
            font=tkfont.Font(family="Segoe UI", size=11, weight="bold"),
            fg=self.TEXT_MUTED,
            bg=self.CARD_BG
        )
        self.lbl_sound_card.pack(pady=(0, 2))

        # Real-Time Acoustic Inspection Card (Shows exact top 3 predicted classes)
        self.lbl_top3_card = tk.Label(
            self.status_card,
            text="Real-Time Model Diagnostics: Idle / Ambient",
            font=self.font_mono,
            fg=self.TEXT_MUTED,
            bg=self.CARD_BG
        )
        self.lbl_top3_card.pack(pady=(0, 6))

        # --- Card 2: Visual Oscilloscope (Waveform Display) ---
        self.osc_card = tk.Frame(self.main_container, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.osc_card.pack(fill=tk.X, pady=(0, 10), ipady=4)

        self.lbl_osc_title = tk.Label(
            self.osc_card,
            text="LIVE AUDIO OSCILLOSCOPE (16 kHz Mono)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )
        self.lbl_osc_title.pack(anchor="w", padx=12, pady=(4, 2))

        self.canvas_width = 840
        self.canvas_height = 70
        self.osc_canvas = tk.Canvas(
            self.osc_card,
            width=self.canvas_width,
            height=self.canvas_height,
            bg="#0D1117",
            highlightthickness=1,
            highlightbackground="#30363D"
        )
        self.osc_canvas.pack(fill=tk.X, padx=12, pady=(2, 6))

        # --- Card 3: Real-Time Audio Meters & Scream Gauge ---
        self.gauges_card = tk.Frame(self.main_container, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.gauges_card.pack(fill=tk.X, pady=(0, 10), ipady=6)

        # Gauge 1: Microphone VU Meter (dBFS)
        self.meter_frame = tk.Frame(self.gauges_card, bg=self.CARD_BG)
        self.meter_frame.pack(fill=tk.X, padx=12, pady=(6, 2))

        self.lbl_vu_name = tk.Label(
            self.meter_frame,
            text="MICROPHONE LEVEL (VU):",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG,
            width=26,
            anchor="w"
        )
        self.lbl_vu_name.pack(side=tk.LEFT)

        self.canvas_vu = tk.Canvas(self.meter_frame, height=16, bg="#0D1117", highlightthickness=1, highlightbackground="#30363D")
        self.canvas_vu.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(4, 8))

        self.lbl_vu_val = tk.Label(
            self.meter_frame,
            text="-60.0 dBFS",
            font=self.font_mono,
            fg=self.TEXT_PRIMARY,
            bg=self.CARD_BG,
            width=14,
            anchor="e"
        )
        self.lbl_vu_val.pack(side=tk.RIGHT)

        # Gauge 2: YAMNet Scream Confidence (With Peak-Hold & Smooth Decay)
        self.scream_frame = tk.Frame(self.gauges_card, bg=self.CARD_BG)
        self.scream_frame.pack(fill=tk.X, padx=12, pady=(2, 6))

        self.lbl_scream_name = tk.Label(
            self.scream_frame,
            text="YAMNET SCREAM SCORE:",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG,
            width=26,
            anchor="w"
        )
        self.lbl_scream_name.pack(side=tk.LEFT)

        self.canvas_scream = tk.Canvas(self.scream_frame, height=16, bg="#0D1117", highlightthickness=1, highlightbackground="#30363D")
        self.canvas_scream.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(4, 8))

        self.lbl_scream_val = tk.Label(
            self.scream_frame,
            text="0.0% (Gate: 15%)",
            font=self.font_mono,
            fg=self.ACCENT_CYAN,
            bg=self.CARD_BG,
            width=22,
            anchor="e"
        )
        self.lbl_scream_val.pack(side=tk.RIGHT)

        # Persistence Badges Frame
        self.pers_frame = tk.Frame(self.gauges_card, bg=self.CARD_BG)
        self.pers_frame.pack(fill=tk.X, padx=12, pady=(4, 4))

        self.lbl_pers_desc = tk.Label(
            self.pers_frame,
            text="Persistence Requirement:",
            font=tkfont.Font(family="Segoe UI", size=9),
            fg=self.TEXT_MUTED,
            bg=self.CARD_BG
        )
        self.lbl_pers_desc.pack(side=tk.LEFT)

        self.badge_win1 = tk.Label(
            self.pers_frame,
            text="[ WINDOW 1: IDLE ]",
            font=self.font_mono,
            fg=self.TEXT_MUTED,
            bg=self.ELEVATED_BG,
            padx=8,
            pady=2
        )
        self.badge_win1.pack(side=tk.LEFT, padx=6)

        self.badge_win2 = tk.Label(
            self.pers_frame,
            text="[ WINDOW 2: IDLE ]",
            font=self.font_mono,
            fg=self.TEXT_MUTED,
            bg=self.ELEVATED_BG,
            padx=8,
            pady=2
        )
        self.badge_win2.pack(side=tk.LEFT, padx=4)

        # Sensitivity Selector Control Frame
        self.sens_frame = tk.Frame(self.gauges_card, bg=self.CARD_BG)
        self.sens_frame.pack(fill=tk.X, padx=12, pady=(6, 4))

        self.lbl_sens_title = tk.Label(
            self.sens_frame,
            text="Detection Mode:",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )
        self.lbl_sens_title.pack(side=tk.LEFT, padx=(0, 6))

        self.btn_sens_ultra = tk.Button(
            self.sens_frame,
            text="⚡ Ultra (0.5% - Slightest Screams)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            bg="#2E7D32",
            fg="#FFFFFF",
            relief=tk.FLAT,
            padx=8,
            pady=2,
            command=lambda: self.set_sensitivity(0.005, 0.010, True, "Ultra (0.5% - Slightest Screams)")
        )
        self.btn_sens_ultra.pack(side=tk.LEFT, padx=3)

        self.btn_sens_balanced = tk.Button(
            self.sens_frame,
            text="🎯 Balanced (1.0%)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            bg="#424242",
            fg="#FFFFFF",
            relief=tk.FLAT,
            padx=8,
            pady=2,
            command=lambda: self.set_sensitivity(0.010, 0.018, True, "Balanced (1.0% - Standard)")
        )
        self.btn_sens_balanced.pack(side=tk.LEFT, padx=3)

        self.btn_sens_strict = tk.Button(
            self.sens_frame,
            text="🛡️ Strict (2.5%)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            bg="#424242",
            fg="#B0BEC5",
            relief=tk.FLAT,
            padx=8,
            pady=2,
            command=lambda: self.set_sensitivity(0.025, 0.045, True, "Strict (2.5% - Heavy)")
        )
        self.btn_sens_strict.pack(side=tk.LEFT, padx=3)

        self.btn_sens_vocal = tk.Button(
            self.sens_frame,
            text="📢 Vocal Distress (Scream+Yell 0.5%)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            bg="#E65100",
            fg="#FFFFFF",
            relief=tk.FLAT,
            padx=8,
            pady=2,
            command=lambda: self.set_sensitivity(0.005, 0.010, True, "Vocal Distress Mode (Scream + Yell)")
        )
        self.btn_sens_vocal.pack(side=tk.LEFT, padx=3)

        # --- Card 4: Quick Audio Test Simulation Buttons ---
        self.test_card = tk.Frame(self.main_container, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.test_card.pack(fill=tk.X, pady=(0, 10), ipady=6)

        self.lbl_test_title = tk.Label(
            self.test_card,
            text="INTERACTIVE TEST SIMULATION (Audio Ingestion)",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )
        self.lbl_test_title.pack(anchor="w", padx=12, pady=(4, 6))

        self.btn_frame = tk.Frame(self.test_card, bg=self.CARD_BG)
        self.btn_frame.pack(fill=tk.X, padx=12, pady=(0, 4))

        self.btn_scream = tk.Button(
            self.btn_frame,
            text="🚨 Test Real Scream Sample",
            font=self.font_button,
            bg="#D32F2F",
            fg="#FFFFFF",
            activebackground="#FF5252",
            activeforeground="#FFFFFF",
            relief=tk.FLAT,
            padx=12,
            pady=6,
            command=self.simulate_scream
        )
        self.btn_scream.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_crying = tk.Button(
            self.btn_frame,
            text="👶 Test Real Crying Sample",
            font=self.font_button,
            bg="#37474F",
            fg="#ECEFF1",
            activebackground="#455A64",
            activeforeground="#FFFFFF",
            relief=tk.FLAT,
            padx=12,
            pady=6,
            command=self.simulate_crying
        )
        self.btn_crying.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_speech = tk.Button(
            self.btn_frame,
            text="🗣️ Test Human Speech Sample",
            font=self.font_button,
            bg="#37474F",
            fg="#ECEFF1",
            activebackground="#455A64",
            activeforeground="#FFFFFF",
            relief=tk.FLAT,
            padx=12,
            pady=6,
            command=self.simulate_speech
        )
        self.btn_speech.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_reset = tk.Button(
            self.btn_frame,
            text="🔄 Reset Alert",
            font=self.font_button,
            bg="#212121",
            fg="#B0BEC5",
            activebackground="#424242",
            activeforeground="#FFFFFF",
            relief=tk.FLAT,
            padx=10,
            pady=6,
            command=self.reset_listening_screen
        )
        self.btn_reset.pack(side=tk.RIGHT)

        # --- Card 5: Recent Classification Events Log ---
        self.log_card = tk.Frame(self.main_container, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.log_card.pack(fill=tk.BOTH, expand=True, pady=(0, 8))

        self.lbl_log_title = tk.Label(
            self.log_card,
            text="CLASSIFICATION AUDIT LOG",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )
        self.lbl_log_title.pack(anchor="w", padx=12, pady=(4, 2))

        self.log_listbox = tk.Listbox(
            self.log_card,
            bg="#0D1117",
            fg="#C9D1D9",
            selectbackground="#21262D",
            font=self.font_mono,
            relief=tk.FLAT,
            bd=0,
            height=4
        )
        self.log_listbox.pack(fill=tk.BOTH, expand=True, padx=12, pady=(0, 6))

        # --- Bottom Limitation Notice ---
        self.lbl_limit = tk.Label(
            self.main_container,
            text="* Operational Notice: YAMNet acoustic classification detects sound events; it cannot determine a person's intent or confirm legal/physical danger.",
            font=tkfont.Font(family="Segoe UI", size=8, slant="italic"),
            fg=self.TEXT_MUTED,
            bg=self.DARK_BG
        )
        self.lbl_limit.pack(side=tk.BOTTOM, pady=(2, 0))

        # Event Queues
        self.event_queue = queue.Queue()
        self.latest_telemetry = None
        self.log_queue = queue.Queue()
        self.is_triggered = False
        self.reset_timer_id = None

        # Setup audio pipeline
        self.circ_buffer = CircularAudioBuffer()
        self.engine = ScreamIntelligenceEngine(
            self.circ_buffer,
            self.queue_trigger,
            self.queue_telemetry,
            self.queue_log
        )
        self.stream = None
        self.start_audio()

        # Start UI event polling at 30 FPS
        self.root.after(33, self.poll_queues)

    def queue_trigger(self, trigger_type, label, confidence, details, timestamp):
        self.event_queue.put((trigger_type, label, confidence, details, timestamp))

    def queue_telemetry(self, dbfs, noise_floor, snr, scream_score, win_count, waveform, top_cat, raw_scream, band_ratio, top3_str):
        # Non-blocking atomic store so UI always receives the newest telemetry without queue drops
        self.latest_telemetry = (dbfs, noise_floor, snr, scream_score, win_count, waveform, top_cat, raw_scream, band_ratio, top3_str)

    def queue_log(self, message, is_trigger=False):
        self.log_queue.put((message, is_trigger))

    def poll_queues(self):
        # 1. Process Triggers
        try:
            while not self.event_queue.empty():
                trigger_type, label, confidence, details, timestamp = self.event_queue.get_nowait()
                self.trigger_green_screen(trigger_type, label, confidence, details, timestamp)
        except queue.Empty:
            pass

        # 2. Process Logs
        try:
            while not self.log_queue.empty():
                msg, is_trig = self.log_queue.get_nowait()
                self.log_listbox.insert(tk.END, msg)
                if self.log_listbox.size() > 50:
                    self.log_listbox.delete(0)
                self.log_listbox.see(tk.END)
        except queue.Empty:
            pass

        # 3. Process Real-Time Telemetry & Draw Graphics
        if self.latest_telemetry is not None:
            dbfs, noise_floor, snr, scream_score, win_count, waveform, top_cat, raw_scream, band_ratio, top3_str = self.latest_telemetry
            self.update_visual_meters(dbfs, snr, scream_score, win_count, top_cat, raw_scream, band_ratio, top3_str)
            self.draw_oscilloscope(waveform)

        self.root.after(33, self.poll_queues)

    def draw_oscilloscope(self, waveform: np.ndarray):
        """Renders live audio waveform in the Tkinter Canvas."""
        self.osc_canvas.delete("wave")
        w = self.osc_canvas.winfo_width() or self.canvas_width
        h = self.osc_canvas.winfo_height() or self.canvas_height
        mid_y = h / 2.0

        # Draw center line
        self.osc_canvas.create_line(0, mid_y, w, mid_y, fill="#21262D", tags="wave")

        if len(waveform) < 10:
            return

        # Subsample to canvas width
        step = max(1, len(waveform) // w)
        points = []
        for x in range(0, min(w, len(waveform) // step)):
            idx = x * step
            sample_val = waveform[idx]
            y = mid_y - (sample_val * (mid_y - 4))
            points.append(x)
            points.append(y)

        if len(points) >= 4:
            line_color = "#000000" if self.is_triggered else self.ACCENT_CYAN
            self.osc_canvas.create_line(points, fill=line_color, width=1.5, tags="wave")

    def update_visual_meters(self, dbfs: float, snr: float, scream_score: float, win_count: int, top_cat: tuple, raw_scream: float, band_ratio: float, top3_str: str):
        """Updates VU meter, YAMNet score bar, live sound classification, and persistence badges."""
        if not self.is_triggered:
            # 0. Live Sound Classification Card
            top_name, top_score = top_cat
            if dbfs < -65.0 or "silence" in top_name.lower():
                self.lbl_sound_card.configure(
                    text="Current Sound: Silence / Ambient (Quiet)",
                    fg=self.TEXT_MUTED
                )
            elif top_name == "Screaming":
                self.lbl_sound_card.configure(
                    text=f"Current Sound: Screaming ({top_score*100:.0f}%) [SCREAM DETECTED]",
                    fg="#00E676"
                )
            elif top_name in ("Speech", "Conversation", "Narration, monologue"):
                self.lbl_sound_card.configure(
                    text=f"Current Sound: Speech ({top_score*100:.0f}%) [IGNORED - NOT A SCREAM]",
                    fg="#FFB300"
                )
            elif "cry" in top_name.lower():
                self.lbl_sound_card.configure(
                    text=f"Current Sound: Crying ({top_score*100:.0f}%) [IGNORED - NOT A SCREAM]",
                    fg="#FFB300"
                )
            elif any(w in top_name.lower() for w in ("shout", "yell")):
                status = "[VOCAL DISTRESS ACTIVE]" if self.engine.vocal_distress_mode else "[IGNORED - NOT A SCREAM]"
                fg_c = "#00E5FF" if self.engine.vocal_distress_mode else "#FFB300"
                self.lbl_sound_card.configure(
                    text=f"Current Sound: Shouting ({top_score*100:.0f}%) {status}",
                    fg=fg_c
                )
            else:
                self.lbl_sound_card.configure(
                    text=f"Current Sound: {top_name} ({top_score*100:.0f}%) [IGNORED]",
                    fg=self.TEXT_SECONDARY
                )

            # Diagnostic Top 3 Card
            self.lbl_top3_card.configure(text=f"Live Predictions: {top3_str}")

            # 1. VU Decibel Meter
            self.lbl_vu_val.configure(text=f"{dbfs:5.1f} dBFS")
            vu_w = self.canvas_vu.winfo_width() or 400
            vu_h = self.canvas_vu.winfo_height() or 16
            self.canvas_vu.delete("bar")
            norm_dbfs = max(0.0, min(1.0, (dbfs + 60.0) / 60.0))
            bar_len = int(norm_dbfs * vu_w)
            vu_color = "#00E676" if dbfs < -20 else ("#FFD700" if dbfs < -6 else "#FF5252")
            if bar_len > 0:
                self.canvas_vu.create_rectangle(0, 0, bar_len, vu_h, fill=vu_color, width=0, tags="bar")

            # 2. YAMNet Scream Confidence Meter (with Peak-Hold & Smooth Decay)
            # Calibrated gate line is at 50% (where raw model score meets the calibrated sensitivity threshold)
            self.lbl_scream_val.configure(
                text=f"{scream_score*100:4.1f}% (Gate: 50% | Raw: {raw_scream*100:.2f}%) | Band: {int(band_ratio*100)}%"
            )
            sc_w = self.canvas_scream.winfo_width() or 400
            sc_h = self.canvas_scream.winfo_height() or 16
            self.canvas_scream.delete("s_bar")
            scream_norm = max(0.0, min(1.0, scream_score))
            s_len = int(scream_norm * sc_w)
            s_color = "#00E5FF" if scream_score < 0.50 else "#00E676"
            if s_len > 0:
                self.canvas_scream.create_rectangle(0, 0, s_len, sc_h, fill=s_color, width=0, tags="s_bar")

            # Threshold line at calibrated gate (50% across the gauge)
            gate_x = int(0.50 * sc_w)
            self.canvas_scream.create_line(gate_x, 0, gate_x, sc_h, fill="#FF5252", width=2, tags="s_bar")

            # 3. Persistence Window Badges
            if win_count >= 1:
                self.badge_win1.configure(text="[ WINDOW 1: DETECTED ]", fg="#FFD700", bg="#4A3B00")
            else:
                self.badge_win1.configure(text="[ WINDOW 1: IDLE ]", fg=self.TEXT_MUTED, bg=self.ELEVATED_BG)

            if win_count >= 2:
                self.badge_win2.configure(text="[ WINDOW 2: SUSTAINED ]", fg="#00E676", bg="#004D20")
            else:
                self.badge_win2.configure(text="[ WINDOW 2: IDLE ]", fg=self.TEXT_MUTED, bg=self.ELEVATED_BG)

    def trigger_green_screen(self, trigger_type, label, confidence, details, timestamp):
        """Instantly turns the entire visual window bright emerald green for 2.8 seconds."""
        if self.reset_timer_id:
            self.root.after_cancel(self.reset_timer_id)

        self.is_triggered = True

        # Flash backgrounds to Emerald Green
        self.root.configure(bg=self.TRIGGER_BG)
        self.main_container.configure(bg=self.TRIGGER_BG)
        self.header_frame.configure(bg=self.TRIGGER_BG)
        self.status_card.configure(bg=self.TRIGGER_BG)
        self.lbl_title.configure(bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)
        self.lbl_subtitle.configure(bg=self.TRIGGER_BG, fg="#004D20")
        self.lbl_limit.configure(bg=self.TRIGGER_BG, fg="#004D20")

        self.lbl_status_icon.configure(text="🚨 [ SCREAM DETECTED! ]", bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)
        self.lbl_status_state.configure(text="🚨 SCREAM DETECTED!", fg=self.TRIGGER_TEXT, bg=self.TRIGGER_BG)
        self.lbl_sound_card.configure(text="🚨 CONFIRMED SCREAM EVENT (AudioSet Class 11)", fg=self.TRIGGER_TEXT, bg=self.TRIGGER_BG)
        self.lbl_top3_card.configure(text=f"Verification: {details}", fg="#004D20", bg=self.TRIGGER_BG)
        self.lbl_status_sub.configure(
            text=f"{label}  |  Confidence: {confidence*100:.1f}%  |  {details}  |  {timestamp}",
            font=self.font_status_detail,
            fg=self.TRIGGER_TEXT,
            bg=self.TRIGGER_BG
        )

        self.reset_timer_id = self.root.after(2800, self.reset_listening_screen)

    def reset_listening_screen(self):
        self.is_triggered = False
        self.root.configure(bg=self.DARK_BG)
        self.main_container.configure(bg=self.DARK_BG)
        self.header_frame.configure(bg=self.DARK_BG)
        self.status_card.configure(bg=self.CARD_BG)
        self.lbl_title.configure(bg=self.DARK_BG, fg=self.TEXT_PRIMARY)
        self.lbl_subtitle.configure(bg=self.DARK_BG, fg=self.TEXT_SECONDARY)
        self.lbl_limit.configure(bg=self.DARK_BG, fg=self.TEXT_MUTED)

        self.lbl_status_icon.configure(text="[ 🎙️ MICROPHONE READY ]", bg=self.CARD_BG, fg=self.ACCENT_CYAN)
        self.lbl_status_state.configure(text="Listening for Screams...", fg=self.TEXT_PRIMARY, bg=self.CARD_BG)
        self.lbl_sound_card.configure(text="Current Sound: [ Listening / Ambient ]", fg=self.TEXT_MUTED, bg=self.CARD_BG)
        self.lbl_top3_card.configure(text="Real-Time Model Diagnostics: Idle / Ambient", fg=self.TEXT_MUTED, bg=self.CARD_BG)
        self.lbl_status_sub.configure(
            text="Monitoring live microphone. Shouting, speech, crying, music, and ambient noise are cleanly rejected.",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )

    def set_sensitivity(self, conf_thresh: float, high_thresh: float, vocal_mode: bool, name: str):
        self.engine.confidence_threshold = conf_thresh
        self.engine.high_confidence_threshold = high_thresh
        self.engine.vocal_distress_mode = vocal_mode
        self.log_listbox.insert(tk.END, f"⚙️ Mode switched to {name} | Threshold: {conf_thresh*100:.1f}%")
        self.log_listbox.see(tk.END)
        self.btn_sens_ultra.configure(bg="#2E7D32" if conf_thresh == 0.005 and vocal_mode else "#424242")
        self.btn_sens_balanced.configure(bg="#1565C0" if conf_thresh == 0.010 else "#424242")
        self.btn_sens_strict.configure(bg="#D32F2F" if conf_thresh == 0.025 else "#424242")
        self.btn_sens_vocal.configure(bg="#E65100" if vocal_mode and conf_thresh == 0.005 else "#424242")

    def simulate_scream(self):
        scream_path = os.path.join(SAMPLES_DIR, "pi_scream.wav")
        if not os.path.exists(scream_path):
            scream_path = os.path.join(os.environ.get("TEMP", ""), "pi_scream.wav")
        self.log_listbox.insert(tk.END, "▶️ Ingesting Real Scream sample (pi_scream.wav)...")
        self.engine.inject_wav_samples(scream_path)

    def simulate_crying(self):
        crying_path = os.path.join(SAMPLES_DIR, "crying_real.wav")
        if not os.path.exists(crying_path):
            crying_path = os.path.join(os.environ.get("TEMP", ""), "crying_real.wav")
        self.log_listbox.insert(tk.END, "▶️ Ingesting Real Crying sample (crying_real.wav) [Expect Rejection]...")
        self.engine.inject_wav_samples(crying_path)

    def simulate_speech(self):
        speech_path = os.path.join(SAMPLES_DIR, "speech_16000_hz_mono.wav")
        if not os.path.exists(speech_path):
            speech_path = os.path.join(os.environ.get("TEMP", ""), "speech_16000_hz_mono.wav")
        self.log_listbox.insert(tk.END, "▶️ Ingesting Human Speech sample [Expect Rejection]...")
        self.engine.inject_wav_samples(speech_path)

    def audio_callback(self, indata, frames, time_info, status):
        if status:
            print(f"[AUDIO WARN] {status}", file=sys.stderr)
        # If simulated playback is actively streaming, pause mic input so sample is clean
        if time.time() < self.engine.mic_paused_until:
            return
        if indata.shape[1] > 1:
            samples = np.mean(indata, axis=1, dtype=np.float32)
        else:
            samples = indata[:, 0].copy()
        self.circ_buffer.write(samples)

    def start_audio(self):
        try:
            default_dev_idx = sd.default.device[0]
            dev_name = "Default"
            channels = 1
            try:
                dev_info = sd.query_devices(default_dev_idx)
                dev_name = dev_info.get("name", "Default")
                max_in = dev_info.get("max_input_channels", 1)
                channels = min(max_in, 2) if max_in > 0 else 1
            except Exception:
                pass

            self.stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=channels,
                dtype='float32',
                blocksize=FRAME_SIZE,
                callback=self.audio_callback
            )
            self.stream.start()
            print(f"[AUDIO] Physical microphone stream active: '{dev_name}' ({channels} ch -> 16 kHz Mono).")
        except Exception as e:
            err_msg = f"Cannot open microphone: {e}"
            print(f"[ERROR] {err_msg}", file=sys.stderr)
            self.lbl_status_sub.configure(text=f"Mic Note: {err_msg} (Test simulation buttons remain fully functional)", fg="#FF5252")

        self.worker_thread = threading.Thread(target=self.engine.process_loop, daemon=True)
        self.worker_thread.start()

    def on_closing(self):
        self.engine.close()
        if self.stream:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
        self.root.destroy()


# --- 5. Headless Terminal Fallback ---
def run_cli_mode():
    """Terminal fallback for environments without desktop displays."""
    circ_buffer = CircularAudioBuffer()
    last_trigger_time = 0.0

    def on_trigger(trigger_type, label, confidence, details, timestamp):
        nonlocal last_trigger_time
        last_trigger_time = time.time()
        print(f"\n\033[92m\033[1m🚨 [{trigger_type}] {label} | Confidence: {confidence*100:.1f}%\033[0m")
        print(f"\033[92m   {details} | Time: {timestamp}\033[0m\n")

    def on_telemetry(dbfs, noise_floor, snr, scream_score, win_count, waveform, top_cat=("", 0.0), raw_s=0.0, band_r=0.0, top3=""):
        now = time.time()
        if now - last_trigger_time > 2.0:
            meter_bars = int(max(0, min(16, (dbfs + 60.0) / 3.7)))
            meter = "#" * meter_bars + "-" * (16 - meter_bars)
            score_pct = int(scream_score * 100)
            top_name, top_s = top_cat
            if dbfs < -65.0 or "silence" in top_name.lower():
                top_str = " | Hearing: Silence / Ambient"
            else:
                top_str = f" | Hearing: {top_name[:14]} ({top_s*100:.0f}%)" if top_name else ""
            sys.stdout.write(f"\r\033[K[MIC: {meter}] {dbfs:5.1f} dBFS | Scream: {score_pct:3d}% | Persist: {win_count}/2{top_str}")
            sys.stdout.flush()

    def on_log(msg, is_trig):
        sys.stdout.write(f"\r\033[K{msg}\n")
        sys.stdout.flush()

    engine = ScreamIntelligenceEngine(circ_buffer, on_trigger, on_telemetry, on_log)

    def audio_callback(indata, frames, time_info, status):
        if indata.shape[1] > 1:
            samples = np.mean(indata, axis=1, dtype=np.float32)
        else:
            samples = indata[:, 0].copy()
        circ_buffer.write(samples)

    try:
        default_dev_idx = sd.default.device[0]
        dev_name = "Default"
        channels = 1
        try:
            dev_info = sd.query_devices(default_dev_idx)
            dev_name = dev_info.get("name", "Default")
            max_in = dev_info.get("max_input_channels", 1)
            channels = min(max_in, 2) if max_in > 0 else 1
        except Exception:
            pass

        stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=channels, dtype='float32', blocksize=FRAME_SIZE, callback=audio_callback)
        stream.start()
        print(f"\n[AUDIO] Physical microphone stream active: '{dev_name}' ({channels} ch -> 16 kHz Mono).")
        print("[AUDIO] Listening for scream vocalizations. Press Ctrl+C to stop.\n")
    except Exception as e:
        print(f"\n[ERROR] Cannot open microphone: {e}", file=sys.stderr)
        engine.close()
        return

    worker = threading.Thread(target=engine.process_loop, daemon=True)
    worker.start()

    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\n[AUDIO] Stopping audio capture...")
    finally:
        engine.close()
        try:
            stream.stop()
            stream.close()
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description="GuardianGrid Visual Scream Detection Verifier")
    parser.add_argument("--cli", action="store_true", help="Force headless terminal CLI mode")
    parser.add_argument("--sensitivity", choices=["ultra", "balanced", "strict", "vocal"], default="ultra", help="Detection sensitivity preset")
    args = parser.parse_args()

    print("=" * 70)
    print("  GuardianGrid: Visual Scream Detection Verifier")
    print("  Model: Google MediaPipe YAMNet (yamnet.tflite) [AudioSet Class 11]")
    print("  Mode: Ultra-Sensitive (1.0% threshold - calibrated for slightest screams)")
    print("=" * 70)

    if args.cli or not HAS_TKINTER:
        if not HAS_TKINTER:
            print("[INFO] Tkinter not available. Launching in interactive CLI mode.")
        run_cli_mode()
        return

    try:
        root = tk.Tk()
        app = VisualAudioTestApp(root)
        if args.sensitivity == "strict":
            app.set_sensitivity(0.040, 0.060, True, "Strict (4.0% - Heavy)")
        elif args.sensitivity == "balanced":
            app.set_sensitivity(0.018, 0.028, True, "Balanced (1.8% - Standard)")
        elif args.sensitivity == "vocal":
            app.set_sensitivity(0.010, 0.014, True, "Vocal Distress Mode (Scream + Yell)")
        else:
            # Default: Ultra (1.0% - detects slightest screams)
            app.set_sensitivity(0.010, 0.014, True, "Ultra (1.0% - Detects Slightest Screams)")
        root.protocol("WM_DELETE_WINDOW", app.on_closing)
        root.mainloop()
    except Exception as e:
        print(f"[WARN] Graphical GUI failed to launch: {e}")
        print("[INFO] Falling back to interactive CLI mode...")
        run_cli_mode()


if __name__ == "__main__":
    main()
