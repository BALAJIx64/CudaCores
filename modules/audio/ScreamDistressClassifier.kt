package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.AudioDistressDetector
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
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Centralized, configurable thresholds for GuardianGrid acoustic distress detection.
 * Tuned conservatively to suppress speech, music, ambient noise, and brief transients
 * while preserving rapid detection of genuine sustained screams.
 */
data class AudioDetectionConfig(
    val defaultSensitivity: Float = 0.55f,
    val agcMinPeakThreshold: Float = 0.015f,
    val agcMaxGainFactor: Float = 18.0f,
    val globalMinRmsDbfs: Float = -46.0f,
    val frameMinDbfs: Float = -54.0f,
    val screamBandMinRatio: Float = 0.36f,
    val spectralCentroidMinHz: Float = 1400.0f,
    val speechDominantLowBandRatio: Float = 0.39f,
    val maxSpeechPercentage: Float = 0.60f,
    val maxSpectralFlatnessForScream: Float = 0.48f,
    val minScreamFrames: Int = 6,
    val minConsecutiveScreamRun: Int = 3,
    val minScreamPercentage: Float = 0.27f,
    val tfliteBaseDistressFloor: Float = 0.38f,
    val tfliteMinDistressFloor: Float = 0.16f,
    val baseScoreThreshold: Float = 0.66f,
    val sensitivityThresholdSpan: Float = 0.34f,
    val minConsecutiveWindows: Int = 2,
    val moderateConsecutiveWindows: Int = 3,
    val strongRawScoreFloor: Float = 0.65f,
    val hysteresisMargin: Float = 0.08f,
    val analysisIntervalMs: Long = 50L,
    val emissionCooldownMs: Long = 4000L
)

/**
 * Pretrained-compatible AI and acoustic sound-event classifier for screams and vocal distress.
 *
 * Implements [AudioDistressDetector].
 *
 * Distinct from Keyword Spotting:
 * - This classifier identifies non-verbal acoustic distress (screams, yells, shrieks, sobbing/wailing)
 *   rather than lexical speech phrases.
 *
 * Owned by Member 1 (modules/audio).
 */
class ScreamDistressClassifier(
    private val modelDirectory: File? = null,
    private val circularBuffer: CircularAudioBuffer? = null,
    private var config: AudioDetectionConfig = AudioDetectionConfig()
) : AudioDistressDetector {

    private var monitoring = false
    private var sensitivity: Float = config.defaultSensitivity
    private val signalFlow = MutableSharedFlow<DistressSignal>(replay = 1, extraBufferCapacity = 64)

    private var analysisJob: Job? = null
    private val classifierScope = CoroutineScope(Dispatchers.Default)

    // State tracking for temporal persistence and hysteresis filtering
    private var consecutiveDistressFrames = 0
    private var lastEmittedTimestamp = 0L

    fun updateConfig(newConfig: AudioDetectionConfig) {
        config = newConfig
        sensitivity = sensitivity.coerceIn(0.0f, 1.0f)
    }

    fun getConfig(): AudioDetectionConfig = config

    // Model metadata
    var activeModelName: String = "GuardianGrid_AcousticDistressDSP_v1.0"
        private set
    var isTfliteModelLoaded: Boolean = false
        private set
    var lastInferenceStatus: String = "INITIALIZING"
        private set
    var tfliteInterpreter: Any? = null
        private set

    init {
        attemptLoadPretrainedModel()
    }

    /**
     * Checks for pretrained .tflite models and initializes TensorFlow Lite interpreter.
     * Makes runtime and inference status transparently observable.
     */
    private fun attemptLoadPretrainedModel() {
        try {
            val candidates = listOfNotNull(
                modelDirectory,
                File("models/audio"),
                File("../models/audio"),
                File("app/src/main/assets/models"),
                File("src/main/assets/models")
            )
            val modelDir = candidates.firstOrNull { it.exists() && it.isDirectory }
            if (modelDir != null) {
                val tfliteFiles = modelDir.listFiles { _, name -> name.endsWith(".tflite") }
                if (!tfliteFiles.isNullOrEmpty()) {
                    val modelFile = tfliteFiles.first()
                    activeModelName = "TFLite_${modelFile.nameWithoutExtension}"
                    try {
                        val fileInputStream = FileInputStream(modelFile)
                        val fileChannel = fileInputStream.channel
                        val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
                        val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
                        val constructor = interpreterClass.getConstructor(ByteBuffer::class.java)
                        tfliteInterpreter = constructor.newInstance(buffer)
                        isTfliteModelLoaded = true
                        lastInferenceStatus = "TFLITE_INFERENCE_ACTIVE (${modelFile.name})"
                        fileInputStream.close()
                    } catch (t: Throwable) {
                        isTfliteModelLoaded = false
                        tfliteInterpreter = null
                        lastInferenceStatus = "EDGE_DSP_FALLBACK (${t.javaClass.simpleName}: ${t.message ?: "Native JNI not found on host JVM"})"
                    }
                } else {
                    lastInferenceStatus = "EDGE_DSP_ACTIVE (No .tflite files in directory)"
                }
            } else {
                lastInferenceStatus = "EDGE_DSP_ACTIVE (Model directory not found)"
            }
        } catch (e: Exception) {
            isTfliteModelLoaded = false
            tfliteInterpreter = null
            lastInferenceStatus = "EDGE_DSP_ACTIVE (Init error: ${e.message})"
        }
    }

    override fun startMonitoring(): Flow<DistressSignal> {
        monitoring = true
        consecutiveDistressFrames = 0

        // If circular buffer is supplied, start background evaluation loop
        if (circularBuffer != null && (analysisJob == null || analysisJob?.isActive == false)) {
            analysisJob = classifierScope.launch {
                val frameSamples = AudioPreprocessor.DEFAULT_FRAME_SIZE
                while (isActive && monitoring) {
                    delay(config.analysisIntervalMs) // Analyze ~20 times per second for low latency
                    val recentAudio = circularBuffer.snapshot(requestedDurationMs = 1000)
                    if (recentAudio.size >= frameSamples) {
                        val score = evaluateAudioSamples(recentAudio)
                        checkAndEmitDistress(score)
                    }
                }
            }
        }

        return signalFlow.asSharedFlow()
    }

    override fun stopMonitoring() {
        monitoring = false
        analysisJob?.cancel()
        analysisJob = null
        consecutiveDistressFrames = 0
    }

    fun observeSignalsOnly(): Flow<DistressSignal> = signalFlow.asSharedFlow()

    override fun isMonitoring(): Boolean = monitoring

    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0.0f, 1.0f)
    }

    fun getSensitivity(): Float = sensitivity

    /**
     * Executes real model inference on 16 kHz audio window via TensorFlow Lite interpreter.
     */
    fun runTfliteInference(floatSamples: FloatArray): Float? {
        val interpreter = tfliteInterpreter ?: return null
        return try {
            val targetSamples = 15600 // YAMNet 0.975s window at 16 kHz
            val inputWindow = if (floatSamples.size >= targetSamples) {
                floatSamples.copyOfRange(floatSamples.size - targetSamples, floatSamples.size)
            } else {
                val padded = FloatArray(targetSamples)
                System.arraycopy(floatSamples, 0, padded, targetSamples - floatSamples.size, floatSamples.size)
                padded
            }

            // Global RMS gate before AGC so quiet room noise is never amplified into false triggers
            val rawRms = AudioPreprocessor.computeRms(inputWindow)
            if (AudioPreprocessor.rmsToDbfs(rawRms) < config.globalMinRmsDbfs) {
                return 0.0f
            }

            // Bounded Automatic Gain Control (AGC)
            var peak = 0.0f
            for (s in inputWindow) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            if (peak >= config.agcMinPeakThreshold && peak < 0.75f) {
                val scale = min(config.agcMaxGainFactor, 0.85f / peak)
                for (i in inputWindow.indices) {
                    inputWindow[i] = inputWindow[i] * scale
                }
            }

            val outputScores = Array(1) { FloatArray(521) }
            val runMethod = interpreter.javaClass.getMethod("run", Any::class.java, Any::class.java)
            runMethod.invoke(interpreter, inputWindow, outputScores)
            lastInferenceStatus = "TFLITE_YAMNET_ACTIVE ($activeModelName)"

            val screamScore = max(outputScores[0][11], outputScores[0][10]) // Screaming / Children shouting
            val maxShoutScore = max(outputScores[0][6], outputScores[0][9]) // Shout / Yell
            val speechScore = maxOf(outputScores[0][0], outputScores[0][1], outputScores[0][65]) // Speech / Kid speech / Babble
            val maxCryScore = max(outputScores[0][19], outputScores[0][20]) // Crying, sobbing / Baby cry
            val musicScore = max(outputScores[0][132], outputScores[0][133]) // Music / Musical instrument
            val laughterScore = outputScores[0][13] // Laughter
            val silenceScore = outputScores[0][494] // Silence

            val vocalDistressScore = maxOf(screamScore, maxShoutScore, maxCryScore * 0.85f)

            // Rejection rules:
            val isPureSilence = silenceScore > 0.55f && silenceScore > vocalDistressScore
            val isPureSpeech = speechScore > 0.40f && speechScore > (1.6f * vocalDistressScore) && screamScore < 0.18f
            val isMusicOrLaughter = (musicScore > 0.30f && musicScore > vocalDistressScore * 1.1f) ||
                (laughterScore > 0.25f && laughterScore > screamScore)

            // Conservative YAMNet distress floor scaled by configured sensitivity
            val distressFloor = (config.tfliteBaseDistressFloor - (sensitivity * 0.20f))
                .coerceIn(config.tfliteMinDistressFloor, config.tfliteBaseDistressFloor)

            if (!isPureSilence && !isPureSpeech && !isMusicOrLaughter && vocalDistressScore >= distressFloor) {
                (0.38f + (vocalDistressScore * 0.95f)).coerceIn(0.38f, 1.0f)
            } else {
                0.0f
            }
        } catch (t: Throwable) {
            lastInferenceStatus = "EDGE_DSP_FALLBACK_RUNTIME (${t.javaClass.simpleName}: ${t.message})"
            null
        }
    }

    /**
     * Analyzes a buffer of 16-bit PCM samples and outputs an acoustic scream/distress score (0.0 to 1.0).
     */
    fun evaluateAudioSamples(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0.0f

        val floatSamples = AudioPreprocessor.toNormalizedFloat(samples)

        // 1. If TFLite model is loaded and native runtime is available, run real model inference
        val tfliteScore = runTfliteInference(floatSamples)
        if (tfliteScore != null) {
            return tfliteScore
        }

        // 2. Global RMS gate before bounded AGC to ignore ambient mic self-noise / silence
        val globalRms = AudioPreprocessor.computeRms(floatSamples)
        if (AudioPreprocessor.rmsToDbfs(globalRms) < config.globalMinRmsDbfs) {
            return 0.0f
        }

        // Bounded Automatic Gain Control (AGC)
        var peak = 0.0f
        for (s in floatSamples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        val normalizedSamples = if (peak in config.agcMinPeakThreshold..0.75f) {
            val scaled = FloatArray(floatSamples.size)
            val factor = min(config.agcMaxGainFactor, 0.85f / peak)
            for (i in floatSamples.indices) scaled[i] = floatSamples[i] * factor
            scaled
        } else {
            floatSamples
        }

        val frames = AudioPreprocessor.frameSignal(
            normalizedSamples,
            AudioPreprocessor.DEFAULT_FRAME_SIZE,
            AudioPreprocessor.DEFAULT_HOP_SIZE
        )

        if (frames.isEmpty()) return 0.0f

        var activeFrames = 0
        var screamFrameCount = 0
        var speechDominantFrames = 0
        var broadbandNoiseFrames = 0
        var currentScreamRun = 0
        var maxScreamRun = 0

        for (frame in frames) {
            val rms = AudioPreprocessor.computeRms(frame)
            val dbfs = AudioPreprocessor.rmsToDbfs(rms)

            if (dbfs < config.frameMinDbfs) {
                currentScreamRun = 0
                continue
            }

            activeFrames++
            AudioPreprocessor.applyHannWindow(frame)
            val powerSpectrum = AudioPreprocessor.computePowerSpectrum(frame)
            val centroid = AudioPreprocessor.computeSpectralCentroid(powerSpectrum)
            val screamBandRatio = AudioPreprocessor.computeScreamBandRatio(powerSpectrum)
            val lowBandRatio = AudioPreprocessor.computeLowBandRatio(powerSpectrum)
            val flatness = computeSpectralFlatness(powerSpectrum)

            val isScreamFrame = screamBandRatio >= config.screamBandMinRatio &&
                screamBandRatio > lowBandRatio &&
                centroid >= config.spectralCentroidMinHz &&
                flatness <= config.maxSpectralFlatnessForScream

            if (isScreamFrame) {
                screamFrameCount++
                currentScreamRun++
                if (currentScreamRun > maxScreamRun) {
                    maxScreamRun = currentScreamRun
                }
            } else {
                currentScreamRun = 0
                if (flatness > config.maxSpectralFlatnessForScream) {
                    broadbandNoiseFrames++
                } else if (lowBandRatio >= config.speechDominantLowBandRatio && lowBandRatio > screamBandRatio) {
                    speechDominantFrames++
                }
            }
        }

        if (activeFrames == 0) return 0.0f

        val screamPercentage = screamFrameCount.toFloat() / activeFrames
        val speechPercentage = speechDominantFrames.toFloat() / activeFrames
        val noisePercentage = broadbandNoiseFrames.toFloat() / activeFrames

        val meetsSustainedCriteria = screamFrameCount >= config.minScreamFrames &&
            maxScreamRun >= config.minConsecutiveScreamRun &&
            screamPercentage >= config.minScreamPercentage &&
            speechPercentage < config.maxSpeechPercentage &&
            noisePercentage < 0.45f

        return if (meetsSustainedCriteria) {
            (0.42f + (screamPercentage * 0.58f)).coerceIn(0.42f, 1.0f)
        } else {
            (screamPercentage * 0.30f).coerceIn(0.0f, 0.18f)
        }
    }

    private fun computeSpectralFlatness(powerSpectrum: FloatArray): Float {
        if (powerSpectrum.size <= 4) return 0.0f
        var logSum = 0.0
        var arithSum = 0.0
        val count = powerSpectrum.size - 1
        for (i in 1 until powerSpectrum.size) {
            val p = max(powerSpectrum[i].toDouble(), 1e-12)
            logSum += ln(p)
            arithSum += p
        }
        val arithMean = arithSum / count
        if (arithMean <= 1e-12) return 0.0f
        val geoMean = exp(logSum / count)
        return (geoMean / arithMean).toFloat().coerceIn(0.0f, 1.0f)
    }

    /**
     * Checks if a score exceeds the calibrated sensitivity threshold with temporal persistence
     * and hysteresis (never triggers on a single noisy frame).
     */
    suspend fun checkAndEmitDistress(rawScore: Float, customSource: String? = null) {
        val effectiveThreshold = (config.baseScoreThreshold - (sensitivity * config.sensitivityThresholdSpan))
            .coerceIn(0.30f, 0.72f)

        if (rawScore >= effectiveThreshold) {
            consecutiveDistressFrames++
            val now = System.currentTimeMillis()
            val requiredFrames = if (rawScore >= config.strongRawScoreFloor) {
                config.minConsecutiveWindows
            } else {
                config.moderateConsecutiveWindows
            }

            if (consecutiveDistressFrames >= requiredFrames && (now - lastEmittedTimestamp) > config.emissionCooldownMs) {
                lastEmittedTimestamp = now
                val calibratedConfidence = (0.58f + (rawScore * 0.38f)).coerceIn(0.60f, 0.96f)

                val signal = DistressSignal(
                    id = UUID.randomUUID().toString(),
                    type = DistressType.SCREAM,
                    confidence = calibratedConfidence,
                    timestampEpochMs = now,
                    source = customSource ?: activeModelName,
                    rawScore = rawScore,
                    metadata = mapOf(
                        "engine" to if (tfliteInterpreter != null && isTfliteModelLoaded) "tflite_model_inference" else "edge_dsp_classifier",
                        "model" to activeModelName,
                        "inference_status" to lastInferenceStatus,
                        "raw_score" to "%.4f".format(rawScore),
                        "sensitivity" to "%.2f".format(sensitivity),
                        "consecutive_windows" to consecutiveDistressFrames.toString(),
                        "sample_rate" to "16000",
                        "window_samples" to "15600"
                    )
                )
                signalFlow.emit(signal)
            }
        } else if (rawScore < (effectiveThreshold - config.hysteresisMargin)) {
            consecutiveDistressFrames = max(0, consecutiveDistressFrames - 1)
        }
    }

    /**
     * Testing / simulation bridge to emit a synthetic or test-driven distress event.
     */
    suspend fun emitSimulatedDistress(confidence: Float = 0.92f, source: String = "SimulatedAcousticClassifier") {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = DistressType.SCREAM,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            timestampEpochMs = System.currentTimeMillis(),
            source = source,
            rawScore = confidence,
            metadata = mapOf("simulation" to "true", "engine" to "test_harness")
        )
        signalFlow.emit(signal)
    }
}
