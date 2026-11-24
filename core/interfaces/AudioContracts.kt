package com.guardiangrid.core.interfaces

import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.DistressSignal
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Interface for AI-based scream and acoustic distress sound classification.
 * Owned by Member 1 (modules/audio).
 */
interface AudioDistressDetector {
    /**
     * Start continuous or windowed audio classification.
     * Emits distress signals when screams or distress sounds exceed threshold.
     */
    fun startMonitoring(): Flow<DistressSignal>

    /**
     * Terminate audio stream ingestion and release microphone resources.
     */
    fun stopMonitoring()

    /**
     * Query active monitoring status.
     */
    fun isMonitoring(): Boolean

    /**
     * Adjust detection sensitivity threshold (0.0f - 1.0f).
     */
    fun setSensitivity(sensitivity: Float)
}

/**
 * Interface for two-stage emergency audio intelligence and recording.
 * Owned by Member 1 (modules/audio).
 */
interface AudioEvidenceRecorder {
    /**
     * Stage 1: Captures a short historical rolling buffer upon initial suspicious trigger.
     */
    suspend fun captureStageOneBuffer(durationMs: Long = 5000): Result<AudioSegment>

    /**
     * Stage 2: Initiates high-confidence recording or segmented evidence streaming.
     */
    suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment>

    /**
     * Stop and finalize any active audio recording.
     */
    fun stopRecording()
}

/**
 * Interface for dedicated emergency keyword spotting.
 * Owned by Member 1 (modules/audio).
 */
interface KeywordSpotter {
    /**
     * Start listening for configured distress keywords.
     */
    fun startListening(targetKeywords: List<String>): Flow<DistressSignal>

    /**
     * Terminate keyword spotting loop and release resources.
     */
    fun stopListening()

    /**
     * Query active listening status.
     */
    fun isListening(): Boolean
}
