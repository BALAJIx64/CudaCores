#!/usr/bin/env python3
"""
GuardianGrid: Live Microphone Audio Intelligence Test
Mirrors the exact DSP, acoustic features, scream detection, and keyword spotting logic
from `modules/audio/` (ScreamDistressClassifier, DistressKeywordSpotter, AudioPreprocessor).

GUI: Minimal Tkinter window
- Default: Dark/neutral "Listening..."
- Triggered: Flashes bright green for 2.5 seconds with trigger details, confidence, and timestamp.
"""

import sys
import time
import queue
import threading
import datetime
import tkinter as tk
from tkinter import font as tkfont

try:
    import numpy as np
except ImportError:
    print("\n[ERROR] Missing 'numpy'. Please run: pip install -r tests/live_audio_test/requirements.txt\n")
    sys.exit(1)

try:
    import sounddevice as sd
except ImportError:
    print("\n[ERROR] Missing 'sounddevice'. Please run: pip install -r tests/live_audio_test/requirements.txt\n")
    sys.exit(1)


# --- 1. Audio & Preprocessing Constants (Mirrors AudioPreprocessor.kt) ---
SAMPLE_RATE = 16000          # 16 kHz
FRAME_SIZE = 512             # 32 ms
HOP_SIZE = 256               # 16 ms (50% overlap)
BUFFER_CAPACITY_SEC = 5.0    # 5-second circular buffer
BUFFER_SAMPLES = int(SAMPLE_RATE * BUFFER_CAPACITY_SEC)

SCREAM_BAND_LOW_HZ = 1500.0
SCREAM_BAND_HIGH_HZ = 4500.0


# --- 2. Keyword Profiles (Mirrors DistressKeywordSpotter.kt) ---
KEYWORD_PROFILES = [
    {
        "keyword": "help",
        "language": "en",
        "pattern": [
            [0.2, 0.4, 0.4],  # /h/
            [0.4, 0.5, 0.1],  # /e/
            [0.6, 0.3, 0.1]   # /l-p/
        ]
    },
    {
        "keyword": "save me",
        "language": "en",
        "pattern": [
            [0.1, 0.3, 0.6],  # /s/
            [0.5, 0.4, 0.1],  # /eɪ-v/
            [0.7, 0.2, 0.1]   # /m-i/
        ]
    },
    {
        "keyword": "stop",
        "language": "en",
        "pattern": [
            [0.1, 0.2, 0.7],  # /s/
            [0.5, 0.4, 0.1],  # /ɒ/
            [0.1, 0.1, 0.1]   # /p/
        ]
    },
    {
        "keyword": "kaapaathunga",
        "language": "ta",
        "pattern": [
            [0.3, 0.5, 0.2],  # kaa
            [0.4, 0.5, 0.1],  # paa
            [0.5, 0.4, 0.1],  # thun
            [0.4, 0.4, 0.2]   # ga
        ]
    },
    {
        "keyword": "udhavi",
        "language": "ta",
        "pattern": [
            [0.6, 0.3, 0.1],  # u
            [0.4, 0.5, 0.1],  # dha
            [0.3, 0.5, 0.2]   # vi
        ]
    },
    {
        "keyword": "bachao",
        "language": "hi",
        "pattern": [
            [0.6, 0.3, 0.1],  # ba
            [0.3, 0.5, 0.2]   # chao
        ]
    }
]


# --- 3. Circular Audio Buffer (Mirrors CircularAudioBuffer.kt) ---
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


# --- 4. DSP & Classification Engine ---
class AudioIntelligenceEngine:
    def __init__(self, circular_buffer: CircularAudioBuffer, trigger_callback):
        self.buffer = circular_buffer
        self.trigger_cb = trigger_callback
        self.sensitivity = 0.70  # Sensitivity in [0.0, 1.0]
        
        # State tracking
        self.consecutive_scream_frames = 0
        self.last_scream_time = 0.0
        self.last_keyword_time = 0.0
        self.running = False
        
        self.hann_window = np.hanning(FRAME_SIZE).astype(np.float32)
        self.freqs = np.fft.rfftfreq(FRAME_SIZE, d=1.0 / SAMPLE_RATE)
        self.scream_mask = (self.freqs >= SCREAM_BAND_LOW_HZ) & (self.freqs <= SCREAM_BAND_HIGH_HZ)
        
        # Band masks for keywords
        self.low_mask = (self.freqs >= 100.0) & (self.freqs < 800.0)
        self.mid_mask = (self.freqs >= 800.0) & (self.freqs < 2500.0)
        self.high_mask = (self.freqs >= 2500.0) & (self.freqs <= 6000.0)

    def process_loop(self):
        self.running = True
        print("[AUDIO ENGINE] Real-time detection loop active.")
        
        while self.running:
            time.sleep(0.04)  # ~25 Hz evaluation rate
            
            # 1. Scream Detection on recent 500 ms
            recent_audio = self.buffer.snapshot(0.50)
            if len(recent_audio) >= FRAME_SIZE:
                self.evaluate_scream(recent_audio)
            
            # 2. Keyword Spotting on recent 1.2 seconds
            speech_audio = self.buffer.snapshot(1.20)
            if len(speech_audio) >= FRAME_SIZE * 4:
                self.evaluate_keywords(speech_audio)

    def evaluate_scream(self, samples: np.ndarray):
        frames = []
        for start in range(0, len(samples) - FRAME_SIZE + 1, HOP_SIZE):
            frames.append(samples[start : start + FRAME_SIZE])
        
        if not frames:
            return

        max_frame_score = 0.0
        scream_frames = 0

        for frame in frames:
            rms = np.sqrt(np.mean(frame**2) + 1e-9)
            dbfs = 20.0 * np.log10(max(rms, 1e-5))

            if dbfs < -28.0:
                continue

            windowed = frame * self.hann_window
            power = (np.abs(np.fft.rfft(windowed)) ** 2) / FRAME_SIZE
            total_power = np.sum(power) + 1e-9

            # Spectral Centroid
            centroid = np.sum(self.freqs * power) / total_power
            # Scream band power ratio (1.5 kHz - 4.5 kHz)
            scream_band_power = np.sum(power[self.scream_mask])
            scream_ratio = scream_band_power / total_power

            # Scoring factors
            band_score = np.clip((scream_ratio - 0.25) / 0.45, 0.0, 1.0)
            centroid_score = np.clip((centroid - 1400.0) / 1600.0, 0.0, 1.0)
            energy_score = np.clip((dbfs + 28.0) / 20.0, 0.0, 1.0)

            frame_score = (band_score * 0.40) + (centroid_score * 0.35) + (energy_score * 0.25)

            if frame_score > 0.60:
                scream_frames += 1
            if frame_score > max_frame_score:
                max_frame_score = frame_score

        persistence = min(1.0, float(scream_frames) / max(1, len(frames) / 3))
        total_score = (max_frame_score * 0.75) + (persistence * 0.25)

        # Sensitivity maps: sensitivity 1.0 -> 0.45 threshold, sensitivity 0.0 -> 0.90 threshold
        threshold = 0.90 - (self.sensitivity * 0.45)

        now = time.time()
        if total_score >= threshold:
            self.consecutive_scream_frames += 1
            if self.consecutive_scream_frames >= 2 and (now - self.last_scream_time) > 1.8:
                self.last_scream_time = now
                confidence = float(np.clip(total_score / 0.95, 0.70, 0.99))
                ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
                print(f"[{ts}] 🚨 [SCREAM DETECTED] Confidence: {confidence*100:.1f}% | Score: {total_score:.3f}")
                self.trigger_cb("SCREAM", "Acoustic Scream Distress", confidence, ts)
        else:
            self.consecutive_scream_frames = max(0, self.consecutive_scream_frames - 1)

    def evaluate_keywords(self, samples: np.ndarray):
        rms = np.sqrt(np.mean(samples**2) + 1e-9)
        dbfs = 20.0 * np.log10(max(rms, 1e-5))

        # Voice activity threshold
        if dbfs < -32.0:
            return

        frames = []
        for start in range(0, len(samples) - FRAME_SIZE + 1, HOP_SIZE):
            frames.append(samples[start : start + FRAME_SIZE])

        if len(frames) < 4:
            return

        # Extract 3-band energy profile for each frame
        frame_bands = []
        for f in frames:
            w = f * self.hann_window
            p = (np.abs(np.fft.rfft(w)) ** 2) / FRAME_SIZE
            low_p = np.sum(p[self.low_mask])
            mid_p = np.sum(p[self.mid_mask])
            high_p = np.sum(p[self.high_mask])
            tot = low_p + mid_p + high_p + 1e-9
            frame_bands.append([low_p / tot, mid_p / tot, high_p / tot])

        frame_bands = np.array(frame_bands)

        best_match = None
        best_score = 0.0

        for profile in KEYWORD_PROFILES:
            target = np.array(profile["pattern"])
            score = self.compute_dtw_alignment(frame_bands, target)
            if score > 0.72 and score > best_score:
                best_score = score
                best_match = profile

        now = time.time()
        if best_match and (now - self.last_keyword_time) > 2.0:
            self.last_keyword_time = now
            ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
            kw = best_match["keyword"]
            lang = best_match["language"]
            print(f"[{ts}] 🗣️ [KEYWORD MATCHED] Word: '{kw}' ({lang.upper()}) | Confidence: {best_score*100:.1f}%")
            self.trigger_cb("KEYWORD", f"'{kw}' ({lang.upper()})", best_score, ts)

    def compute_dtw_alignment(self, observed: np.ndarray, target: np.ndarray) -> float:
        n = len(observed)
        m = len(target)
        dtw = np.full((n + 1, m + 1), float('inf'))
        dtw[0, 0] = 0.0

        for i in range(1, n + 1):
            for j in range(1, m + 1):
                cost = np.sum(np.abs(observed[i - 1] - target[j - 1]))
                dtw[i, j] = cost + min(dtw[i - 1, j], dtw[i, j - 1], dtw[i - 1, j - 1])

        avg_dist = dtw[n, m] / (n + m)
        return float(np.clip(1.0 - avg_dist, 0.0, 1.0))


# --- 5. Visual GUI Window (Tkinter) ---
class LiveAudioTestApp:
    def __init__(self, root):
        self.root = root
        self.root.title("GuardianGrid - Live Audio Intelligence Test")
        self.root.geometry("640x420")
        self.root.minsize(520, 340)

        # Theme Colors
        self.DARK_BG = "#121212"
        self.CARD_BG = "#1E1E1E"
        self.TEXT_PRIMARY = "#FFFFFF"
        self.TEXT_SECONDARY = "#A0A0A0"
        self.TRIGGER_BG = "#00E676"  # Bright Green!
        self.TRIGGER_TEXT = "#000000"

        self.root.configure(bg=self.DARK_BG)

        # Fonts
        self.font_header = tkfont.Font(family="Segoe UI", size=20, weight="bold")
        self.font_title = tkfont.Font(family="Segoe UI", size=26, weight="bold")
        self.font_detail = tkfont.Font(family="Segoe UI", size=15, weight="bold")
        self.font_sub = tkfont.Font(family="Segoe UI", size=11)
        self.font_meta = tkfont.Font(family="Consolas", size=10)

        # Main Display Frame
        self.main_frame = tk.Frame(self.root, bg=self.DARK_BG)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=20, pady=20)

        # Title / Subheading
        self.app_label = tk.Label(
            self.main_frame,
            text="GuardianGrid Audio Intelligence",
            font=self.font_header,
            fg=self.TEXT_PRIMARY,
            bg=self.DARK_BG
        )
        self.app_label.pack(pady=(10, 2))

        self.sub_label = tk.Label(
            self.main_frame,
            text="Acoustic Scream Classifier + English/Tamil/Hindi Keyword Spotter",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.DARK_BG
        )
        self.sub_label.pack(pady=(0, 20))

        # Status Card (Turns Bright Green on Trigger)
        self.card = tk.Frame(self.main_frame, bg=self.CARD_BG, relief=tk.FLAT, bd=0)
        self.card.pack(fill=tk.BOTH, expand=True, pady=10)

        self.status_icon = tk.Label(
            self.card,
            text="🎙️",
            font=tkfont.Font(size=36),
            fg=self.TEXT_PRIMARY,
            bg=self.CARD_BG
        )
        self.status_icon.pack(pady=(25, 5))

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
            text="Say: \"help\", \"save me\", \"kaapaathunga\", \"udhavi\", \"bachao\"\nOr produce a loud distress scream",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG,
            justify=tk.CENTER
        )
        self.status_detail.pack(pady=(4, 15))

        # Footer telemetry
        self.footer = tk.Label(
            self.main_frame,
            text="Mic: 16kHz Mono 16-bit PCM | Rolling Buffer: 5.0s | Ready",
            font=self.font_meta,
            fg=self.TEXT_SECONDARY,
            bg=self.DARK_BG
        )
        self.footer.pack(side=tk.BOTTOM, pady=(10, 0))

        # Queue for cross-thread GUI events
        self.event_queue = queue.Queue()
        self.is_triggered = False
        self.reset_timer_id = None

        # Audio Pipeline Setup
        self.circ_buffer = CircularAudioBuffer()
        self.engine = AudioIntelligenceEngine(self.circ_buffer, self.queue_trigger)

        self.stream = None
        self.start_audio_pipeline()

        # Check queue periodically
        self.root.after(50, self.check_event_queue)

    def queue_trigger(self, trigger_type, label, confidence, timestamp):
        self.event_queue.put((trigger_type, label, confidence, timestamp))

    def check_event_queue(self):
        try:
            while not self.event_queue.empty():
                trigger_type, label, confidence, timestamp = self.event_queue.get_nowait()
                self.trigger_green_screen(trigger_type, label, confidence, timestamp)
        except queue.Empty:
            pass
        self.root.after(50, self.check_event_queue)

    def trigger_green_screen(self, trigger_type, label, confidence, timestamp):
        """Immediately turns the window bright green for 2.5 seconds."""
        if self.reset_timer_id:
            self.root.after_cancel(self.reset_timer_id)

        self.is_triggered = True

        # Turn background bright green
        self.root.configure(bg=self.TRIGGER_BG)
        self.main_frame.configure(bg=self.TRIGGER_BG)
        self.card.configure(bg=self.TRIGGER_BG)
        self.app_label.configure(bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)
        self.sub_label.configure(bg=self.TRIGGER_BG, fg="#1B5E20")
        self.footer.configure(bg=self.TRIGGER_BG, fg=self.TRIGGER_TEXT)

        # Trigger Details
        icon_str = "🚨" if trigger_type == "SCREAM" else "🗣️"
        self.status_icon.configure(text=icon_str, bg=self.TRIGGER_BG)
        self.status_title.configure(
            text=f"{trigger_type} DETECTED!",
            fg=self.TRIGGER_TEXT,
            bg=self.TRIGGER_BG
        )
        self.status_detail.configure(
            text=f"Matched: {label}\nConfidence: {confidence * 100:.1f}%  |  Time: {timestamp}",
            font=self.font_detail,
            fg=self.TRIGGER_TEXT,
            bg=self.TRIGGER_BG
        )

        # Reset back to dark "Listening..." after 2.5 seconds
        self.reset_timer_id = self.root.after(2500, self.reset_listening_screen)

    def reset_listening_screen(self):
        self.is_triggered = False
        self.root.configure(bg=self.DARK_BG)
        self.main_frame.configure(bg=self.DARK_BG)
        self.card.configure(bg=self.CARD_BG)
        self.app_label.configure(bg=self.DARK_BG, fg=self.TEXT_PRIMARY)
        self.sub_label.configure(bg=self.DARK_BG, fg=self.TEXT_SECONDARY)
        self.footer.configure(bg=self.DARK_BG, fg=self.TEXT_SECONDARY)

        self.status_icon.configure(text="🎙️", bg=self.CARD_BG, fg=self.TEXT_PRIMARY)
        self.status_title.configure(text="Listening...", fg=self.TEXT_PRIMARY, bg=self.CARD_BG)
        self.status_detail.configure(
            text="Say: \"help\", \"save me\", \"kaapaathunga\", \"udhavi\", \"bachao\"\nOr produce a loud distress scream",
            font=self.font_sub,
            fg=self.TEXT_SECONDARY,
            bg=self.CARD_BG
        )

    def audio_callback(self, indata, frames, time_info, status):
        if status:
            print(f"[AUDIO WARN] {status}", file=sys.stderr)
        samples = indata[:, 0].copy()
        self.circ_buffer.write(samples)

    def start_audio_pipeline(self):
        try:
            self.stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                dtype='float32',
                blocksize=FRAME_SIZE,
                callback=self.audio_callback
            )
            self.stream.start()
            print("[AUDIO] Microphone input stream started successfully (16 kHz Mono).")
        except Exception as e:
            err_msg = f"Cannot open microphone: {e}"
            print(f"[ERROR] {err_msg}", file=sys.stderr)
            self.status_detail.configure(text=f"ERROR: {err_msg}", fg="#FF5252")
            return

        self.worker_thread = threading.Thread(target=self.engine.process_loop, daemon=True)
        self.worker_thread.start()

    def on_closing(self):
        self.engine.running = False
        if self.stream:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
        self.root.destroy()


def main():
    print("=" * 65)
    print("  GuardianGrid: Live Microphone Test Harness")
    print("  Module 1: Scream Classifier & Keyword Spotter")
    print("=" * 65)
    print("Supported Keywords:")
    print("  - English: 'help', 'save me', 'stop'")
    print("  - Tamil:   'kaapaathunga', 'udhavi'")
    print("  - Hindi:   'bachao'")
    print("  - Screams: High-pitch acoustic vocal distress (>1500 Hz)")
    print("=" * 65)

    root = tk.Tk()
    app = LiveAudioTestApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    try:
        root.mainloop()
    except KeyboardInterrupt:
        app.on_closing()


if __name__ == "__main__":
    main()
