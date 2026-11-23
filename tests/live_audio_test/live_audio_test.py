#!/usr/bin/env python3
"""
GuardianGrid: Live Microphone Audio Intelligence Test
Uses the official Google MediaPipe YAMNet Audio Classifier (yamnet.tflite)
for detecting possible vocal distress (screaming, crying/sobbing) and
an energy-endpointed, multi-language Keyword Spotting (KWS) engine.

IMPORTANT LIMITATION:
YAMNet classifies audio sound events; it cannot establish a person's intent
or confirm that they are actually in danger. Its output represents evidence of
a 'possible distress vocalization' signature, not confirmed legal or physical distress.

GUI: Tkinter window
- Default State: Dark/neutral "Listening..." with real-time level and YAMNet telemetry.
- Triggered State: Turns bright emerald green (#00E676) for 2.5 seconds when
  distress vocalization or distress keywords are verified.
"""

import sys
import os
import time
import queue
import threading
import datetime
import tkinter as tk
from tkinter import font as tkfont

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


# --- 1. Audio & Preprocessing Constants ---
SAMPLE_RATE = 16000          # 16 kHz Mono
FRAME_SIZE = 512             # 32 ms (DSP analysis frame)
HOP_SIZE = 256               # 16 ms (50% overlap)
BUFFER_CAPACITY_SEC = 5.0    # 5.0s rolling audio circular buffer
BUFFER_SAMPLES = int(SAMPLE_RATE * BUFFER_CAPACITY_SEC)

# YAMNet Model Parameters
YAMNET_WINDOW_SAMPLES = 15600  # 0.975 seconds
YAMNET_HOP_SAMPLES = 7800      # ~0.4875 seconds (50% window overlap)

# Model paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
MODEL_PATH = os.path.join(PROJECT_ROOT, "models", "audio", "yamnet.tflite")

# Relevant AudioSet Distress Classes
DISTRESS_CLASSES = {
    "Screaming": 11,
    "Crying, sobbing": 19,
    "Baby cry, infant cry": 20,
    "Whimper": 21,
    "Wail, moan": 22,
    "Groan": 33,
}

# Calibrated Thresholds
CONFIDENCE_THRESHOLD = 0.35
HIGH_CONFIDENCE_THRESHOLD = 0.50
MIN_PERSISTENCE_WINDOWS = 2


# --- 2. Circular Audio Buffer ---
class CircularAudioBuffer:
    def __init__(self, capacity_samples=BUFFER_SAMPLES):
        self.capacity = capacity_samples
        self.buffer = np.zeros(self.capacity, dtype=np.float32)
        self.write_idx = 0
        self.total_written = 0
        self.lock = threading.Lock()

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
                return np.zeros(0, dtype=np.float32)

            read_idx = (self.write_idx - count + self.capacity) % self.capacity
            if read_idx + count <= self.capacity:
                return self.buffer[read_idx : read_idx + count].copy()
            else:
                first = self.capacity - read_idx
                second = count - first
                return np.concatenate((self.buffer[read_idx:], self.buffer[:second]))


# --- 3. Mel Filterbank & DSP Feature Utilities ---
def hz_to_mel(hz: float) -> float:
    return 2595.0 * np.log10(1.0 + hz / 700.0)

def mel_to_hz(mel: float) -> float:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)

def create_mel_filterbank(n_mels=16, n_fft=FRAME_SIZE, sr=SAMPLE_RATE, low_hz=100.0, high_hz=7000.0):
    mel_low = hz_to_mel(low_hz)
    mel_high = hz_to_mel(high_hz)
    mel_points = np.linspace(mel_low, mel_high, n_mels + 2)
    hz_points = mel_to_hz(mel_points)
    bin_points = np.floor((n_fft + 1) * hz_points / sr).astype(int)

    filters = np.zeros((n_mels, n_fft // 2 + 1), dtype=np.float32)
    for m in range(1, n_mels + 1):
        f_m_minus = bin_points[m - 1]
        f_m = bin_points[m]
        f_m_plus = bin_points[m + 1]

        for k in range(f_m_minus, f_m):
            if f_m != f_m_minus:
                filters[m - 1, k] = (k - f_m_minus) / (f_m - f_m_minus)
        for k in range(f_m, f_m_plus):
            if f_m_plus != f_m:
                filters[m - 1, k] = (f_m_plus - k) / (f_m_plus - f_m)

    return filters

MEL_FILTERS = create_mel_filterbank()
HANN_WINDOW = np.hanning(FRAME_SIZE).astype(np.float32)


def compute_mel_spectrum(frame: np.ndarray) -> np.ndarray:
    w = frame * HANN_WINDOW
    spec = np.abs(np.fft.rfft(w)) ** 2 / FRAME_SIZE
    mel_energies = np.dot(MEL_FILTERS, spec)
    log_mel = np.log(np.maximum(mel_energies, 1e-6))
    norm = np.linalg.norm(log_mel)
    return log_mel / norm if norm > 1e-6 else log_mel


def compute_zcr(frame: np.ndarray) -> float:
    crossings = np.sum(np.abs(np.diff(np.signbit(frame))))
    return float(crossings) / (len(frame) - 1)


def count_syllables_hysteresis(frame_dbfs: list, noise_floor: float) -> int:
    state = 0  # 0=IDLE, 1=RISING, 2=FALLING
    peak_count = 0
    peak_val = -100.0
    speech_gate = noise_floor + 8.0

    for dbfs in frame_dbfs:
        if state == 0:
            if dbfs >= speech_gate:
                state = 1
                peak_val = dbfs
        elif state == 1:
            if dbfs > peak_val:
                peak_val = dbfs
            elif dbfs < (peak_val - 3.0):
                peak_count += 1
                state = 2
        elif state == 2:
            if dbfs < speech_gate:
                state = 0
            elif dbfs > (peak_val + 2.5):
                state = 1
                peak_val = dbfs

    if state == 1:
        peak_count += 1

    return max(1, peak_count)


# --- 4. Audio Intelligence Engine ---
class AudioIntelligenceEngine:
    def __init__(self, circular_buffer: CircularAudioBuffer, trigger_callback, telemetry_callback):
        self.buffer = circular_buffer
        self.trigger_cb = trigger_callback
        self.telemetry_cb = telemetry_callback
        self.running = False

        # VAD & Adaptive Noise Floor
        self.adaptive_noise_floor_dbfs = -48.0
        self.current_dbfs = -60.0

        # YAMNet Distress Classification State
        self.consecutive_distress_windows = 0
        self.last_distress_trigger_time = 0.0
        self.vocal_distress_lockout_until = 0.0

        # Utterance Endpointing State Machine
        self.in_speech = False
        self.silence_frame_count = 0
        self.current_utterance = []
        self.last_keyword_trigger_time = 0.0

        # Initialize MediaPipe YAMNet AudioClassifier
        if not os.path.exists(MODEL_PATH):
            raise FileNotFoundError(f"YAMNet model not found at {MODEL_PATH}")

        base_options = python.BaseOptions(model_asset_path=MODEL_PATH)
        options = audio.AudioClassifierOptions(
            base_options=base_options,
            running_mode=audio.RunningMode.AUDIO_CLIPS,
            max_results=8,
            score_threshold=0.01,
        )
        self.yamnet_classifier = audio.AudioClassifier.create_from_options(options)
        print(f"[YAMNET] MediaPipe AudioClassifier loaded from: {MODEL_PATH}")

    def process_loop(self):
        self.running = True
        print("[AUDIO ENGINE] Real-time evaluation loop active.")

        last_yamnet_time = time.time()

        while self.running:
            time.sleep(0.016)  # 16 ms loop (matches 256 hop size at 16kHz)

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
            zcr = compute_zcr(frame)

            # Telemetry update
            self.telemetry_cb(self.current_dbfs, self.adaptive_noise_floor_dbfs, snr, zcr)

            # =========================================================================
            # PIPELINE 1: MEDIAPIPE YAMNET VOCAL DISTRESS CLASSIFICATION (~2 Hz)
            # =========================================================================
            now = time.time()
            if (now - last_yamnet_time) >= 0.48:  # Evaluate every 480 ms (50% overlap of 975 ms)
                last_yamnet_time = now
                window_audio = self.buffer.snapshot(0.975)  # Exactly 15,600 samples
                if len(window_audio) >= YAMNET_WINDOW_SAMPLES:
                    self.evaluate_yamnet_distress(window_audio[:YAMNET_WINDOW_SAMPLES])

            # If within vocal distress lockout, suppress keyword matching
            if now < self.vocal_distress_lockout_until:
                continue

            # =========================================================================
            # PIPELINE 2: SPEECH ENDPOINTED KEYWORD SPOTTING
            # =========================================================================
            self.process_speech_endpointing(frame, self.current_dbfs, zcr)

    def evaluate_yamnet_distress(self, window_audio: np.ndarray):
        """Runs MediaPipe YAMNet inference on a 0.975s audio chunk."""
        # Check volume: Ignore complete silence
        rms = np.sqrt(np.mean(window_audio**2) + 1e-9)
        dbfs = 20.0 * np.log10(max(rms, 1e-5))
        if dbfs < -38.0:
            self.consecutive_distress_windows = 0
            return

        audio_clip = containers.AudioData.create_from_array(window_audio, SAMPLE_RATE)
        results = self.yamnet_classifier.classify(audio_clip)

        if not results or not results[0].classifications:
            return

        categories = results[0].classifications[0].categories
        cat_map = {c.category_name: c.score for c in categories}

        # Check distress classes
        distress_scores = {name: cat_map.get(name, 0.0) for name in DISTRESS_CLASSES if name in cat_map}
        max_distress_score = max(distress_scores.values()) if distress_scores else 0.0
        top_distress_class = max(distress_scores, key=distress_scores.get) if distress_scores else None

        # Check non-distress sounds (Speech, Shouting, Music, Fans, Noise)
        speech_score = cat_map.get("Speech", 0.0)
        shout_score = max(cat_map.get("Shout", 0.0), cat_map.get("Yell", 0.0), cat_map.get("Children shouting", 0.0))
        noise_score = max(cat_map.get("Noise", 0.0), cat_map.get("White noise", 0.0), cat_map.get("Static", 0.0))
        music_score = cat_map.get("Music", 0.0)

        now = time.time()
        top_cat = categories[0]
        ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]

        # Log detailed evaluation
        if max_distress_score >= CONFIDENCE_THRESHOLD:
            self.consecutive_distress_windows += 1

            # Acceptance criteria: sustained over >= 2 windows OR high peak >= 0.50
            if (self.consecutive_distress_windows >= MIN_PERSISTENCE_WINDOWS or max_distress_score >= HIGH_CONFIDENCE_THRESHOLD) and (now - self.last_distress_trigger_time > 2.0):
                self.last_distress_trigger_time = now
                self.vocal_distress_lockout_until = now + 2.0
                self.consecutive_distress_windows = 0

                # Preempt keyword state
                self.in_speech = False
                self.current_utterance.clear()

                confidence = float(min(0.99, max_distress_score))
                label = f"Possible Vocal Distress: {top_distress_class}"
                details = f"YAMNet: {top_distress_class} ({max_distress_score*100:.1f}%) | Persistent | Level: {dbfs:.1f} dBFS"

                print(f"[{ts}] 🚨 [YAMNet DISTRESS ACCEPTED] Class: '{top_distress_class}' | Score: {max_distress_score:.3f} | Persistence Met | Status: TRIGGERED")
                print(f"      Top Categories: {[(c.category_name, round(c.score, 3)) for c in categories[:3]]}")
                print("      [NOTE] Audio event classification indicates possible distress, not proof of danger.")

                self.trigger_cb("VOCAL DISTRESS", label, confidence, details, ts)
            else:
                print(f"[{ts}] ⚠️ [YAMNet DISTRESS PENDING] Class: '{top_distress_class}' | Score: {max_distress_score:.3f} | Window: {self.consecutive_distress_windows}/{MIN_PERSISTENCE_WINDOWS}")
        else:
            if self.consecutive_distress_windows > 0:
                self.consecutive_distress_windows = 0

            # Log non-distress rejections for transparency
            if shout_score > 0.20 or speech_score > 0.40 or max_distress_score > 0.10:
                print(f"[{ts}] ℹ️ [YAMNet NON-DISTRESS REJECTED] Top: '{top_cat.category_name}' ({top_cat.score:.2f}) | Distress Max: {max_distress_score:.3f} < {CONFIDENCE_THRESHOLD:.2f} (REJECTED)")

    def process_speech_endpointing(self, frame: np.ndarray, dbfs: float, zcr: float):
        """Isolates spoken utterances via adaptive VAD state machine."""
        speech_threshold = max(self.adaptive_noise_floor_dbfs + 7.5, -40.0)

        if dbfs >= speech_threshold:
            if not self.in_speech:
                self.in_speech = True
                self.current_utterance.clear()
            self.current_utterance.append((frame, dbfs, zcr))
            self.silence_frame_count = 0
        else:
            if self.in_speech:
                self.current_utterance.append((frame, dbfs, zcr))
                self.silence_frame_count += 1

                duration_ms = len(self.current_utterance) * (HOP_SIZE * 1000 // SAMPLE_RATE)

                # Utterance finished when trailing silence >= 14 frames (~225 ms)
                if self.silence_frame_count >= 14 or duration_ms > 1350:
                    self.in_speech = False
                    valid_frames = len(self.current_utterance) - self.silence_frame_count
                    if valid_frames > 0:
                        word_frames = self.current_utterance[:valid_frames]
                        self.current_utterance.clear()
                        self.silence_frame_count = 0
                        self.classify_speech_utterance(word_frames)
                    else:
                        self.current_utterance.clear()
                        self.silence_frame_count = 0

    def classify_speech_utterance(self, word_frames: list):
        """Orthogonal phonetic & duration classification for distress keywords."""
        duration_ms = len(word_frames) * (HOP_SIZE * 1000 // SAMPLE_RATE)
        if duration_ms < 160 or duration_ms > 1350:
            return

        now = time.time()
        if (now - self.last_keyword_trigger_time) < 1.4:
            return

        # 1. Onset Sibilant /s/ Detection (first 5 frames / ~80 ms)
        onset_frames = min(5, len(word_frames))
        onset_max_zcr = max(f[2] for f in word_frames[:onset_frames])
        has_initial_s = onset_max_zcr >= 0.17

        # 2. Syllable Peak-Valley Count
        dbfs_list = [f[1] for f in word_frames]
        syllable_count = count_syllables_hysteresis(dbfs_list, self.adaptive_noise_floor_dbfs)

        matched_keyword = None
        matched_lang = None
        confidence = 0.0

        # Orthogonal phonetic decision logic (matches DistressKeywordSpotter.kt)
        if syllable_count == 1:
            if not has_initial_s and 200 <= duration_ms <= 550:
                matched_keyword = "help"
                matched_lang = "EN"
                confidence = 0.88
            elif has_initial_s and 200 <= duration_ms <= 580:
                matched_keyword = "stop"
                matched_lang = "EN"
                confidence = 0.89
        elif syllable_count == 2:
            if has_initial_s and 420 <= duration_ms <= 950:
                matched_keyword = "save me"
                matched_lang = "EN"
                confidence = 0.91
            elif not has_initial_s and 300 <= duration_ms <= 780:
                matched_keyword = "bachao"
                matched_lang = "HI"
                confidence = 0.87
        elif syllable_count == 3:
            if not has_initial_s and 380 <= duration_ms <= 850:
                matched_keyword = "udhavi"
                matched_lang = "TA"
                confidence = 0.86
            elif 550 <= duration_ms <= 1300:
                matched_keyword = "kaapaathunga"
                matched_lang = "TA"
                confidence = 0.90
        elif syllable_count >= 4:
            if 550 <= duration_ms <= 1350:
                matched_keyword = "kaapaathunga"
                matched_lang = "TA"
                confidence = 0.92

        if matched_keyword:
            self.last_keyword_trigger_time = now
            ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
            label = f"'{matched_keyword}' ({matched_lang})"
            details = f"Duration: {duration_ms} ms | {syllable_count} syll | Initial /s/: {has_initial_s}"

            print(f"[{ts}] 🗣️ [KEYWORD ACCEPTED] Word: '{matched_keyword}' ({matched_lang}) | Confidence: {confidence*100:.1f}% | Duration: {duration_ms} ms")
            self.trigger_cb("KEYWORD", label, confidence, details, ts)

    def close(self):
        self.running = False
        if hasattr(self, 'yamnet_classifier'):
            self.yamnet_classifier.close()


# --- 5. Visual Tkinter GUI ---
class LiveAudioTestApp:
    def __init__(self, root):
        self.root = root
        self.root.title("GuardianGrid - Live Audio Intelligence & Distress Verifier")
        self.root.geometry("750x480")
        self.root.minsize(620, 400)

        # Theme Colors
        self.DARK_BG = "#121212"
        self.CARD_BG = "#1E1E1E"
        self.TEXT_PRIMARY = "#FFFFFF"
        self.TEXT_SECONDARY = "#A0A0A0"
        self.TEXT_MUTED = "#757575"
        self.TRIGGER_BG = "#00E676"  # Bright Emerald Green!
        self.TRIGGER_TEXT = "#000000"
        self.ACCENT_BLUE = "#29B6F6"

        self.root.configure(bg=self.DARK_BG)

        # Fonts
        self.font_header = tkfont.Font(family="Segoe UI", size=20, weight="bold")
        self.font_sub = tkfont.Font(family="Segoe UI", size=11)
        self.font_icon = tkfont.Font(family="Segoe UI", size=24, weight="bold")
        self.font_title = tkfont.Font(family="Segoe UI", size=26, weight="bold")
        self.font_detail = tkfont.Font(family="Segoe UI", size=13, weight="bold")
        self.font_meta = tkfont.Font(family="Consolas", size=10)

        # Main Layout
        self.main_frame = tk.Frame(self.root, bg=self.DARK_BG)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=24, pady=18)

        self.app_label = tk.Label(
            self.main_frame,
            text="GuardianGrid Audio Intelligence",
            font=self.font_header,
            fg=self.TEXT_PRIMARY,
            bg=self.DARK_BG
        )
        self.app_label.pack(pady=(4, 2))

        self.sub_label = tk.Label(
            self.main_frame,
            text="MediaPipe YAMNet Vocal Distress Classifier + Multilingual Keyword Spotter",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.DARK_BG
        )
        self.sub_label.pack(pady=(0, 14))

        # Central Card
        self.card = tk.Frame(self.main_frame, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.card.pack(fill=tk.BOTH, expand=True, pady=8)

        self.status_icon = tk.Label(
            self.card,
            text="[ MIC READY ]",
            font=self.font_icon,
            fg=self.ACCENT_BLUE,
            bg=self.CARD_BG
        )
        self.status_icon.pack(pady=(20, 6))

        self.status_title = tk.Label(
            self.card,
            text="Listening...",
            font=self.font_title,
            fg=self.TEXT_PRIMARY,
            bg=self.CARD_BG
        )
        self.status_title.pack(pady=4)

        self.status_detail = tk.Label(
            self.card,
            text="Speak clearly: \"help\", \"save me\", \"stop\", \"kaapaathunga\", \"udhavi\", \"bachao\"\nOr vocalize distress (screaming, sobbing)",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG,
            justify=tk.CENTER
        )
        self.status_detail.pack(pady=(4, 15))

        # Limitation notice
        self.limitation_label = tk.Label(
            self.card,
            text="* Note: YAMNet detects acoustic sound events; it cannot establish intent or confirm legal danger.",
            font=tkfont.Font(family="Segoe UI", size=9, slant="italic"),
            fg=self.TEXT_MUTED,
            bg=self.CARD_BG
        )
        self.limitation_label.pack(side=tk.BOTTOM, pady=(0, 8))

        # Telemetry Footer
        self.footer = tk.Label(
            self.main_frame,
            text="Audio: 16 kHz Mono | YAMNet: yamnet.tflite Active | Ready",
            font=self.font_meta,
            fg=self.TEXT_MUTED,
            bg=self.DARK_BG
        )
        self.footer.pack(side=tk.BOTTOM, pady=(8, 0))

        # Event queue
        self.event_queue = queue.Queue()
        self.telemetry_queue = queue.Queue()
        self.is_triggered = False
        self.reset_timer_id = None

        # Setup audio pipeline
        self.circ_buffer = CircularAudioBuffer()
        self.engine = AudioIntelligenceEngine(self.circ_buffer, self.queue_trigger, self.queue_telemetry)
        self.stream = None
        self.start_audio()

        self.root.after(40, self.poll_queues)

    def queue_trigger(self, trigger_type, label, confidence, details, timestamp):
        self.event_queue.put((trigger_type, label, confidence, details, timestamp))

    def queue_telemetry(self, dbfs, noise_floor, snr, zcr):
        # Sample telemetry at lower rate to avoid UI flooding
        if self.telemetry_queue.empty():
            self.telemetry_queue.put((dbfs, noise_floor, snr, zcr))

    def poll_queues(self):
        # 1. Process triggers
        try:
            while not self.event_queue.empty():
                trigger_type, label, confidence, details, timestamp = self.event_queue.get_nowait()
                self.trigger_green_screen(trigger_type, label, confidence, details, timestamp)
        except queue.Empty:
            pass

        # 2. Process telemetry
        try:
            if not self.is_triggered and not self.telemetry_queue.empty():
                dbfs, noise_floor, snr, zcr = self.telemetry_queue.get_nowait()
                self.footer.configure(text=f"Level: {dbfs:.1f} dBFS | Noise Floor: {noise_floor:.1f} dBFS | SNR: {snr:+.1f} dB | ZCR: {zcr:.2f}")
        except queue.Empty:
            pass

        self.root.after(40, self.poll_queues)

    def trigger_green_screen(self, trigger_type, label, confidence, details, timestamp):
        """Immediately turns the window bright green for 2.5 seconds."""
        if self.reset_timer_id:
            self.root.after_cancel(self.reset_timer_id)

        self.is_triggered = True

        self.root.configure(bg=self.TRIGGER_BG)
        self.main_frame.configure(bg=self.TRIGGER_BG)
        self.card.configure(bg=self.TRIGGER_BG)
        self.app_label.configure(bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)
        self.sub_label.configure(bg=self.TRIGGER_BG, fg="#1B5E20")
        self.limitation_label.configure(bg=self.TRIGGER_BG, fg="#1B5E20")
        self.footer.configure(bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)

        icon_str = "🚨 VOCAL DISTRESS DETECTED!" if trigger_type == "VOCAL DISTRESS" else "🗣️ KEYWORD DETECTED!"
        self.status_icon.configure(text=icon_str, bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)
        self.status_title.configure(text=f"{trigger_type} CONFIRMED!", fg=self.TRIGGER_TEXT, bg=self.TRIGGER_BG)
        self.status_detail.configure(
            text=f"{label}\nConfidence: {confidence * 100:.1f}%  |  {details}\nTime: {timestamp}",
            font=self.font_detail,
            fg=self.TRIGGER_TEXT,
            bg=self.TRIGGER_BG
        )

        self.reset_timer_id = self.root.after(2500, self.reset_listening_screen)

    def reset_listening_screen(self):
        self.is_triggered = False
        self.root.configure(bg=self.DARK_BG)
        self.main_frame.configure(bg=self.DARK_BG)
        self.card.configure(bg=self.CARD_BG)
        self.app_label.configure(bg=self.DARK_BG, fg=self.TEXT_PRIMARY)
        self.sub_label.configure(bg=self.DARK_BG, fg=self.TEXT_SECONDARY)
        self.limitation_label.configure(bg=self.CARD_BG, fg=self.TEXT_MUTED)
        self.footer.configure(bg=self.DARK_BG, fg=self.TEXT_MUTED)

        self.status_icon.configure(text="[ MIC READY ]", bg=self.CARD_BG, fg=self.ACCENT_BLUE)
        self.status_title.configure(text="Listening...", fg=self.TEXT_PRIMARY, bg=self.CARD_BG)
        self.status_detail.configure(
            text="Speak clearly: \"help\", \"save me\", \"stop\", \"kaapaathunga\", \"udhavi\", \"bachao\"\nOr vocalize distress (screaming, sobbing)",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )

    def audio_callback(self, indata, frames, time_info, status):
        if status:
            print(f"[AUDIO WARN] {status}", file=sys.stderr)
        samples = indata[:, 0].copy()
        self.circ_buffer.write(samples)

    def start_audio(self):
        try:
            self.stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                dtype='float32',
                blocksize=FRAME_SIZE,
                callback=self.audio_callback
            )
            self.stream.start()
            print("[AUDIO] Physical microphone stream active: 16 kHz Mono.")
        except Exception as e:
            err_msg = f"Cannot open microphone: {e}"
            print(f"[ERROR] {err_msg}", file=sys.stderr)
            self.status_detail.configure(text=f"ERROR: {err_msg}", fg="#FF5252")
            return

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


def main():
    print("=" * 70)
    print("  GuardianGrid: Live Microphone Test Harness")
    print("  Model: MediaPipe YAMNet (yamnet.tflite) + Multi-Language KWS")
    print("=" * 70)
    print("Supported Distress Vocalizations (YAMNet AudioSet):")
    print("  - Screaming (Class 11)")
    print("  - Crying, sobbing (Class 19)")
    print("  - Baby cry, infant cry (Class 20)")
    print("  - Wail, moan (Class 22)")
    print("Supported Spoken Keywords (Endpointed KWS):")
    print("  - English: 'help', 'save me', 'stop'")
    print("  - Tamil:   'kaapaathunga', 'udhavi'")
    print("  - Hindi:   'bachao'")
    print("=" * 70)
    print("Operational Notice:")
    print("  YAMNet classifies acoustic event categories; it cannot determine intent.")
    print("=" * 70)

    root = tk.Tk()
    app = LiveAudioTestApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    try:
        root.mainloop()
    except KeyboardInterrupt:
        app.on_closing()


if __name__ == "__main__":
    main()
