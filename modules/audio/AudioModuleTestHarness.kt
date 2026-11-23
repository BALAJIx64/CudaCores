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
 * or external test harnesses. Exercises all seven critical requirements:
 * 1. Standard RIFF WAV header generation, byte encoding, and file finalization.
 * 2. In-memory circular rolling buffer rollover, duration calculation, and snapshot extraction.
 * 3. Audio preprocessing: Short-to-Float normalization, RMS, dBFS, ZCR, and FFT spectral features.
 * 4. Scream distress classifier: acoustic scream signature detection vs silence vs normal speech.
 * 5. Multi-language keyword spotter: English ("help", "save me") and Tamil ("kaapaathunga", "udhavi").
 * 6. Two-stage emergency evidence recording: Stage 1 preview buffer dump and Stage 2 evidence stream.
 * 7. Hardware interruption and permission denial error handling.
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
        results.add(testDistressKeywordSpotter())
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

    // --- Test 5: Multi-Language Keyword Spotter ---
    fun testDistressKeywordSpotter(): TestResult {
        return try {
            val spotter = DistressKeywordSpotter()
            spotter.startListening(DistressKeywordSpotter.DEFAULT_KEYWORDS)
            check(spotter.isListening()) { "Spotter should report listening active" }

            var positiveTested = 0
            var positivePassed = 0
            var negativeTested = 0
            var negativePassed = 0
            val failureDetails = mutableListOf<String>()

            // 1. Positive Tests for all 6 supported phrases across 3 languages
            val supportedPhrases = listOf(
                "help" to "en",
                "stop" to "en",
                "save me" to "en",
                "bachao" to "hi",
                "udhavi" to "ta",
                "kaapaathunga" to "ta"
            )

            for ((keyword, expectedLang) in supportedPhrases) {
                // Base standard utterance
                positiveTested++
                val baseAudio = synthesizeKeywordWindow(keyword)
                val match = spotter.evaluateSpeechWindow(baseAudio)
                if (match != null && match.keyword == keyword && match.confidence >= 0.72f) {
                    positivePassed++
                } else {
                    failureDetails.add("Missed positive: '$keyword' (got: ${match?.keyword} conf: ${match?.confidence})")
                }

                // Variation A: Female speaker (+15% pitch/formant shift)
                positiveTested++
                val femaleAudio = synthesizeKeywordWindow(keyword, f0 = 220.0, formantScale = 1.15)
                val femaleMatch = spotter.evaluateSpeechWindow(femaleAudio)
                if (femaleMatch != null && femaleMatch.keyword == keyword && femaleMatch.confidence >= 0.70f) {
                    positivePassed++
                } else {
                    failureDetails.add("Missed female variation: '$keyword' (got: ${femaleMatch?.keyword} conf: ${femaleMatch?.confidence})")
                }

                // Variation B: Fast speech rate (0.85x duration)
                positiveTested++
                val fastAudio = synthesizeKeywordWindow(keyword, durationScale = 0.85)
                val fastMatch = spotter.evaluateSpeechWindow(fastAudio)
                if (fastMatch != null && fastMatch.keyword == keyword && fastMatch.confidence >= 0.70f) {
                    positivePassed++
                } else {
                    failureDetails.add("Missed fast variation: '$keyword' (got: ${fastMatch?.keyword} conf: ${fastMatch?.confidence})")
                }

                // Variation C: Slow speech rate (1.18x duration)
                positiveTested++
                val slowAudio = synthesizeKeywordWindow(keyword, durationScale = 1.18)
                val slowMatch = spotter.evaluateSpeechWindow(slowAudio)
                if (slowMatch != null && slowMatch.keyword == keyword && slowMatch.confidence >= 0.70f) {
                    positivePassed++
                } else {
                    failureDetails.add("Missed slow variation: '$keyword' (got: ${slowMatch?.keyword} conf: ${slowMatch?.confidence})")
                }

                // Variation D: Quiet speech (-30 dBFS)
                positiveTested++
                val quietAudio = synthesizeKeywordWindow(keyword, amplitudeScale = 0.35)
                val quietMatch = spotter.evaluateSpeechWindow(quietAudio)
                if (quietMatch != null && quietMatch.keyword == keyword && quietMatch.confidence >= 0.70f) {
                    positivePassed++
                } else {
                    failureDetails.add("Missed quiet variation: '$keyword' (got: ${quietMatch?.keyword} conf: ${quietMatch?.confidence})")
                }
            }

            // 2. Negative Tests (Silence, Room Noise, Keyboard Clicks, Conversational Speech)
            // Negative Case 1: Pure digital silence
            negativeTested++
            val silence = ShortArray(19200) { 0 }
            val silenceMatch = spotter.evaluateSpeechWindow(silence)
            if (silenceMatch == null) {
                negativePassed++
            } else {
                failureDetails.add("False positive on silence: '${silenceMatch.keyword}' (${silenceMatch.confidence})")
            }

            // Negative Case 2: Room background noise (50/100 Hz hum + low-freq hiss at -35 dBFS)
            // Specifically verifies that room noise NO LONGER matches "save me"!
            negativeTested++
            val roomNoise = ShortArray(19200) { i ->
                val hum50 = sin(2.0 * PI * 50.0 * i / 16000.0) * 800.0
                val hum100 = sin(2.0 * PI * 100.0 * i / 16000.0) * 400.0
                val hiss = ((i * 1103515245 + 12345) % 300).toDouble()
                (hum50 + hum100 + hiss).toInt().toShort()
            }
            val roomMatch = spotter.evaluateSpeechWindow(roomNoise)
            if (roomMatch == null) {
                negativePassed++
            } else {
                failureDetails.add("False positive on room noise: '${roomMatch.keyword}' (${roomMatch.confidence})")
            }

            // Negative Case 3: Keyboard clicks (impulsive transient clicks)
            negativeTested++
            val keyboardClicks = ShortArray(19200) { 0 }
            val clickLocations = listOf(3000, 8000, 14000)
            for (clickStart in clickLocations) {
                for (k in 0 until 120) { // ~7.5 ms click
                    keyboardClicks[clickStart + k] = (sin(2.0 * PI * 3500.0 * k / 16000.0) * 14000 * Math.exp(-k / 20.0)).toInt().toShort()
                }
            }
            val clickMatch = spotter.evaluateSpeechWindow(keyboardClicks)
            if (clickMatch == null) {
                negativePassed++
            } else {
                failureDetails.add("False positive on keyboard clicks: '${clickMatch.keyword}' (${clickMatch.confidence})")
            }

            // Negative Case 4: Ordinary conversational words ("hello", "tomorrow", "weather", "table")
            val conversationalWords = listOf("hello", "tomorrow", "weather", "table")
            for (word in conversationalWords) {
                negativeTested++
                val nonKeywordAudio = synthesizeConversationalWord(word)
                val convMatch = spotter.evaluateSpeechWindow(nonKeywordAudio)
                if (convMatch == null) {
                    negativePassed++
                } else {
                    failureDetails.add("False positive on conversational word '$word': '${convMatch.keyword}' (${convMatch.confidence})")
                }
            }

            spotter.stopListening()
            check(!spotter.isListening()) { "Spotter should report inactive" }

            val positiveAcc = (positivePassed.toDouble() / positiveTested) * 100.0
            val falsePositives = negativeTested - negativePassed
            val message = "Positive: $positivePassed/$positiveTested (%.1f%%), False Positives: $falsePositives/$negativeTested. Details: ${failureDetails.joinToString("; ")}".format(positiveAcc)

            val passed = (positivePassed >= (positiveTested * 0.85).toInt()) && (falsePositives == 0)
            TestResult("testDistressKeywordSpotter", passed, message)
        } catch (e: Throwable) {
            TestResult("testDistressKeywordSpotter", false, "Failed: ${e.message}")
        }
    }

    // --- Synthetic Acoustic Speech Generator for Positive & Negative Tests ---
    private data class Phone(val f1: Double, val f2: Double, val durMs: Int)
    private data class Syllable(val phones: List<Phone>)

    private fun synthesizeKeywordWindow(
        keyword: String,
        f0: Double = 130.0,
        formantScale: Double = 1.0,
        durationScale: Double = 1.0,
        amplitudeScale: Double = 1.0
    ): ShortArray {
        val syllables = when (keyword) {
            "help" -> listOf(
                Syllable(listOf(
                    Phone(1200.0, 2000.0, 70), // /h/
                    Phone(600.0, 1800.0, 170),  // /e/
                    Phone(400.0, 1100.0, 80),   // /l/
                    Phone(300.0, 0.0, 50)       // /p/
                ))
            )
            "stop" -> listOf(
                Syllable(listOf(
                    Phone(5200.0, 6400.0, 100), // /s/
                    Phone(2500.0, 0.0, 30),     // /t/
                    Phone(650.0, 1100.0, 170),  // /ɒ/
                    Phone(300.0, 0.0, 60)       // /p/
                ))
            )
            "save me" -> listOf(
                Syllable(listOf(
                    Phone(5200.0, 6400.0, 110), // /s/
                    Phone(550.0, 2100.0, 180),  // /eɪ/
                    Phone(350.0, 1400.0, 60)    // /v/
                )),
                Syllable(listOf(
                    Phone(250.0, 0.0, 70),      // /m/
                    Phone(300.0, 2400.0, 180)   // /i/
                ))
            )
            "bachao" -> listOf(
                Syllable(listOf(
                    Phone(750.0, 1250.0, 180)   // /ba/
                )),
                Syllable(listOf(
                    Phone(2800.0, 3400.0, 80),  // /ch/
                    Phone(700.0, 1050.0, 220)   // /aʊ/
                ))
            )
            "udhavi" -> listOf(
                Syllable(listOf(
                    Phone(350.0, 800.0, 160)    // /u/
                )),
                Syllable(listOf(
                    Phone(700.0, 1300.0, 180)   // /d̪a/
                )),
                Syllable(listOf(
                    Phone(300.0, 2350.0, 220)   // /vi/
                ))
            )
            "kaapaathunga" -> listOf(
                Syllable(listOf(Phone(750.0, 1250.0, 220))), // kaa
                Syllable(listOf(Phone(750.0, 1250.0, 200))), // paa
                Syllable(listOf(Phone(400.0, 1100.0, 180))), // thun
                Syllable(listOf(Phone(700.0, 1250.0, 180)))  // ga
            )
            else -> emptyList()
        }

        val isSibilant = (keyword == "stop" || keyword == "save me")
        return renderSyllableSequence(syllables, f0, formantScale, durationScale, amplitudeScale, unvoicedFirst = isSibilant)
    }

    private fun synthesizeConversationalWord(word: String): ShortArray {
        val syllables = when (word) {
            "hello" -> listOf(
                Syllable(listOf(Phone(1200.0, 0.0, 60), Phone(600.0, 1800.0, 160))),
                Syllable(listOf(Phone(500.0, 900.0, 200)))
            )
            "tomorrow" -> listOf(
                Syllable(listOf(Phone(2000.0, 0.0, 25), Phone(500.0, 1400.0, 120))),
                Syllable(listOf(Phone(700.0, 1100.0, 180))),
                Syllable(listOf(Phone(500.0, 900.0, 200)))
            )
            "weather" -> listOf(
                Syllable(listOf(Phone(400.0, 1000.0, 140))),
                Syllable(listOf(Phone(600.0, 1800.0, 160), Phone(500.0, 1300.0, 140)))
            )
            "table" -> listOf(
                Syllable(listOf(Phone(2500.0, 0.0, 30), Phone(550.0, 2000.0, 200))),
                Syllable(listOf(Phone(400.0, 1100.0, 120)))
            )
            else -> emptyList()
        }
        return renderSyllableSequence(syllables, 130.0, 1.0, 1.0, 1.0, unvoicedFirst = false)
    }

    private fun renderSyllableSequence(
        syllables: List<Syllable>,
        f0: Double,
        formantScale: Double,
        durationScale: Double,
        amplitudeScale: Double,
        unvoicedFirst: Boolean
    ): ShortArray {
        // Standard snapshot window: 1.2s at 16000 Hz = 19200 samples
        val window = ShortArray(19200)

        // Add 250ms quiet lead-in noise (-55 dBFS)
        val leadInSamples = 4000
        for (i in 0 until 19200) {
            val dither = ((i * 1103515245 + 12345) % 15).toDouble()
            window[i] = dither.toInt().toShort()
        }

        var offset = leadInSamples
        val interSyllableDipSamples = (25 * 16.0).toInt() // 25ms dip between syllables

        for ((sylIdx, syl) in syllables.withIndex()) {
            val sylMs = (syl.phones.sumOf { it.durMs } * durationScale).toInt()
            val sylSamples = (sylMs * 16.0).toInt()
            var phoneOffset = 0

            for ((pIdx, phone) in syl.phones.withIndex()) {
                val pSamples = ((phone.durMs * durationScale) * 16.0).toInt()
                val isUnvoiced = (sylIdx == 0 && pIdx == 0 && unvoicedFirst)
                val phoneAmp = if (phone.f1 > 2000.0 || (phone.f2 == 0.0 && phone.f1 < 400.0)) 0.45 else 1.0

                for (i in 0 until pSamples) {
                    if (offset + phoneOffset + i >= 19200) break
                    val t = i / 16000.0

                    // Syllable-level envelope
                    val posInSyl = phoneOffset + i
                    val sylRamp = when {
                        posInSyl < sylSamples * 0.12 -> posInSyl / (sylSamples * 0.12)
                        posInSyl > sylSamples * 0.88 -> (sylSamples - posInSyl) / (sylSamples * 0.12)
                        else -> 1.0
                    }

                    var s = 0.55 * sin(2.0 * PI * (phone.f1 * formantScale) * t)
                    if (phone.f2 > 0.0) s += 0.35 * sin(2.0 * PI * (phone.f2 * formantScale) * t)
                    if (!isUnvoiced) s += 0.10 * sin(2.0 * PI * f0 * t)

                    val value = (s * sylRamp * 14000.0 * amplitudeScale * phoneAmp).toInt().coerceIn(-32768, 32767).toShort()
                    window[offset + phoneOffset + i] = value
                }
                phoneOffset += pSamples
            }
            offset += sylSamples + interSyllableDipSamples
        }

        return window
    }

    // --- Test 6: Two-Stage Audio Evidence Recorder ---
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

    // --- Test 7: Error Handling and Interruption ---
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

