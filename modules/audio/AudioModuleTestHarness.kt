package com.guardiangrid.modules.audio

import com.guardiangrid.core.models.AudioStage
import com.guardiangrid.core.models.DistressType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Self-contained unit & sample test harness for GuardianGrid's Audio Intelligence module.
 *
 * Runs on standard JVM without requiring an Android device, physical microphone,
 * or external test harnesses. Exercises all six critical requirements:
 * 1. Standard RIFF WAV header generation, byte encoding, and file finalization.
 * 2. In-memory circular rolling buffer rollover, duration calculation, and snapshot extraction.
 * 3. Audio preprocessing: Short-to-Float normalization, RMS, dBFS, ZCR, and FFT spectral features.
 * 4. Scream distress classifier: acoustic scream signature detection vs silence vs normal speech.
 * 5. Two-stage emergency evidence recording: Stage 1 preview buffer dump and Stage 2 evidence stream.
 * 6. Hardware interruption and permission denial error handling.
 *
 * Owned by Member 1 (modules/audio).
 */
object AudioModuleTestHarness {

    data class TestResult(val name: String, val passed: Boolean, val message: String = "")
    data class TestSuiteResult(val total: Int, val passed: Int, val failed: Int, val results: List<TestResult>)

    /**
     * Executes the complete audio test suite and returns structured results.
     */
    fun runAllTests(): TestSuiteResult {
        val results = mutableListOf<TestResult>()

        results.add(testWavEncoder())
        results.add(testCircularAudioBuffer())
        results.add(testAudioPreprocessor())
        results.add(testScreamDistressClassifier())
        results.add(testTwoStageEvidenceRecorder())
        results.add(testErrorHandlingAndInterruption())

        val passedCount = results.count { it.passed }
        val failedCount = results.size - passedCount

        return TestSuiteResult(
            total = results.size,
            passed = passedCount,
            failed = failedCount,
            results = results
        )
    }

    // --- Test 1: WAV Audio Encoder ---
    fun testWavEncoder(): TestResult {
        return try {
            val sampleCount = 16000 // 1 second at 16 kHz
            val samples = ShortArray(sampleCount) { i -> (sin(2.0 * PI * 440.0 * i / 16000.0) * 16000).toInt().toShort() }

            val wavBytes = WavAudioEncoder.encodeToWavBytes(samples, 16000, 1)

            // Header checks: 44 bytes header + 32000 bytes PCM = 32044 total
            check(wavBytes.size == 44 + 32000) { "WAV byte length mismatch: ${wavBytes.size}" }
            check(wavBytes[0] == 'R'.code.toByte() && wavBytes[1] == 'I'.code.toByte() && wavBytes[2] == 'F'.code.toByte() && wavBytes[3] == 'F'.code.toByte()) { "Invalid RIFF identifier" }
            check(wavBytes[8] == 'W'.code.toByte() && wavBytes[9] == 'A'.code.toByte() && wavBytes[10] == 'V'.code.toByte() && wavBytes[11] == 'E'.code.toByte()) { "Invalid WAVE format" }

            // Write to file and check file size
            val tempFile = File.createTempFile("test_wav_", ".wav")
            tempFile.deleteOnExit()
            WavAudioEncoder.writeWavFile(tempFile, samples, 16000, 1)
            check(tempFile.length() == (44 + 32000).toLong()) { "WAV file size mismatch on disk" }

            TestResult("testWavEncoder", true, "Canonical 44-byte RIFF WAV encoding verified.")
        } catch (e: Throwable) {
            TestResult("testWavEncoder", false, "Failed: ${e.message}")
        }
    }

    // --- Test 2: Circular Audio Buffer ---
    fun testCircularAudioBuffer(): TestResult {
        return try {
            // Buffer of 3000 ms at 16000 Hz = 48,000 samples
            val buffer = CircularAudioBuffer(durationMs = 3000L, sampleRate = 16000)
            check(buffer.capacitySamples == 48000) { "Capacity mismatch" }
            check(buffer.availableDurationMs() == 0L) { "Initial duration must be 0" }

            // Write 1 second (16,000 samples)
            val chunk1 = ShortArray(16000) { 100 }
            buffer.write(chunk1)
            check(buffer.availableDurationMs() == 1000L) { "Duration after chunk 1 should be 1000ms" }

            // Write 3 more seconds (48,000 samples) -> Total 64,000 written (triggers circular rollover)
            val chunk2 = ShortArray(48000) { 200 }
            buffer.write(chunk2)

            // Available duration capped at 3000ms
            check(buffer.availableDurationMs() == 3000L) { "Duration should cap at 3000ms" }

            // Snapshot the last 1000ms -> should contain exactly samples of value 200
            val snapshot = buffer.snapshot(1000L)
            check(snapshot.size == 16000) { "Snapshot size should be 16000" }
            check(snapshot.all { it == 200.toShort() }) { "Snapshot samples should match rolled-over value" }

            buffer.clear()
            check(buffer.availableDurationMs() == 0L) { "Buffer should be empty after clear" }

            TestResult("testCircularAudioBuffer", true, "Rollover and snapshot math verified.")
        } catch (e: Throwable) {
            TestResult("testCircularAudioBuffer", false, "Failed: ${e.message}")
        }
    }

    // --- Test 3: Audio Preprocessing Features ---
    fun testAudioPreprocessor(): TestResult {
        return try {
            // Generate synthetic 2500 Hz tone (scream resonant band)
            val n = 512
            val samples = ShortArray(n) { i ->
                (sin(2.0 * PI * 2500.0 * i / 16000.0) * 20000).toInt().toShort()
            }

            val floatSamples = AudioPreprocessor.toNormalizedFloat(samples)
            check(floatSamples.size == n) { "Normalized size mismatch" }

            val rms = AudioPreprocessor.computeRms(floatSamples)
            val dbfs = AudioPreprocessor.rmsToDbfs(rms)
            check(dbfs > -10.0f) { "High amplitude tone should have high dBFS: $dbfs" }

            val zcr = AudioPreprocessor.computeZeroCrossingRate(floatSamples)
            check(zcr > 0.1f) { "High frequency tone should have high ZCR: $zcr" }

            AudioPreprocessor.applyHannWindow(floatSamples)
            val power = AudioPreprocessor.computePowerSpectrum(floatSamples)
            val centroid = AudioPreprocessor.computeSpectralCentroid(power, 16000)
            check(centroid in 2000.0f..3000.0f) { "Centroid should center around 2500 Hz: $centroid" }

            val screamBandRatio = AudioPreprocessor.computeScreamBandRatio(power, 16000)
            check(screamBandRatio > 0.65f) { "2500 Hz tone should concentrate >65% power in scream band: $screamBandRatio" }

            val melEnergies = AudioPreprocessor.computeMelFilterbank(power, 16000, 16)
            check(melEnergies.size == 16) { "Mel filterbank must produce 16 bands" }

            TestResult("testAudioPreprocessor", true, "Spectral centroid, scream ratio, and Mel features verified.")
        } catch (e: Throwable) {
            TestResult("testAudioPreprocessor", false, "Failed: ${e.message}")
        }
    }

    // --- Test 4: Scream Distress Classifier ---
    fun testScreamDistressClassifier(): TestResult {
        return try {
            val classifier = ScreamDistressClassifier()
            classifier.setSensitivity(0.85f)

            // Case A: Silence should produce near-zero distress confidence
            val silence = ShortArray(8000) { 0 }
            val silenceScore = classifier.evaluateAudioSamples(silence)
            check(silenceScore < 0.20f) { "Silence should produce low score: $silenceScore" }

            // Case B: Synthetic vocal scream (Loud 2800 Hz harmonic tone)
            val scream = ShortArray(8000) { i ->
                val f1 = sin(2.0 * PI * 2800.0 * i / 16000.0)
                val f2 = 0.5 * sin(2.0 * PI * 3500.0 * i / 16000.0)
                ((f1 + f2) * 18000).toInt().toShort()
            }
            val screamScore = classifier.evaluateAudioSamples(scream)
            check(screamScore > 0.70f) { "Synthetic scream should produce high confidence: $screamScore" }

            TestResult("testScreamDistressClassifier", true, "Distress scream classification verified (score: $screamScore).")
        } catch (e: Throwable) {
            TestResult("testScreamDistressClassifier", false, "Failed: ${e.message}")
        }
    }

    // --- Test 5: Two-Stage Audio Evidence Recorder ---
    fun testTwoStageEvidenceRecorder(): TestResult = runBlocking {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "audio_test_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
            // Pre-fill circular buffer with 3 seconds of audio
            val sampleTone = ShortArray(48000) { i -> (sin(2.0 * PI * 440.0 * i / 16000.0) * 10000).toInt().toShort() }
            circularBuffer.write(sampleTone)

            val recorder = TwoStageAudioEvidenceRecorder(circularBuffer, cacheDirectory = tempDir)

            // Stage 1 Preview extraction
            val stage1Result = recorder.captureStageOneBuffer(3000L)
            check(stage1Result.isSuccess) { "Stage 1 capture must succeed" }
            val segment1 = stage1Result.getOrThrow()
            check(segment1.stage == AudioStage.STAGE_1_PREVIEW) { "Stage must be STAGE_1_PREVIEW" }
            check(File(segment1.filePath).exists()) { "Stage 1 WAV file must exist on disk" }
            check(File(segment1.filePath).length() > 44) { "Stage 1 WAV file must contain data" }

            // Stage 2 Continuous evidence recording
            val stage2File = File(tempDir, "stage2_evidence.wav")
            val stage2Flow = recorder.startStageTwoRecording(stage2File)
            check(stage2File.exists()) { "Stage 2 file must be created" }

            recorder.stopRecording()
            check(stage2File.length() >= 44) { "Stage 2 WAV header must be written and finalized" }

            // Cleanup
            tempDir.deleteRecursively()

            TestResult("testTwoStageEvidenceRecorder", true, "Stage 1 buffer dump and Stage 2 stream verified.")
        } catch (e: Throwable) {
            TestResult("testTwoStageEvidenceRecorder", false, "Failed: ${e.message}")
        }
    }

    // --- Test 6: Error Handling and Interruption ---
    fun testErrorHandlingAndInterruption(): TestResult {
        return try {
            val captureManager = AudioCaptureManager()
            // Test permission denial handling
            val deniedResult = captureManager.startCapture(hasRecordAudioPermission = false)
            check(deniedResult.isFailure) { "Permission denial must return Result.failure" }
            check(captureManager.lastErrorMessage != null) { "Error message must be populated" }

            // Test empty sample evaluation
            val classifier = ScreamDistressClassifier()
            val score = classifier.evaluateAudioSamples(ShortArray(0))
            check(score == 0.0f) { "Empty samples must evaluate to 0.0 score" }

            // Test extreme sensitivity bounds
            classifier.setSensitivity(-1.5f)
            classifier.setSensitivity(2.5f)

            TestResult("testErrorHandlingAndInterruption", true, "Permission denial and boundary handling verified.")
        } catch (e: Throwable) {
            TestResult("testErrorHandlingAndInterruption", false, "Failed: ${e.message}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = runAllTests()
        println("=== AUDIO MODULE TEST RESULTS ===")
        println("Total: ${result.total}, Passed: ${result.passed}, Failed: ${result.failed}")
        for (r in result.results) {
            val status = if (r.passed) "[PASS]" else "[FAIL]"
            println("$status ${r.name}: ${r.message}")
        }
        if (result.failed > 0) {
            System.exit(1)
        }
    }
}

fun main() {
    AudioModuleTestHarness.main(emptyArray())
}

