package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.AudioEvidenceRecorder
import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.AudioStage
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
import java.io.FileOutputStream
import java.util.UUID

/**
 * Concrete implementation of the Two-Stage Emergency Audio Recording Protocol.
 *
 * Implements [AudioEvidenceRecorder].
 *
 * Architectural Specification:
 * - Stage 1 (Preview Buffer):
 *   Upon an initial suspicious or distress trigger, instantly snapshots the past 3–5 seconds
 *   of in-memory PCM audio from [CircularAudioBuffer] and flushes it as `stage1_preview.wav`.
 *   This gives first responders immediate historical context of the distress trigger without
 *   having recorded to disk beforehand.
 *
 * - Stage 2 (Emergency Evidence Stream):
 *   Upon verified emergency escalation, initiates continuous high-bitrate evidence streaming
 *   into a specified output file, emitting periodic [AudioSegment] chunks for transmission and audit trail.
 *
 * - Privacy Invariant:
 *   Normal ambient sound remains strictly in volatile RAM; only explicit triggers cause disk writes.
 *
 * Owned by Member 1 (modules/audio).
 */
class TwoStageAudioEvidenceRecorder(
    private val circularBuffer: CircularAudioBuffer,
    private val captureManager: AudioCaptureManager? = null,
    private val cacheDirectory: File? = null
) : AudioEvidenceRecorder {

    private var isStageTwoRecording = false
    private var stageTwoJob: Job? = null
    private var currentOutputFile: File? = null
    private var currentFileOutputStream: FileOutputStream? = null

    private val stageTwoFlow = MutableSharedFlow<AudioSegment>(extraBufferCapacity = 16)
    private val recorderScope = CoroutineScope(Dispatchers.IO)

    override suspend fun captureStageOneBuffer(durationMs: Long): Result<AudioSegment> {
        return try {
            val clampedDuration = durationMs.coerceIn(1000L, 10000L)
            val samples = circularBuffer.snapshot(clampedDuration)

            if (samples.isEmpty()) {
                // If circular buffer is empty (e.g. freshly started), create silent baseline preview
                val emptySamples = ShortArray(((clampedDuration * circularBuffer.sampleRate) / 1000L).toInt())
                val tempFile = createStageOneFile()
                WavAudioEncoder.writeWavFile(tempFile, emptySamples, circularBuffer.sampleRate, 1)

                val segment = AudioSegment(
                    id = UUID.randomUUID().toString(),
                    filePath = tempFile.absolutePath,
                    durationMs = clampedDuration,
                    sampleRate = circularBuffer.sampleRate,
                    stage = AudioStage.STAGE_1_PREVIEW,
                    timestampEpochMs = System.currentTimeMillis(),
                    fileSizeBytes = tempFile.length()
                )
                return Result.success(segment)
            }

            val outputFile = createStageOneFile()
            WavAudioEncoder.writeWavFile(outputFile, samples, circularBuffer.sampleRate, 1)

            val actualDurationMs = (samples.size * 1000L) / circularBuffer.sampleRate
            val segment = AudioSegment(
                id = UUID.randomUUID().toString(),
                filePath = outputFile.absolutePath,
                durationMs = actualDurationMs,
                sampleRate = circularBuffer.sampleRate,
                stage = AudioStage.STAGE_1_PREVIEW,
                timestampEpochMs = System.currentTimeMillis(),
                fileSizeBytes = outputFile.length()
            )

            Result.success(segment)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun startStageTwoRecording(outputFile: File): Flow<AudioSegment> {
        stopRecording() // Clean up any active session

        isStageTwoRecording = true
        currentOutputFile = outputFile
        outputFile.parentFile?.mkdirs()

        // Write initial 44-byte WAV header placeholder
        val initialHeader = WavAudioEncoder.createWavHeader(
            pcmDataLength = 0L,
            sampleRate = circularBuffer.sampleRate,
            channels = 1
        )
        val fos = FileOutputStream(outputFile)
        fos.write(initialHeader)
        fos.flush()
        currentFileOutputStream = fos

        stageTwoJob = recorderScope.launch {
            val segmentIntervalMs = 3000L // Emit segment updates every 3 seconds
            var totalPcmBytesWritten = 0L
            val startTime = System.currentTimeMillis()

            // Collect samples from capture manager if available, or periodically flush circular buffer
            if (captureManager != null) {
                val chunkJob = launch {
                    captureManager.observePcmChunks().collect { chunk ->
                        if (isStageTwoRecording && currentFileOutputStream != null) {
                            val bytes = WavAudioEncoder.shortsToBytes(chunk)
                            synchronized(this@TwoStageAudioEvidenceRecorder) {
                                currentFileOutputStream?.write(bytes)
                                totalPcmBytesWritten += bytes.size
                            }
                        }
                    }
                }

                while (isActive && isStageTwoRecording) {
                    delay(segmentIntervalMs)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val segment = AudioSegment(
                        id = UUID.randomUUID().toString(),
                        filePath = outputFile.absolutePath,
                        durationMs = elapsedMs,
                        sampleRate = circularBuffer.sampleRate,
                        stage = AudioStage.STAGE_2_EVIDENCE,
                        timestampEpochMs = System.currentTimeMillis(),
                        fileSizeBytes = outputFile.length()
                    )
                    stageTwoFlow.emit(segment)
                }
                chunkJob.cancel()
            } else {
                // Standalone periodic flusher
                while (isActive && isStageTwoRecording) {
                    delay(segmentIntervalMs)
                    val recentSamples = circularBuffer.snapshot(segmentIntervalMs)
                    if (recentSamples.isNotEmpty()) {
                        val bytes = WavAudioEncoder.shortsToBytes(recentSamples)
                        synchronized(this@TwoStageAudioEvidenceRecorder) {
                            currentFileOutputStream?.write(bytes)
                            totalPcmBytesWritten += bytes.size
                        }
                    }
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val segment = AudioSegment(
                        id = UUID.randomUUID().toString(),
                        filePath = outputFile.absolutePath,
                        durationMs = elapsedMs,
                        sampleRate = circularBuffer.sampleRate,
                        stage = AudioStage.STAGE_2_EVIDENCE,
                        timestampEpochMs = System.currentTimeMillis(),
                        fileSizeBytes = outputFile.length()
                    )
                    stageTwoFlow.emit(segment)
                }
            }
        }

        return stageTwoFlow.asSharedFlow()
    }

    override fun stopRecording() {
        if (!isStageTwoRecording) return
        isStageTwoRecording = false

        stageTwoJob?.cancel()
        stageTwoJob = null

        synchronized(this) {
            try {
                currentFileOutputStream?.flush()
                currentFileOutputStream?.close()
            } catch (_: Exception) {
            } finally {
                currentFileOutputStream = null
            }

            // Patch final WAV header sizes
            currentOutputFile?.let { file ->
                WavAudioEncoder.finalizeWavHeader(file)
            }
            currentOutputFile = null
        }
    }

    private fun createStageOneFile(incidentId: String? = null): File {
        val baseDir = cacheDirectory?.let { File(it, "evidence") }
            ?: File(System.getProperty("java.io.tmpdir"), "guardiangrid_evidence")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val prefix = if (!incidentId.isNullOrBlank()) "evidence_${incidentId}" else "stage1_preview"
        return File(baseDir, "${prefix}_${System.currentTimeMillis()}.wav")
    }

    /**
     * User-controlled forensic evidence deletion.
     * Guarantees files are deleted permanently and never retained silently.
     */
    fun deleteEvidence(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Cleans up orphaned or temporary preview recordings to prevent unneeded storage usage.
     */
    fun cleanupTemporaryPreviews(maxAgeMs: Long = 3600_000L): Int {
        val baseDir = cacheDirectory?.let { File(it, "evidence") }
            ?: File(System.getProperty("java.io.tmpdir"), "guardiangrid_evidence")
        if (!baseDir.exists()) return 0

        val cutoff = System.currentTimeMillis() - maxAgeMs
        var deletedCount = 0
        baseDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("stage1_preview") && file.lastModified() < cutoff) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
