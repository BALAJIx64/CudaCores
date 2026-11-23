# GuardianGrid: Live Microphone Audio Intelligence Test

This standalone desktop tool performs real-world, live verification of GuardianGrid's Audio Intelligence module using your PC's physical microphone.

---

## What Was Redesigned & Fixed

1. **Preemptive Scream Priority (No More False "Help" on Screams):**
   - Screams have extreme sound pressure level ($> -26\text{ dBFS}$ or $SNR \ge +13\text{ dB}$) and high acoustic resonance ($1.5\text{ kHz} - 4.5\text{ kHz}$ ratio $> 32\%$, spectral centroid $> 1750\text{ Hz}$).
   - When a scream occurs, it triggers **`SCREAM DETECTED!`** and immediately locks out the keyword detector for 1.8 seconds. A scream can **never** be misidentified as "help".

2. **Speech Endpointing (VAD State Machine - No More Ambient Noise Triggers):**
   - Instead of continuously matching sliding windows of room silence, the redesigned engine uses energy-based speech endpointing.
   - It waits for you to finish speaking a word (detecting speech onset, tracking syllable energy contours, and detecting trailing silence of 130 ms).
   - Only completed, isolated words ($200\text{ ms} - 1200\text{ ms}$) are sent to the classifier.

3. **Orthogonal Acoustic-Phonetic Classification (No More "Save Me" Confusion):**
   - **`"help"`**: 1 syllable, duration 200–550 ms, **no** initial `/s/` sibilant (initial ZCR $< 0.16$), front vowel `/e/`.
   - **`"stop"`**: 1 syllable, duration 200–580 ms, **strong** initial `/s/` sibilant (initial ZCR $\ge 0.17$).
   - **`"save me"`**: 2 syllables, duration 420–950 ms, **strong** initial `/s/` sibilant (initial ZCR $\ge 0.17$).
   - **`"bachao"`**: 2 syllables, duration 300–780 ms, **no** initial `/s/` sibilant.
   - **`"udhavi"`**: 3 syllables, duration 380–850 ms, initial low vowel `/u/`.
   - **`"kaapaathunga"`**: 3–4 rhythmic syllables, duration 550–1300 ms.

4. **1-Click Voice Calibration (Optional):**
   - Click any word button in the GUI (`help`, `save me`, `stop`, `bachao`, `kaapaathunga`, `udhavi`) and speak the word once.
   - It captures your specific vocal timbre and microphone response for a 100% personalized match.

---

## Visual Trigger (Green Screen GUI)

- **Default State:** Sleek dark window displaying `"Listening..."`, live audio level meter, and SNR telemetry.
- **Triggered State:** Instantly turns the entire window **bright emerald green** (`#00E676`) for 2.5 seconds, displaying:
  - Trigger Type: `🚨 SCREAM DETECTED!` or `🗣️ KEYWORD DETECTED!`
  - Matched word/event (e.g. `'help' (EN)` or `Acoustic Scream Distress`)
  - Confidence percentage (e.g. `92.0%`)
  - Syllable count, duration, and timestamp.
- Automatically reverts to `"Listening..."` after 2.5 seconds.
- Terminal simultaneously outputs clean, real-time diagnostic logs.

---

## How to Run

Run directly with Java using the JDK bundled in Android Studio:

### PowerShell
```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" tests\live_audio_test\LiveAudioTestRunner.java
```

### Command Prompt (CMD)
```cmd
"C:\Program Files\Android\Android Studio\jbr\bin\java.exe" tests\live_audio_test\LiveAudioTestRunner.java
```

---

## How to Test

1. **Ambient Room Test:**
   - Stay quiet or speak normal conversational words ("weather", "computer", "hello").
   - Confirm: The GUI stays in dark `"Listening..."` state. Zero false triggers.

2. **Scream Detection:**
   - Produce a loud, sharp vocal scream or shout.
   - Result: Window immediately flashes bright green with **`SCREAM CONFIRMED!`** and console logs `🚨 [SCREAM DETECTED]`.

3. **Keyword Detection:**
   - Say clearly into your microphone:
     - `"help"` -> Flashes bright green: `'help' (EN)`
     - `"save me"` -> Flashes bright green: `'save me' (EN)`
     - `"stop"` -> Flashes bright green: `'stop' (EN)`
     - `"bachao"` -> Flashes bright green: `'bachao' (HI)`
     - `"kaapaathunga"` -> Flashes bright green: `'kaapaathunga' (TA)`
     - `"udhavi"` -> Flashes bright green: `'udhavi' (TA)`
