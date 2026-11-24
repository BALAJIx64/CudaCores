package com.guardiangrid.tests

import com.guardiangrid.core.models.AudioStage
import com.guardiangrid.modules.audio.AudioPreprocessor
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import com.guardiangrid.modules.audio.TwoStageAudioEvidenceRecorder
import com.guardiangrid.modules.audio.WavAudioEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Formal JUnit test suite for GuardianGrid's Audio Intelligence Subsystem (Member 1).
 * Validates noise floor gating, speech rejection, scream and vocal distress detection, and false-alarm rejection.
 */
class AudioModuleTest {

    private lateinit var classifier: ScreamDistressClassifier
    private lateinit var circularBuffer: CircularAudioBuffer

    @Before
    fun setUp() {
        circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
        classifier = ScreamDistressClassifier(circularBuffer = circularBuffer)
    }

    // --- 1. Silence Rejection ---

    @Test
    fun testSilenceRejection() {
        val silence = ShortArray(16000) { 0 }

        val screamScore = classifier.evaluateAudioSamples(silence)
        assertTrue("Silence must produce near-zero scream confidence ($screamScore)", screamScore < 0.15f)
    }

    // --- 2. Constant Background Hum Rejection ---

    @Test
    fun testConstantBackgroundHumRejection() {
        // Synthesizes 1.2 seconds of constant 120 Hz + 240 Hz hum (e.g. computer fan / HVAC drone)
        val hum = ShortArray(19200) { i ->
            val s1 = sin(2.0 * PI * 120.0 * i / 16000.0) * 3500
            val s2 = sin(2.0 * PI * 240.0 * i / 16000.0) * 1800
            (s1 + s2).toInt().toShort()
        }

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
    }

    // --- 4. High-Energy Scream Detection ---

    @Test
    fun testHighEnergyScreamDetection() {
        // Synthesizes a loud, sustained vocal scream (2800 Hz + 3500 Hz high acoustic resonance)
        // Lasts 400 ms (> 200 ms physiological vocalization) at high Sound Pressure Level (~ -12 dBFS)
        val scream = ShortArray(6400) { i ->
            val f1 = sin(2.0 * PI * 2800.0 * i / 16000.0)
            val f2 = 0.6 * sin(2.0 * PI * 3600.0 * i / 16000.0)
            ((f1 + f2) * 19000).toInt().toShort()
        }

        val screamScore = classifier.evaluateAudioSamples(scream)
        assertTrue("High-energy acoustic scream must be detected ($screamScore >= 0.65)", screamScore >= 0.65f)
    }

    // --- 5. Impulse Noise Rejection (Clicks / Claps) ---

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
    }

    // --- 6. Two-Stage Audio Evidence Recording ---

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
