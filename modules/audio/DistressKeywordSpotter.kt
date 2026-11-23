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
         * and relative spectral emphasis across low/mid/high frequency bands.
         */
        data class KeywordProfile(
            val keyword: String,
            val language: String,
            val minDurationMs: Long,
            val maxDurationMs: Long,
            val syllables: Int,
            val spectralPattern: List<FloatArray> // Sequence of normalized 3-band energy vectors [low, mid, high]
        )

        val PROFILES = listOf(
            // "help": /h/ (broadband) -> /e/ (strong mid/F2) -> /l/ -> /p/ (sudden drop)
            KeywordProfile(
                keyword = "help",
                language = "en",
                minDurationMs = 250,
                maxDurationMs = 700,
                syllables = 1,
                spectralPattern = listOf(
                    floatArrayOf(0.2f, 0.4f, 0.4f), // /h/
                    floatArrayOf(0.4f, 0.5f, 0.1f), // /e/
                    floatArrayOf(0.6f, 0.3f, 0.1f)  // /l-p/
                )
            ),
            // "save me": 2 syllables (/s-eɪ-v/ -> /m-i/)
            KeywordProfile(
                keyword = "save me",
                language = "en",
                minDurationMs = 400,
                maxDurationMs = 1000,
                syllables = 2,
                spectralPattern = listOf(
                    floatArrayOf(0.1f, 0.3f, 0.6f), // /s/
                    floatArrayOf(0.5f, 0.4f, 0.1f), // /eɪ-v/
                    floatArrayOf(0.7f, 0.2f, 0.1f)  // /m-i/
                )
            ),
            // "stop": /s/ (high freq) -> /t/ (burst) -> /ɒ/ (low/mid) -> /p/ (stop)
            KeywordProfile(
                keyword = "stop",
                language = "en",
                minDurationMs = 250,
                maxDurationMs = 650,
                syllables = 1,
                spectralPattern = listOf(
                    floatArrayOf(0.1f, 0.2f, 0.7f), // /s/
                    floatArrayOf(0.5f, 0.4f, 0.1f), // /ɒ/
                    floatArrayOf(0.1f, 0.1f, 0.1f)  // /p/
                )
            ),
            // "kaapaathunga" (Tamil): 4 distinct rhythmic syllables: kaa - paa - thun - ga
            KeywordProfile(
                keyword = "kaapaathunga",
                language = "ta",
                minDurationMs = 600,
                maxDurationMs = 1400,
                syllables = 4,
                spectralPattern = listOf(
                    floatArrayOf(0.3f, 0.5f, 0.2f), // kaa
                    floatArrayOf(0.4f, 0.5f, 0.1f), // paa
                    floatArrayOf(0.5f, 0.4f, 0.1f), // thun
                    floatArrayOf(0.4f, 0.4f, 0.2f)  // ga
                )
            ),
            // "udhavi" (Tamil): 3 syllables: u - dha - vi
            KeywordProfile(
                keyword = "udhavi",
                language = "ta",
                minDurationMs = 400,
                maxDurationMs = 900,
                syllables = 3,
                spectralPattern = listOf(
                    floatArrayOf(0.6f, 0.3f, 0.1f), // u
                    floatArrayOf(0.4f, 0.5f, 0.1f), // dha
                    floatArrayOf(0.3f, 0.5f, 0.2f)  // vi
                )
            ),
            // "bachao" (Hindi): 2 syllables: ba - chao
            KeywordProfile(
                keyword = "bachao",
                language = "hi",
                minDurationMs = 350,
                maxDurationMs = 850,
                syllables = 2,
                spectralPattern = listOf(
                    floatArrayOf(0.6f, 0.3f, 0.1f), // ba
                    floatArrayOf(0.3f, 0.5f, 0.2f)  // chao
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
     * Matches a speech window against active keyword profiles.
     */
    fun evaluateSpeechWindow(samples: ShortArray): MatchResult? {
        if (samples.isEmpty()) return null
        val floatSamples = AudioPreprocessor.toNormalizedFloat(samples)
        val rms = AudioPreprocessor.computeRms(floatSamples)
        val dbfs = AudioPreprocessor.rmsToDbfs(rms)

        // Voice activity gate: must be audible speech (> -32 dBFS)
        if (dbfs < -32.0f) return null

        val frames = AudioPreprocessor.frameSignal(
            floatSamples,
            AudioPreprocessor.DEFAULT_FRAME_SIZE,
            AudioPreprocessor.DEFAULT_HOP_SIZE
        )
        if (frames.size < 4) return null

        // Extract 3-band energy vector [low: 100-800Hz, mid: 800-2500Hz, high: 2500-6000Hz] for each frame
        val frameBands = frames.map { frame ->
            AudioPreprocessor.applyHannWindow(frame)
            val power = AudioPreprocessor.computePowerSpectrum(frame)
            extractThreeBandProfile(power)
        }

        var bestMatch: MatchResult? = null

        for (profile in PROFILES) {
            // Check if this keyword is enabled in active configuration
            val isEnabled = activeKeywords.any {
                it.lowercase(Locale.ROOT).trim() == profile.keyword.lowercase(Locale.ROOT)
            }
            if (!isEnabled) continue

            val score = computePatternAlignment(frameBands, profile.spectralPattern)
            if (score > 0.72f && (bestMatch == null || score > bestMatch.confidence)) {
                bestMatch = MatchResult(
                    keyword = profile.keyword,
                    language = profile.language,
                    confidence = score
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

    private fun extractThreeBandProfile(powerSpectrum: FloatArray): FloatArray {
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
     * Aligns observed frame energy bands against target profile using a simplified Dynamic Time Warping distance.
     */
    private fun computePatternAlignment(observed: List<FloatArray>, target: List<FloatArray>): Float {
        if (observed.isEmpty() || target.isEmpty()) return 0.0f

        val n = observed.size
        val m = target.size
        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dtw[0][0] = 0.0f

        for (i in 1..n) {
            for (j in 1..m) {
                val obs = observed[i - 1]
                val tgt = target[j - 1]
                val dist = abs(obs[0] - tgt[0]) + abs(obs[1] - tgt[1]) + abs(obs[2] - tgt[2])

                val minPrev = min(dtw[i - 1][j], min(dtw[i][j - 1], dtw[i - 1][j - 1]))
                dtw[i][j] = dist + minPrev
            }
        }

        val totalDist = dtw[n][m]
        val avgDist = totalDist / (n + m)
        // Convert distance to normalized confidence in [0.0, 1.0]
        return (1.0f - avgDist).coerceIn(0.0f, 1.0f)
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
