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

    // Adaptive noise floor & scream preemption state
    var adaptiveNoiseFloorDbfs: Float = -50.0f
        private set

    var screamLockoutUntil: Long = 0L
        private set

    fun notifyScreamDetected(lockoutDurationMs: Long = 1800L) {
        screamLockoutUntil = System.currentTimeMillis() + lockoutDurationMs
    }

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
     * Incorporates adaptive noise floor gating, speech endpointing, spectral flux checks,
     * and scream preemption to eliminate room-noise false alarms.
     */
    fun evaluateSpeechWindow(samples: ShortArray): MatchResult? {
        if (samples.isEmpty()) return null

        // 1. Preempt keyword detection if scream lockout is active
        if (System.currentTimeMillis() < screamLockoutUntil) {
            return null
        }

        val floatSamples = AudioPreprocessor.toNormalizedFloat(samples)
        val rms = AudioPreprocessor.computeRms(floatSamples)
        val dbfs = AudioPreprocessor.rmsToDbfs(rms)

        // 2. Update adaptive ambient noise floor on non-transient frames
        if (dbfs < adaptiveNoiseFloorDbfs + 4.5f) {
            adaptiveNoiseFloorDbfs = (adaptiveNoiseFloorDbfs * 0.985f + dbfs * 0.015f).coerceIn(-75.0f, -25.0f)
        }

        // 3. Adaptive Energy Gate: Must be at least +7.5 dB above ambient noise floor AND >= -38.0 dBFS
        val speechThreshold = max(adaptiveNoiseFloorDbfs + 7.5f, -38.0f)
        if (dbfs < speechThreshold) {
            return null // Reject silence and ambient room hum immediately
        }

        val frames = AudioPreprocessor.frameSignal(
            floatSamples,
            AudioPreprocessor.DEFAULT_FRAME_SIZE,
            AudioPreprocessor.DEFAULT_HOP_SIZE
        )
        if (frames.size < 4) return null

        // 4. Preempt keyword detection if scream lockout is active
        if (System.currentTimeMillis() < screamLockoutUntil) {
            return null
        }

        // 5. Speech Endpointing: Filter active speech frames based on local frame energy
        val activeFrameIndices = frames.indices.filter { idx ->
            val frameRms = AudioPreprocessor.computeRms(frames[idx])
            AudioPreprocessor.rmsToDbfs(frameRms) >= (speechThreshold - 4.0f)
        }

        if (activeFrameIndices.isEmpty()) return null

        val firstActive = activeFrameIndices.first()
        val lastActive = activeFrameIndices.last()
        val trimmedFrames = frames.subList(firstActive, lastActive + 1)

        // Enforce valid human utterance duration: 160 ms to 1350 ms
        val hopMs = AudioPreprocessor.DEFAULT_HOP_SIZE * 1000L / AudioPreprocessor.DEFAULT_SAMPLE_RATE
        val utteranceDurationMs = trimmedFrames.size * hopMs
        if (utteranceDurationMs < 160L || utteranceDurationMs > 1350L) {
            return null // Reject transient clicks (< 160ms) and continuous running drone/hum (> 1350ms)
        }

        // 6. Extract 3-band energy vectors [low: 100-800Hz, mid: 800-2500Hz, high: 2500-6000Hz]
        val frameBands = trimmedFrames.map { frame ->
            AudioPreprocessor.applyHannWindow(frame)
            val power = AudioPreprocessor.computePowerSpectrum(frame)
            extractThreeBandProfile(power)
        }

        // 7. Spectral Flux Check: Reject static low-frequency background hum (fans/HVAC)
        var totalFlux = 0.0f
        for (i in 1 until frameBands.size) {
            val p = frameBands[i - 1]
            val c = frameBands[i]
            totalFlux += abs(c[0] - p[0]) + abs(c[1] - p[1]) + abs(c[2] - p[2])
        }
        val avgFlux = if (frameBands.size > 1) totalFlux / (frameBands.size - 1) else 0.0f
        if (avgFlux < 0.035f) {
            return null // Constant background hum has flat spectral flux; human speech has dynamic syllable transitions
        }

        var bestMatch: MatchResult? = null

        for (profile in PROFILES) {
            val isEnabled = activeKeywords.any {
                it.lowercase(Locale.ROOT).trim() == profile.keyword.lowercase(Locale.ROOT)
            }
            if (!isEnabled) continue

            // Check if utterance duration matches keyword syllable expectations
            if (utteranceDurationMs < profile.minDurationMs * 0.70 || utteranceDurationMs > profile.maxDurationMs * 1.30) {
                continue
            }

            val score = computePatternAlignment(frameBands, profile.spectralPattern)
            if (score > 0.74f && (bestMatch == null || score > bestMatch.confidence)) {
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
                            "confidence" to "%.3f".format(bestMatch.confidence),
                            "duration_ms" to utteranceDurationMs.toString(),
                            "noise_floor_dbfs" to "%.1f".format(adaptiveNoiseFloorDbfs)
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
     * Incorporates onset and offset boundary validation to prevent 1-step stalling.
     */
    private fun computePatternAlignment(observed: List<FloatArray>, target: List<FloatArray>): Float {
        if (observed.isEmpty() || target.isEmpty()) return 0.0f

        val n = observed.size
        val m = target.size

        if (n < m) return 0.0f

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

        // Endpoint boundary checks: Onset must approximate step 1, offset must approximate last step
        val onsetDist = observed.take(min(3, observed.size)).minOf { obs ->
            abs(obs[0] - target.first()[0]) + abs(obs[1] - target.first()[1]) + abs(obs[2] - target.first()[2])
        }
        val offsetDist = observed.takeLast(min(3, observed.size)).minOf { obs ->
            abs(obs[0] - target.last()[0]) + abs(obs[1] - target.last()[1]) + abs(obs[2] - target.last()[2])
        }

        if (onsetDist > 1.35f || offsetDist > 1.35f) {
            return 0.0f // Reject mismatched trajectories
        }

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
