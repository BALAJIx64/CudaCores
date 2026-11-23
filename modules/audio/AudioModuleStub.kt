package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.AudioDistressDetector
import com.guardiangrid.core.interfaces.AudioEvidenceRecorder
import com.guardiangrid.core.interfaces.KeywordSpotter
import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * Unified production facade for GuardianGrid's Audio Intelligence subsystem.
 *
 * Implements:
 * - [AudioDistressDetector]: Pretrained-compatible AI & acoustic sound-event scream/distress classifier.
 * - [KeywordSpotter]: Multi-language emergency hotword spotter (English, Tamil, Hindi).
 * - [AudioEvidenceRecorder]: Two-stage privacy-preserving audio evidence capture protocol.
 *
 * Maintains 100% backward compatibility with [MainActivity], Compose UI simulation controls,
 * and architectural unit tests in [tests/CoreArchitectureTest.kt].
 *
 * Owned by Member 1 (modules/audio).
 */
class AudioModuleStub(
    cacheDirectory: File? = null,
    modelDirectory: File? = null
) : AudioDistressDetector, KeywordSpotter, AudioEvidenceRecorder {

    // Core Subsystem Components
    val circularBuffer: CircularAudioBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
    val captureManager: AudioCaptureManager = AudioCaptureManager(sampleRate = 16000, circularBuffer = circularBuffer)
    val screamClassifier: ScreamDistressClassifier = ScreamDistressClassifier(modelDirectory, circularBuffer)
    val keywordSpotter: DistressKeywordSpotter = DistressKeywordSpotter(circularBuffer)
    val evidenceRecorder: TwoStageAudioEvidenceRecorder = TwoStageAudioEvidenceRecorder(circularBuffer, captureManager, cacheDirectory)

    // --- AudioDistressDetector Implementation ---

    /**
     * Start continuous audio classification and distress keyword spotting concurrently.
     * Merges both scream and keyword detection streams into a single Flow<DistressSignal>.
     */
    override fun startMonitoring(): Flow<DistressSignal> {
        captureManager.startCapture()
        val screamFlow = screamClassifier.startMonitoring().onEach { signal ->
            if (signal.type == DistressType.SCREAM) {
                keywordSpotter.notifyScreamDetected(1800L)
            }
        }
        val keywordFlow = keywordSpotter.startListening(DistressKeywordSpotter.DEFAULT_KEYWORDS)
        return merge(screamFlow, keywordFlow)
    }

    override fun stopMonitoring() {
        screamClassifier.stopMonitoring()
        keywordSpotter.stopListening()
        captureManager.stopCapture()
    }

    override fun isMonitoring(): Boolean = screamClassifier.isMonitoring() || keywordSpotter.isListening()

    override fun setSensitivity(sensitivity: Float) {
        screamClassifier.setSensitivity(sensitivity)
    }

    // --- KeywordSpotter Implementation ---

    override fun startListening(targetKeywords: List<String>): Flow<DistressSignal> {
        captureManager.startCapture()
        return keywordSpotter.startListening(targetKeywords)
    }

    override fun stopListening() {
        keywordSpotter.stopListening()
        if (!screamClassifier.isMonitoring()) {
            captureManager.stopCapture()
        }
    }

    override fun isListening(): Boolean = keywordSpotter.isListening()

    // --- AudioEvidenceRecorder Implementation ---

    override suspend fun captureStageOneBuffer(durationMs: Long): Result<AudioSegment> {
        return evidenceRecorder.captureStageOneBuffer(durationMs)
    }

    override suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment> {
        return evidenceRecorder.startStageTwoRecording(outputFile)
    }

    override fun stopRecording() {
        evidenceRecorder.stopRecording()
    }

    // --- Simulation & Testing Bridge ---

    /**
     * Testing and simulation bridge used by Compose Dashboard buttons to simulate distress detection.
     */
    suspend fun emitSimulatedDistress(type: DistressType, confidence: Float, source: String = "AudioIntelligenceFacade") {
        when (type) {
            DistressType.SCREAM -> screamClassifier.emitSimulatedDistress(confidence, source)
            DistressType.KEYWORD -> keywordSpotter.emitSimulatedKeyword("help", confidence)
            else -> screamClassifier.emitSimulatedDistress(confidence, source)
        }
    }
}
