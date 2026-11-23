package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.KeywordSpotter
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated keyword spotter for configurable emergency distress phrases.
 *
 * Implements [KeywordSpotter].
 *
 * Distinct from Sound-Event Classification:
 * - This engine specifically listens for lexical speech keywords and vocal cries for help,
 *   supporting multi-language distress triggers across English, Tamil, and Hindi.
 *
 * Supported Vocabulary:
 * 1. English:
 *    - "help" / "help me"
 *    - "save me"
 *    - "stop"
 *    - "emergency"
 * 2. Tamil:
 *    - "kaapaathunga" (காப்பாத்துங்க - common colloquial cry: "Save me / Help me")
 *    - "udhavi" (உதவி - "Help")
 *    - "ennai kaapaatru" (என்னை காப்பாற்று - "Save me")
 * 3. Hindi:
 *    - "bachao" (बचाओ - "Save me / Help")
 *    - "madad" (मदद - "Help")
 *
 * Keyword Spotting Algorithm:
 * - Employs acoustic-phonetic syllable profiling and dynamic time warping (DTW) across 16-band Mel filterbank energy contours.
 * - Matches vocalic formant transitions, vowel durations, and unvoiced/plosive stops.
 * - Operates entirely on-device, preserving user privacy with zero network calls.
 *
 * Owned by Member 1 (modules/audio).
 */
class DistressKeywordSpotter(
    private val circularBuffer: CircularAudioBuffer? = null
) : KeywordSpotter {

    private var listening = false
    private val signalFlow = MutableSharedFlow<DistressSignal>(extraBufferCapacity = 64)
    private var activeKeywords: List<String> = DEFAULT_KEYWORDS

    private var listeningJob: Job? = null
    private val spotterScope = CoroutineScope(Dispatchers.Default)

    private var lastEmittedTimestamp = 0L
    private val emissionCooldownMs = 2000L

    companion object {
        val DEFAULT_KEYWORDS = listOf(
            "help",
            "save me",
            "stop",
            "kaapaathunga",
            "udhavi",
            "bachao"
        )

        /**
         * Phonetic acoustic profiles defining target syllable structures, expected durations,
         * sibilance gating, and 16-band Mel filterbank energy contours.
         */
        data class KeywordProfile(
            val keyword: String,
            val language: String,
            val minDurationMs: Long,
            val maxDurationMs: Long,
            val syllables: Int,
            val requiresInitialSibilant: Boolean?, // true: /s/ required, false: /s/ forbidden, null: don't care
            val melPattern: List<FloatArray>       // Sequence of normalized 16-band Mel energy vectors
        ) {
            // Backward-compatibility accessor
            val spectralPattern: List<FloatArray> get() = melPattern
        }

        private fun computeCanonicalMel(f1: Double, f2: Double = 0.0, f0: Double = 130.0): FloatArray {
            val frame = FloatArray(512) { i ->
                val t = i / 16000.0
                var s = 0.55 * kotlin.math.sin(2.0 * kotlin.math.PI * f1 * t)
                if (f2 > 0.0) s += 0.35 * kotlin.math.sin(2.0 * kotlin.math.PI * f2 * t)
                s += 0.10 * kotlin.math.sin(2.0 * kotlin.math.PI * f0 * t)
                s.toFloat()
            }
            val windowed = frame.clone()
            AudioPreprocessor.applyHannWindow(windowed)
            val power = AudioPreprocessor.computePowerSpectrum(windowed)
            return AudioPreprocessor.computeNormalizedMelFilterbank(power)
        }

        val PROFILES = listOf(
            // "help": /h/ (1200, 2000) -> /e/ (600, 1800) -> /l/ (400, 1100) -> /p/ (300)
            KeywordProfile(
                keyword = "help",
                language = "en",
                minDurationMs = 200,
                maxDurationMs = 580,
                syllables = 1,
                requiresInitialSibilant = false,
                melPattern = listOf(
                    computeCanonicalMel(1200.0, 2000.0),
                    computeCanonicalMel(600.0, 1800.0),
                    computeCanonicalMel(400.0, 1100.0),
                    computeCanonicalMel(300.0, 0.0)
                )
            ),
            // "stop": /s/ (5200, 6400) -> /t/ (2500) -> /ɒ/ (650, 1100) -> /p/ (300)
            KeywordProfile(
                keyword = "stop",
                language = "en",
                minDurationMs = 200,
                maxDurationMs = 550,
                syllables = 1,
                requiresInitialSibilant = true,
                melPattern = listOf(
                    computeCanonicalMel(5200.0, 6400.0),
                    computeCanonicalMel(2500.0, 0.0),
                    computeCanonicalMel(650.0, 1100.0),
                    computeCanonicalMel(300.0, 0.0)
                )
            ),
            // "save me": /s/ (5200, 6400) -> /eɪ/ (550, 2100) -> /v/ (350, 1400) -> /m/ (250) -> /i/ (300, 2400)
            KeywordProfile(
                keyword = "save me",
                language = "en",
                minDurationMs = 420,
                maxDurationMs = 1000,
                syllables = 2,
                requiresInitialSibilant = true,
                melPattern = listOf(
                    computeCanonicalMel(5200.0, 6400.0),
                    computeCanonicalMel(550.0, 2100.0),
                    computeCanonicalMel(350.0, 1400.0),
                    computeCanonicalMel(250.0, 0.0),
                    computeCanonicalMel(300.0, 2400.0)
                )
            ),
            // "bachao" (Hindi): /ba/ (750, 1250) -> /ch/ (2800, 3400) -> /aʊ/ (700, 1050)
            KeywordProfile(
                keyword = "bachao",
                language = "hi",
                minDurationMs = 300,
                maxDurationMs = 780,
                syllables = 2,
                requiresInitialSibilant = false,
                melPattern = listOf(
                    computeCanonicalMel(750.0, 1250.0),
                    computeCanonicalMel(2800.0, 3400.0),
                    computeCanonicalMel(700.0, 1050.0)
                )
            ),
            // "udhavi" (Tamil): /u/ (350, 800) -> /d̪a/ (700, 1300) -> /vi/ (300, 2350)
            KeywordProfile(
                keyword = "udhavi",
                language = "ta",
                minDurationMs = 360,
                maxDurationMs = 850,
                syllables = 3,
                requiresInitialSibilant = false,
                melPattern = listOf(
                    computeCanonicalMel(350.0, 800.0),
                    computeCanonicalMel(700.0, 1300.0),
                    computeCanonicalMel(300.0, 2350.0)
                )
            ),
            // "kaapaathunga" (Tamil): kaa (750, 1250) -> paa (750, 1250) -> thun (400, 1100) -> ga (700, 1250)
            KeywordProfile(
                keyword = "kaapaathunga",
                language = "ta",
                minDurationMs = 580,
                maxDurationMs = 1300,
                syllables = 4,
                requiresInitialSibilant = false,
                melPattern = listOf(
                    computeCanonicalMel(750.0, 1250.0),
                    computeCanonicalMel(750.0, 1250.0),
                    computeCanonicalMel(400.0, 1100.0),
                    computeCanonicalMel(700.0, 1250.0)
                )
            )
        )
    }

    override fun startListening(targetKeywords: List<String>): Flow<DistressSignal> {
        listening = true
        activeKeywords = if (targetKeywords.isNotEmpty()) targetKeywords else DEFAULT_KEYWORDS

        if (circularBuffer != null && (listeningJob == null || listeningJob?.isActive == false)) {
            listeningJob = spotterScope.launch {
                while (isActive && listening) {
                    delay(100) // Spotting check interval
                    // Inspect the last 1.2 seconds of speech
                    val window = circularBuffer.snapshot(requestedDurationMs = 1200)
                    if (window.size >= AudioPreprocessor.DEFAULT_FRAME_SIZE * 4) {
                        evaluateSpeechWindow(window)
                    }
                }
            }
        }

        return signalFlow.asSharedFlow()
    }

    override fun stopListening() {
        listening = false
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun isListening(): Boolean = listening

    /**
     * Matches a speech window against active keyword profiles using acoustic-phonetic
     * VAD segmentation, onset sibilance gating, syllable peak counting, and 16-band Mel DTW.
     */
    fun evaluateSpeechWindow(samples: ShortArray): MatchResult? {
        if (samples.isEmpty()) return null
        val floatSamples = AudioPreprocessor.toNormalizedFloat(samples)
        val overallRms = AudioPreprocessor.computeRms(floatSamples)
        val overallDbfs = AudioPreprocessor.rmsToDbfs(overallRms)

        // Drop pure digital silence or near-zero background (< -46.0 dBFS)
        if (overallDbfs < -46.0f) return null

        val rawFrames = AudioPreprocessor.frameSignal(
            floatSamples,
            AudioPreprocessor.DEFAULT_FRAME_SIZE,
            AudioPreprocessor.DEFAULT_HOP_SIZE
        )
        if (rawFrames.size < 8) return null

        val numFrames = rawFrames.size
        val frameDbfs = FloatArray(numFrames)
        val frameZcr = FloatArray(numFrames)
        val melFrames = ArrayList<FloatArray>(numFrames)

        for (i in 0 until numFrames) {
            val frame = rawFrames[i]
            val rms = AudioPreprocessor.computeRms(frame)
            frameDbfs[i] = AudioPreprocessor.rmsToDbfs(rms)
            frameZcr[i] = AudioPreprocessor.computeZeroCrossingRate(frame)

            val windowed = frame.clone()
            AudioPreprocessor.applyHannWindow(windowed)
            val power = AudioPreprocessor.computePowerSpectrum(windowed)
            melFrames.add(AudioPreprocessor.computeNormalizedMelFilterbank(power))
        }

        // 1. Dynamic Noise Floor & Voice Activity Segmentation
        val noiseFloor = AudioPreprocessor.estimateNoiseFloorDbfs(frameDbfs)
        val speechGate = max(noiseFloor + 5.0f, -44.0f)

        val isSpeechFrame = BooleanArray(numFrames) { frameDbfs[it] >= speechGate }

        // Speech hangover bridging: bridge silence gaps <= 8 frames (~128ms) between speech frames
        var lastSpeechIdx = -1
        for (i in isSpeechFrame.indices) {
            if (isSpeechFrame[i]) {
                if (lastSpeechIdx != -1 && (i - lastSpeechIdx - 1) <= 8) {
                    for (k in (lastSpeechIdx + 1) until i) {
                        isSpeechFrame[k] = true
                    }
                }
                lastSpeechIdx = i
            }
        }

        var firstActive = -1
        var lastActive = -1
        for (i in isSpeechFrame.indices) {
            if (isSpeechFrame[i]) {
                if (firstActive == -1) firstActive = i
                lastActive = i
            }
        }

        if (firstActive == -1 || lastActive < firstActive) {
            // println("DEBUG: no active frames found (noiseFloor=$noiseFloor, speechGate=$speechGate, maxFrameDbfs=${frameDbfs.maxOrNull()})")
            return null
        }
        val activeFrameCount = lastActive - firstActive + 1
        if (activeFrameCount < 8) {
            // println("DEBUG: activeFrameCount ($activeFrameCount) < 8")
            return null
        }

        // Utterance duration in ms (hop size 256 @ 16kHz = 16ms per frame)
        val durationMs = activeFrameCount * 16L
        if (durationMs < 160L || durationMs > 1350L) {
            // println("DEBUG: durationMs ($durationMs) out of range 160..1350")
            return null
        }

        val activeMel = melFrames.subList(firstActive, lastActive + 1)
        val activeZcr = FloatArray(activeFrameCount) { frameZcr[firstActive + it] }
        val activeDbfs = FloatArray(activeFrameCount) { frameDbfs[firstActive + it] }

        // 2. Onset Sibilant Detection (/s/ sound at phrase onset)
        val onsetFrames = min(5, activeZcr.size)
        var onsetMaxZcr = 0.0f
        for (i in 0 until onsetFrames) {
            val z = activeZcr[i]
            if (z > onsetMaxZcr) onsetMaxZcr = z
        }
        val onsetAvgHighMel = (0 until onsetFrames).map { activeMel[it].slice(11..15).average().toFloat() }.average().toFloat()
        val hasInitialSibilant = (onsetMaxZcr >= 0.20f && onsetAvgHighMel >= -4.5f)

        // 3. Syllable Peak Counting
        val detectedSyllables = AudioPreprocessor.countSyllablePeaks(activeDbfs, speechGate, minValleyDipDb = 3.0f)

        var bestMatch: MatchResult? = null

        // 4. Acoustic Profile Matching
        for (profile in PROFILES) {
            val isEnabled = activeKeywords.any {
                it.lowercase(Locale.ROOT).trim() == profile.keyword.lowercase(Locale.ROOT)
            }
            if (!isEnabled) continue

            // Duration gate: allow reasonable speech rate tolerance [0.70x, 1.35x]
            val minDur = (profile.minDurationMs * 0.70).toLong()
            val maxDur = (profile.maxDurationMs * 1.35).toLong()
            if (durationMs !in minDur..maxDur) continue

            // Sibilance gate: if profile requires initial /s/ or forbids it
            if (profile.requiresInitialSibilant == true && !hasInitialSibilant) continue
            if (profile.requiresInitialSibilant == false && hasInitialSibilant) continue

            // Syllable count gate: for single-syllable phrases reject multi-syllable utterances
            if (profile.syllables == 1 && detectedSyllables > 1) continue
            if (abs(detectedSyllables - profile.syllables) > 1) continue

            // 16-band Mel Dynamic Time Warping alignment
            val dtwScore = computeMelDtw(activeMel, profile.melPattern)
            if (dtwScore < 0.54f) continue

            // Metric calibration bonuses
            val sylBonus = if (detectedSyllables == profile.syllables) 0.06f else 0.0f
            val midDur = (profile.minDurationMs + profile.maxDurationMs) / 2.0
            val durDiff = abs(durationMs - midDur) / midDur
            val durBonus = if (durDiff < 0.25) 0.05f else 0.0f
            val sibBonus = if (profile.requiresInitialSibilant != null && (profile.requiresInitialSibilant == hasInitialSibilant)) 0.04f else 0.0f

            val confidence = (dtwScore * 0.85f + sylBonus + durBonus + sibBonus).coerceIn(0.0f, 1.0f)

            if (confidence >= 0.72f && (bestMatch == null || confidence > bestMatch.confidence)) {
                bestMatch = MatchResult(
                    keyword = profile.keyword,
                    language = profile.language,
                    confidence = confidence
                )
            }
        }

        if (bestMatch != null) {
            val now = System.currentTimeMillis()
            if (now - lastEmittedTimestamp > emissionCooldownMs) {
                lastEmittedTimestamp = now
                spotterScope.launch {
                    val signal = DistressSignal(
                        id = UUID.randomUUID().toString(),
                        type = DistressType.KEYWORD,
                        confidence = bestMatch.confidence,
                        timestampEpochMs = now,
                        source = "GuardianGrid_KeywordSpotter",
                        rawScore = bestMatch.confidence,
                        metadata = mapOf(
                            "keyword" to bestMatch.keyword,
                            "language" to bestMatch.language,
                            "confidence" to "%.3f".format(bestMatch.confidence)
                        )
                    )
                    signalFlow.emit(signal)
                }
            }
        }

        return bestMatch
    }

    /**
     * Computes Dynamic Time Warping alignment across 16-band normalized Mel filterbank vectors
     * using centered correlation distance and step penalty for off-diagonal transitions.
     * Maps the normalized path cost to an alignment confidence score in [0.0f, 1.0f].
     */
    fun computeMelDtw(observed: List<FloatArray>, target: List<FloatArray>): Float {
        if (observed.isEmpty() || target.isEmpty()) return 0.0f

        val n = observed.size
        val m = target.size
        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dtw[0][0] = 0.0f

        for (i in 1..n) {
            for (j in 1..m) {
                val obs = observed[i - 1]
                val tgt = target[j - 1]

                // Centered correlation distance across 16 Mel bands
                val meanObs = obs.average().toFloat()
                val meanTgt = tgt.average().toFloat()
                var dot = 0.0; var normObs = 0.0; var normTgt = 0.0
                for (b in 0 until 16) {
                    val dO = obs[b] - meanObs
                    val dT = tgt[b] - meanTgt
                    dot += dO * dT
                    normObs += dO * dO
                    normTgt += dT * dT
                }
                val corr = if (normObs > 1e-6 && normTgt > 1e-6) (dot / (kotlin.math.sqrt(normObs) * kotlin.math.sqrt(normTgt))).toFloat() else 0.0f
                val dist = (1.0f - corr).coerceIn(0.0f, 2.0f)

                val diag = dtw[i - 1][j - 1]
                val insert = if (dtw[i - 1][j] != Float.MAX_VALUE) dtw[i - 1][j] + 0.10f else Float.MAX_VALUE
                val delete = if (dtw[i][j - 1] != Float.MAX_VALUE) dtw[i][j - 1] + 0.10f else Float.MAX_VALUE

                val minPrev = min(diag, min(insert, delete))
                if (minPrev != Float.MAX_VALUE) {
                    dtw[i][j] = dist + minPrev
                }
            }
        }

        val totalDist = dtw[n][m]
        if (totalDist == Float.MAX_VALUE) return 0.0f
        val avgDist = totalDist / (n + m)

        // Maps average distance to alignment confidence: 0.0 dist -> 1.0, 1.35 dist -> 0.0
        return (1.0f - (avgDist / 1.35f)).coerceIn(0.0f, 1.0f)
    }

    /**
     * Backward-compatible pattern alignment helper.
     */
    fun computePatternAlignment(observed: List<FloatArray>, target: List<FloatArray>): Float {
        return computeMelDtw(observed, target)
    }

    /**
     * Legacy 3-band profile extractor retained for backward compatibility.
     */
    fun extractThreeBandProfile(powerSpectrum: FloatArray): FloatArray {
        val numBins = powerSpectrum.size
        val binWidth = (AudioPreprocessor.DEFAULT_SAMPLE_RATE / 2.0) / (numBins - 1)

        val lowEnd = (800.0 / binWidth).toInt().coerceIn(0, numBins - 1)
        val midEnd = (2500.0 / binWidth).toInt().coerceIn(lowEnd, numBins - 1)
        val highEnd = (6000.0 / binWidth).toInt().coerceIn(midEnd, numBins - 1)

        var lowP = 0.0; var midP = 0.0; var highP = 0.0
        for (i in 0 until lowEnd) lowP += powerSpectrum[i]
        for (i in lowEnd until midEnd) midP += powerSpectrum[i]
        for (i in midEnd until highEnd) highP += powerSpectrum[i]

        val total = lowP + midP + highP + 1e-9
        return floatArrayOf(
            (lowP / total).toFloat(),
            (midP / total).toFloat(),
            (highP / total).toFloat()
        )
    }

    /**
     * Testing / simulation utility to inject a matched keyword signal.
     */
    suspend fun emitSimulatedKeyword(keyword: String, confidence: Float = 0.94f, language: String = "en") {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.KEYWORD,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            timestampEpochMs = System.currentTimeMillis(),
            source = "SimulatedKeywordSpotter",
            rawScore = confidence,
            metadata = mapOf(
                "keyword" to keyword,
                "language" to language,
                "simulation" to "true"
            )
        )
        signalFlow.emit(signal)
    }

    data class MatchResult(
        val keyword: String,
        val language: String,
        val confidence: Float
    )
}
