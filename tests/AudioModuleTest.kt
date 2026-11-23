package com.guardiangrid.tests

import com.guardiangrid.core.models.AudioStage
import com.guardiangrid.modules.audio.AudioPreprocessor
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.DistressKeywordSpotter
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import com.guardiangrid.modules.audio.TwoStageAudioEvidenceRecorder
import com.guardiangrid.modules.audio.WavAudioEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Formal JUnit test suite for GuardianGrid's Audio Intelligence Subsystem (Member 1).
 * Validates noise floor gating, speech endpointing, scream detection, and false-alarm rejection.
 */
class AudioModuleTest {

    private lateinit var spotter: DistressKeywordSpotter
    private lateinit var classifier: ScreamDistressClassifier
    private lateinit var circularBuffer: CircularAudioBuffer

    @Before
    fun setUp() {
        circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
        spotter = DistressKeywordSpotter(circularBuffer)
        classifier = ScreamDistressClassifier(circularBuffer = circularBuffer)
    }

    // --- 1. Silence Rejection ---

    @Test
    fun testSilenceRejection() {
        val silence = ShortArray(16000) { 0 }

        val keywordMatch = spotter.evaluateSpeechWindow(silence)
        assertNull("Silence must not trigger any keyword match", keywordMatch)

        val screamScore = classifier.evaluateAudioSamples(silence)
        assertTrue("Silence must produce near-zero scream confidence ($screamScore)", screamScore < 0.15f)
    }

    // --- 2. Constant Background Hum Rejection ---

    @Test
    fun testConstantBackgroundHumRejection() {
        // Synthesizes 1.2 seconds of constant 120 Hz + 240 Hz hum (e.g. computer fan / HVAC drone)
        // In the uncalibrated engine, this hum triggered false 'save me' detections at ~85% confidence
        val hum = ShortArray(19200) { i ->
            val s1 = sin(2.0 * PI * 120.0 * i / 16000.0) * 3500
            val s2 = sin(2.0 * PI * 240.0 * i / 16000.0) * 1800
            (s1 + s2).toInt().toShort()
        }

        val keywordMatch = spotter.evaluateSpeechWindow(hum)
        assertNull("Constant low-frequency hum must be rejected by VAD & spectral flux checks", keywordMatch)

        val screamScore = classifier.evaluateAudioSamples(hum)
        assertTrue("Background hum must not trigger scream classifier ($screamScore)", screamScore < 0.25f)
    }

    // --- 3. Ordinary Speech (Calm / Non-Distress) ---

    @Test
    fun testOrdinarySpeechNonDistress() {
        // Synthesizes conversational speech-like vowel transitions (formants at 500 Hz and 1100 Hz, low centroid)
        val speech = ShortArray(12000) { i ->
            val formant = if (i < 4000) 500.0 else if (i < 8000) 900.0 else 600.0
            val env = if (i % 2000 < 1500) 0.7 else 0.1 // Inter-syllable envelope
            (sin(2.0 * PI * formant * i / 16000.0) * env * 7000).toInt().toShort()
        }

        val screamScore = classifier.evaluateAudioSamples(speech)
        assertTrue("Calm conversational speech must not trigger scream distress ($screamScore)", screamScore < 0.40f)

        // Ordinary speech should not match emergency keywords unless phonetically aligned
        val keywordMatch = spotter.evaluateSpeechWindow(speech)
        assertNull("Non-keyword conversational speech must not trigger emergency keyword match", keywordMatch)
    }

    // --- 4. Keyword Test Fixture ("help") ---

    @Test
    fun testKeywordTestFixture() {
        // Synthesizes an acoustic test fixture matching the syllable profile of "help":
        // Step 1: Broadband aspirated onset (/h/) for ~90 ms (20% low, 40% mid, 40% high)
        // Step 2: Strong mid-frequency vowel (/e/) for ~220 ms (40% low, 50% mid, 10% high)
        // Step 3: Low-frequency plosive drop (/l-p/) for ~100 ms (60% low, 30% mid, 10% high)
        // Total duration = ~410 ms (matches expected 250ms - 700ms window)
        val sampleRate = 16000
        val totalSamples = (0.410 * sampleRate).toInt()
        val helpFixture = ShortArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            when {
                // Step 1: /h/
                t < 0.090 -> {
                    val s = sin(2.0 * PI * 400.0 * i / sampleRate) * 0.45 +
                            sin(2.0 * PI * 1400.0 * i / sampleRate) * 0.63 +
                            sin(2.0 * PI * 3200.0 * i / sampleRate) * 0.63
                    (s * 7000).toInt().toShort()
                }
                // Step 2: /e/
                t < 0.310 -> {
                    val s = sin(2.0 * PI * 500.0 * i / sampleRate) * 0.63 +
                            sin(2.0 * PI * 1200.0 * i / sampleRate) * 0.71 +
                            sin(2.0 * PI * 3000.0 * i / sampleRate) * 0.32
                    (s * 9000).toInt().toShort()
                }
                // Step 3: /l-p/
                else -> {
                    val s = sin(2.0 * PI * 400.0 * i / sampleRate) * 0.77 +
                            sin(2.0 * PI * 1000.0 * i / sampleRate) * 0.55 +
                            sin(2.0 * PI * 3000.0 * i / sampleRate) * 0.32
                    (s * 5000).toInt().toShort()
                }
            }
        }

        val match = spotter.evaluateSpeechWindow(helpFixture)
        assertNotNull("Keyword test fixture matching 'help' phonetic trajectory should be detected", match)
        assertEquals("help", match?.keyword)
        assertEquals("en", match?.language)
        assertTrue("Confidence should be above detection threshold (${match?.confidence})", (match?.confidence ?: 0f) >= 0.70f)
    }

    // --- 5. High-Energy Scream Detection & Preemption ---

    @Test
    fun testHighEnergyScreamDetectionAndKeywordPreemption() {
        // Synthesizes a loud, sustained vocal scream (2800 Hz + 3500 Hz high acoustic resonance)
        // Lasts 400 ms (> 200 ms physiological vocalization) at high Sound Pressure Level (~ -12 dBFS)
        val scream = ShortArray(6400) { i ->
            val f1 = sin(2.0 * PI * 2800.0 * i / 16000.0)
            val f2 = 0.6 * sin(2.0 * PI * 3600.0 * i / 16000.0)
            ((f1 + f2) * 19000).toInt().toShort()
        }

        val screamScore = classifier.evaluateAudioSamples(scream)
        assertTrue("High-energy acoustic scream must be detected ($screamScore >= 0.65)", screamScore >= 0.65f)

        // Scream should NOT be falsely decoded as a spoken keyword (e.g. 'help')
        spotter.notifyScreamDetected(1800L)
        val keywordMatch = spotter.evaluateSpeechWindow(scream)
        assertNull("A high-energy vocal scream must preempt keyword detection, never triggering false 'help'", keywordMatch)
    }

    // --- 6. Impulse Noise Rejection (Clicks / Claps) ---

    @Test
    fun testImpulseNoiseRejection() {
        // Synthesizes a sharp 2 ms transient impulse click (e.g. mouse click, dropped pen, keypress: 24 samples)
        // followed by silence. Total buffer = 8000 samples (500 ms).
        val click = ShortArray(8000) { i ->
            if (i in 1000..1024) {
                (sin(2.0 * PI * 3200.0 * i / 16000.0) * 18000).toInt().toShort()
            } else {
                0
            }
        }

        val screamScore = classifier.evaluateAudioSamples(click)
        assertTrue("Short transient impulse click must be rejected by persistence filter ($screamScore < 0.40)", screamScore < 0.40f)

        val keywordMatch = spotter.evaluateSpeechWindow(click)
        assertNull("Transient click (< 160 ms) must be rejected by speech utterance duration gate", keywordMatch)
    }

    // --- 7. Two-Stage Audio Evidence Recording ---

    @Test
    fun testTwoStageEvidenceRecording() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "audio_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val recorderBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
            // Pre-fill circular buffer with 3 seconds of audio tone
            val testTone = ShortArray(48000) { i -> (sin(2.0 * PI * 440.0 * i / 16000.0) * 8000).toInt().toShort() }
            recorderBuffer.write(testTone)

            val recorder = TwoStageAudioEvidenceRecorder(recorderBuffer, cacheDirectory = tempDir)

            // Stage 1: Snapshot 3-second preview from RAM to WAV
            val stage1Result = recorder.captureStageOneBuffer(3000L)
            assertTrue("Stage 1 capture must succeed", stage1Result.isSuccess)
            val segment1 = stage1Result.getOrThrow()
            assertEquals(AudioStage.STAGE_1_PREVIEW, segment1.stage)
            assertTrue("Stage 1 WAV file must exist", File(segment1.filePath).exists())
            assertTrue("Stage 1 WAV file must contain data (> 44 bytes)", File(segment1.filePath).length() > 44)

            // Stage 2: Continuous recording stream
            val stage2File = File(tempDir, "stage2_test.wav")
            recorder.startStageTwoRecording(stage2File)
            assertTrue("Stage 2 file must be initialized", stage2File.exists())
            recorder.stopRecording()
            assertTrue("Stage 2 WAV header must be finalized", stage2File.length() >= 44)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
