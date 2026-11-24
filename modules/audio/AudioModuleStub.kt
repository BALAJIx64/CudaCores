package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.AudioDistressDetector
import com.guardiangrid.core.interfaces.AudioEvidenceRecorder
import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Unified production facade for GuardianGrid's Audio Intelligence subsystem.
 *
 * Implements:
 * - [AudioDistressDetector]: Pretrained-compatible AI & acoustic sound-event vocal distress classifier.
 * - [AudioEvidenceRecorder]: Two-stage privacy-preserving audio evidence capture protocol.
 * - Exposes real-time [LiveAudioMetrics] StateFlow from [AudioCaptureManager].
 *
 * Owned by Member 1 (modules/audio).
 */
class AudioModuleStub(
    cacheDirectory: File? = null,
    modelDirectory: File? = null
) : AudioDistressDetector, AudioEvidenceRecorder {

    // Core Subsystem Components
    val circularBuffer: CircularAudioBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
    val captureManager: AudioCaptureManager = AudioCaptureManager(sampleRate = 16000, circularBuffer = circularBuffer)
    val screamClassifier: ScreamDistressClassifier = ScreamDistressClassifier(modelDirectory, circularBuffer)
    val evidenceRecorder: TwoStageAudioEvidenceRecorder = TwoStageAudioEvidenceRecorder(circularBuffer, captureManager, cacheDirectory)

    val liveMetrics: StateFlow<LiveAudioMetrics>
        get() = captureManager.liveMetrics

    // --- AudioDistressDetector Implementation ---

    /**
     * Start continuous audio classification for vocal distress (screams, shouts, and crying).
     * Emits structured [DistressSignal] instances when distress exceeds calibrated threshold.
     */
    override fun startMonitoring(): Flow<DistressSignal> {
        captureManager.startCapture()
        return screamClassifier.startMonitoring()
    }

    override fun stopMonitoring() {
        screamClassifier.stopMonitoring()
        captureManager.stopCapture()
    }

    override fun isMonitoring(): Boolean = screamClassifier.isMonitoring()

    override fun setSensitivity(sensitivity: Float) {
        screamClassifier.setSensitivity(sensitivity)
    }

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
     * Testing / simulation utility to inject a distress signal directly into the flow.
     */
    suspend fun emitSimulatedDistress(type: DistressType = DistressType.SCREAM, confidence: Float = 0.94f) {
        screamClassifier.emitSimulatedDistress(confidence, "SimulatedAudioDetector")
    }
}
