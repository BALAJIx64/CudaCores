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

    // Core Subsystem Components (10-second volatile RAM rolling buffer)
    val circularBuffer: CircularAudioBuffer = CircularAudioBuffer(durationMs = 10_000L, sampleRate = 16000)
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
        val captureResult = captureManager.startCapture()
        return if (captureResult.isSuccess) {
            screamClassifier.startMonitoring()
        } else {
            screamClassifier.stopMonitoring()
            screamClassifier.observeSignalsOnly()
        }
    }

    override fun stopMonitoring() {
        screamClassifier.stopMonitoring()
        captureManager.stopCapture()
    }

    override fun isMonitoring(): Boolean = screamClassifier.isMonitoring() && captureManager.isCapturing

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

    /**
     * Initiates a continuous emergency incident audio relay session:
     * - Immediately snapshots the previous 10 seconds from [circularBuffer] as one unsplit WAV file
     * - Continuously captures subsequent 10-second (160,000 sample @ 16 kHz) chunks without restarting mic capture
     * - Maintains a complete chronological incident WAV file on disk until stopped or max duration reached.
     */
    fun startIncidentAudioRelay(
        incidentId: String,
        preTriggerDurationMs: Long = 10_000L,
        chunkDurationMs: Long = 10_000L,
        maxRecordingDurationMs: Long = 300_000L
    ): IncidentAudioRelaySession {
        return evidenceRecorder.startIncidentRelaySession(
            incidentId = incidentId,
            preTriggerDurationMs = preTriggerDurationMs,
            chunkDurationMs = chunkDurationMs,
            maxRecordingDurationMs = maxRecordingDurationMs
        )
    }

    /**
     * Cleans up satisfied source chunks only when BOTH delivered and past retention window.
     */
    fun cleanupSatisfiedEvidenceChunks(
        deliveredFilePaths: Set<String>,
        retentionWindowMs: Long = TwoStageAudioEvidenceRecorder.DEFAULT_CHUNK_RETENTION_MS,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int = evidenceRecorder.cleanupSatisfiedChunks(deliveredFilePaths, retentionWindowMs, nowEpochMs)

    // --- Simulation & Testing Bridge ---

    /**
     * Testing / simulation utility to inject a distress signal directly into the flow.
     */
    suspend fun emitSimulatedDistress(type: DistressType = DistressType.SCREAM, confidence: Float = 0.94f) {
        screamClassifier.emitSimulatedDistress(confidence, "SimulatedAudioDetector")
    }
}
