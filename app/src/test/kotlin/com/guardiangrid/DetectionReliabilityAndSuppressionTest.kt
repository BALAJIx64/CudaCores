package com.guardiangrid

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit test suite validating Task 7: Audio Detection & False-Positive Hardening.
 *
 * Covers:
 * - Positive detection: High harmonic acoustic scream tone.
 * - Negative rejection: Silence (< -65 dBFS) must not trigger distress.
 * - Negative rejection: Low-band conversational speech tone (200-400 Hz) must not trigger scream.
 * - Debounce / cooldown repeated detection suppression.
 */
class DetectionReliabilityAndSuppressionTest {

    private lateinit var circularBuffer: CircularAudioBuffer
    private lateinit var classifier: ScreamDistressClassifier

    @Before
    fun setUp() {
        circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
        classifier = ScreamDistressClassifier(circularBuffer = circularBuffer)
    }

    @Test
    fun testPositiveScreamDetection() {
        // High harmonic acoustic scream tone (2800 Hz + 3500 Hz harmonic with elevated spectral centroid)
        val sampleRate = 16000
        val screamChunk = ShortArray(sampleRate) { i ->
            val s1 = sin(2.0 * PI * 2800.0 * i / sampleRate)
            val s2 = 0.5 * sin(2.0 * PI * 3500.0 * i / sampleRate)
            ((s1 + s2) * 12000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val score = classifier.evaluateAudioSamples(screamChunk)
        assertTrue("High harmonic vocal scream must yield high confidence score (> 0.40)", score >= 0.40f)
    }

    @Test
    fun testNegativeSilenceRejection() {
        // Complete silence (0 amplitude)
        val silence = ShortArray(16000) { 0 }
        val score = classifier.evaluateAudioSamples(silence)
        assertEquals("Silence must produce exactly 0.0 confidence score", 0.0f, score, 0.001f)
    }

    @Test
    fun testNegativeSpeechRejection() {
        // Normal conversational fundamental speech pitch (150 Hz - 300 Hz)
        val sampleRate = 16000
        val speechChunk = ShortArray(sampleRate) { i ->
            val fundamental = sin(2.0 * PI * 220.0 * i / sampleRate)
            val vowelFormant = 0.4 * sin(2.0 * PI * 440.0 * i / sampleRate)
            ((fundamental + vowelFormant) * 8000).toInt().toShort()
        }

        val score = classifier.evaluateAudioSamples(speechChunk)
        assertTrue("Conversational speech must be suppressed by low-band formant filter (score <= 0.20)", score <= 0.20f)
    }

    @Test
    fun testRepeatedDetectionCooldownSuppression() = runBlocking {
        val signals = mutableListOf<DistressSignal>()
        val job = launch {
            classifier.startMonitoring().collect { signal ->
                signals.add(signal)
            }
        }

        // First scream trigger
        classifier.checkAndEmitDistress(0.95f)
        classifier.checkAndEmitDistress(0.95f) // Second frame to satisfy 2-frame persistence
        kotlinx.coroutines.yield()

        assertEquals(1, signals.size)

        // Immediate subsequent scream trigger within 3-second cooldown window
        classifier.checkAndEmitDistress(0.95f)
        classifier.checkAndEmitDistress(0.95f)
        kotlinx.coroutines.yield()

        assertEquals("Immediate repeated distress signals within cooldown window must be suppressed", 1, signals.size)

        job.cancel()
    }

    @Test
    fun testConservativeAudioConfigSuppressesSpeechShortNoiseMusicAndSingleFrameSpikes() = runBlocking {
        // 1. Short sudden noise burst (40 ms high-frequency bang inside 1s window) -> rejected
        val shortBang = ShortArray(16000) { i ->
            if (i in 4000..4640) {
                (sin(2.0 * PI * 3000.0 * i / 16000.0) * 26000).toInt().toShort()
            } else {
                0
            }
        }
        val bangScore = classifier.evaluateAudioSamples(shortBang)
        assertTrue("Short 40ms bang must be suppressed (score=$bangScore)", bangScore < 0.30f)

        // 2. Music / broadband multi-band mix -> rejected
        val musicMix = ShortArray(16000) { i ->
            val bass = sin(2.0 * PI * 180.0 * i / 16000.0) * 0.45
            val chord1 = sin(2.0 * PI * 440.0 * i / 16000.0) * 0.35
            val chord2 = sin(2.0 * PI * 660.0 * i / 16000.0) * 0.25
            val hihat = sin(2.0 * PI * 3100.0 * i / 16000.0) * 0.15
            ((bass + chord1 + chord2 + hihat) * 14000).toInt().toShort()
        }
        val musicScore = classifier.evaluateAudioSamples(musicMix)
        assertTrue("Music/multi-band audio must be rejected (score=$musicScore)", musicScore < 0.30f)

        // 3. Single noisy high frame does NOT trigger without repeated/sustained confirmation
        val emitted = mutableListOf<DistressSignal>()
        val job = launch {
            classifier.startMonitoring().collect { emitted.add(it) }
        }
        classifier.checkAndEmitDistress(0.85f)
        kotlinx.coroutines.yield()
        assertEquals("Single noisy window must NOT trigger emergency distress", 0, emitted.size)

        // 4. Hysteresis reset prevents accidental single follow-up after drop, while repeated distress confirms
        classifier.checkAndEmitDistress(0.10f)
        classifier.checkAndEmitDistress(0.88f)
        kotlinx.coroutines.yield()
        assertEquals("Dropped score breaks streak so 1 window does not trigger", 0, emitted.size)

        classifier.checkAndEmitDistress(0.88f)
        kotlinx.coroutines.yield()
        assertEquals("Sustained repeated distress must emit signal cleanly", 1, emitted.size)
        job.cancel()
    }
}
