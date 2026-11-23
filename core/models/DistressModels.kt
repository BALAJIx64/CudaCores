package com.guardiangrid.core.models

/**
 * Types of distress identified by multimodal detectors.
 */
enum class DistressType {
    SCREAM,
    KEYWORD,
    MOTION_ANOMALY,
    FALL_IMPACT,
    MANUAL_SOS,
    SILENT_ACTIVATION
}

/**
 * Stage of audio evidence collection.
 * Stage 1: Short buffer (e.g., 3-5 seconds) captured upon suspicious trigger.
 * Stage 2: Continuous or segmented high-fidelity evidence stream when emergency is confirmed.
 */
enum class AudioStage {
    STAGE_1_PREVIEW,
    STAGE_2_EVIDENCE
}

/**
 * Represents an individual distress detection signal emitted by audio, motion, or manual sensors.
 *
 * @property id Unique identifier for the signal
 * @property type Distress classification category
 * @property confidence Confidence score between 0.0f and 1.0f
 * @property timestampEpochMs System epoch time when the signal was detected
 * @property source Description of the originating detector
 * @property rawScore Uncalibrated model output or raw sensor metric
 * @property metadata Arbitrary auxiliary metrics (e.g., decibels, peak acceleration, matched keyword)
 */
data class DistressSignal(
    val id: String,
    val type: DistressType,
    val confidence: Float,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val source: String,
    val rawScore: Float = confidence,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Metadata representation of captured emergency audio data.
 */
data class AudioSegment(
    val id: String,
    val filePath: String,
    val durationMs: Long,
    val sampleRate: Int = 16000,
    val stage: AudioStage,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val fileSizeBytes: Long = 0L
)

/**
 * Raw or fused motion sample data.
 */
data class MotionSample(
    val timestampEpochMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val magnitude: Float = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
)
