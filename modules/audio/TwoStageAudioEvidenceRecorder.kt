package com.guardiangrid.modules.audio

import com.guardiangrid.core.interfaces.AudioEvidenceRecorder
import com.guardiangrid.core.models.AudioSegment
import com.guardiangrid.core.models.AudioStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playable WAV chunk produced during emergency audio relay.
 */
data class AudioRelayChunk(
    val incidentId: String,
    val chunkIndex: Int,
    val isPreTrigger: Boolean,
    val isFinal: Boolean,
    val captureTimestampEpochMs: Long,
    val totalSamples: Int,
    val durationMs: Long,
    val segment: AudioSegment,
    val wavBytes: ByteArray
)

/**
 * Completion metadata emitted once continuous emergency recording ends.
 */
data class IncidentRecordingSummary(
    val incidentId: String,
    val preTriggerSegment: AudioSegment,
    val combinedRecordingSegment: AudioSegment,
    val chunkSegments: List<AudioSegment>,
    val totalPostTriggerChunks: Int,
    val totalDurationMs: Long,
    val endedByCancellationOrStop: Boolean
)

/**
 * Handle to a continuous emergency incident recording & 10-second chunked relay session.
 */
data class IncidentAudioRelaySession(
    val incidentId: String,
    val preTriggerSegment: AudioSegment,
    val preTriggerWavBytes: ByteArray,
    val combinedRecordingFile: File,
    val chunkFlow: Flow<AudioRelayChunk>,
    val completionDeferred: CompletableDeferred<IncidentRecordingSummary>
)

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
    private val stopRequested = AtomicBoolean(false)
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
            val segmentIntervalMs = 10_000L // Align legacy Stage 2 updates to 10-second intervals
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
        stopRequested.set(true)
        if (!isStageTwoRecording) return
        isStageTwoRecording = false
        // If legacy startStageTwoRecording (no relay session managing finalization) was running, cancel & finalize immediately;
        // continuous relay session observes stopRequested=true, flushes its final partial chunk, then closes & patches header cleanly.
        if (activeRelaySessionJob != stageTwoJob) {
            stageTwoJob?.cancel()
            stageTwoJob = null
            finalizeCombinedRelayFile()
        }
    }

    @Volatile
    private var activeRelaySessionJob: Job? = null

    /**
     * Initiates a continuous emergency audio capture and chunked relay session:
     * 1. Immediately snapshots up to [preTriggerDurationMs] (default 10s) from [circularBuffer] as ONE unsplit WAV file.
     * 2. Seeds the complete chronological incident WAV file with those pre-trigger PCM samples.
     * 3. Continuously captures post-trigger PCM without restarting microphone capture and without gaps/overlaps:
     *    - Produces sequential [chunkDurationMs] (default 10s = 160,000 samples @ 16 kHz) WAV chunks until stopped
     *      or [maxRecordingDurationMs] is reached.
     *    - On explicit termination/cancellation, flushes any remaining samples as the final partial chunk ([isFinal = true]),
     *      finalizes the complete chronological incident WAV file, and completes [completionDeferred].
     */
    fun startIncidentRelaySession(
        incidentId: String,
        preTriggerDurationMs: Long = 10_000L,
        chunkDurationMs: Long = 10_000L,
        maxRecordingDurationMs: Long = 60_000L
    ): IncidentAudioRelaySession {
        if (isStageTwoRecording) {
            stopRequested.set(true)
            stageTwoJob?.cancel()
            stageTwoJob = null
            finalizeCombinedRelayFile()
        }

        stopRequested.set(false)
        val now = System.currentTimeMillis()
        val evidenceDir = getEvidenceDirectory()
        val preTriggerFile = File(evidenceDir, "stage1_pre10s_${incidentId}_$now.wav")
        val combinedFile = File(evidenceDir, "evidence_${incidentId}_full_$now.wav")

        val sampleRate = circularBuffer.sampleRate
        val clampedPreMs = preTriggerDurationMs.coerceIn(1000L, circularBuffer.durationMs.coerceAtLeast(10_000L))

        val pcmInbox = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        val useLiveCaptureFlow = (captureManager != null && captureManager.isCapturing)

        // Register listener synchronously right after snapshot (before disk writes) so zero live samples are lost
        val rawPreSamples = circularBuffer.snapshot(clampedPreMs)
        val baselineCircularTotalAtTrigger = circularBuffer.totalSamplesWritten()
        val liveListenerId = if (useLiveCaptureFlow) {
            captureManager!!.addSampleListener { chunk ->
                if (chunk.isNotEmpty()) {
                    pcmInbox.trySend(chunk)
                }
            }
        } else {
            null
        }

        val preTriggerSamples = if (rawPreSamples.isNotEmpty()) {
            rawPreSamples
        } else {
            // Minimal valid playable 1s silent frame if trigger fired milliseconds after start
            ShortArray(sampleRate)
        }
        val preTriggerBytes = WavAudioEncoder.encodeToWavBytes(preTriggerSamples, sampleRate, 1)
        preTriggerFile.writeBytes(preTriggerBytes)

        val preActualDurationMs = ((preTriggerSamples.size * 1000L) / sampleRate).coerceAtLeast(1000L)
        val preSegment = AudioSegment(
            id = "pre-$incidentId",
            filePath = preTriggerFile.absolutePath,
            durationMs = preActualDurationMs,
            sampleRate = sampleRate,
            stage = AudioStage.STAGE_1_PREVIEW,
            timestampEpochMs = now,
            fileSizeBytes = preTriggerFile.length()
        )

        // Seed complete chronological incident WAV file with exact pre-trigger PCM
        val prePcmBytes = WavAudioEncoder.shortsToBytes(preTriggerSamples)
        val combinedFos = FileOutputStream(combinedFile)
        combinedFos.write(WavAudioEncoder.createWavHeader(prePcmBytes.size.toLong(), sampleRate, 1))
        combinedFos.write(prePcmBytes)
        combinedFos.flush()

        synchronized(this) {
            isStageTwoRecording = true
            currentOutputFile = combinedFile
            currentFileOutputStream = combinedFos
        }

        val chunkChannel = Channel<AudioRelayChunk>(capacity = Channel.UNLIMITED)
        val completionDeferred = CompletableDeferred<IncidentRecordingSummary>()

        val safeChunkDurationMs = chunkDurationMs.coerceAtLeast(100L)
        val chunkSamplesTarget = ((safeChunkDurationMs * sampleRate) / 1000L).toInt().coerceAtLeast(sampleRate / 10)
        val maxPostSamples = ((maxRecordingDurationMs.coerceAtLeast(safeChunkDurationMs) * sampleRate) / 1000L)

        val job = recorderScope.launch {
            var circularBaseline = baselineCircularTotalAtTrigger

            val pendingSamples = ArrayList<Short>(chunkSamplesTarget + sampleRate)
            val generatedChunks = mutableListOf<AudioSegment>()
            var chunkSeq = 1
            var totalPostSamplesCaptured = 0L
            var chunkStartTimestampEpochMs = System.currentTimeMillis()
            var maxDurationReached = false

            fun writeAndEmitChunk(samples: ShortArray, isFinalChunk: Boolean, captureTs: Long) {
                val wavBytes = WavAudioEncoder.encodeToWavBytes(samples, sampleRate, 1)
                val chunkFile = File(
                    evidenceDir,
                    "evidence_${incidentId}_chunk_${chunkSeq}_${captureTs}.wav"
                )
                chunkFile.writeBytes(wavBytes)

                val pcmBytes = WavAudioEncoder.shortsToBytes(samples)
                synchronized(this@TwoStageAudioEvidenceRecorder) {
                    currentFileOutputStream?.write(pcmBytes)
                    currentFileOutputStream?.flush()
                }

                val durationActualMs = (samples.size * 1000L) / sampleRate
                val chunkSeg = AudioSegment(
                    id = "chunk-$incidentId-$chunkSeq",
                    filePath = chunkFile.absolutePath,
                    durationMs = durationActualMs,
                    sampleRate = sampleRate,
                    stage = AudioStage.STAGE_2_EVIDENCE,
                    timestampEpochMs = captureTs,
                    fileSizeBytes = chunkFile.length()
                )
                generatedChunks.add(chunkSeg)
                chunkChannel.trySend(
                    AudioRelayChunk(
                        incidentId = incidentId,
                        chunkIndex = chunkSeq,
                        isPreTrigger = false,
                        isFinal = isFinalChunk,
                        captureTimestampEpochMs = captureTs,
                        totalSamples = samples.size,
                        durationMs = durationActualMs,
                        segment = chunkSeg,
                        wavBytes = wavBytes
                    )
                )
                chunkSeq++
            }

            fun ingestNewSamples(samples: ShortArray) {
                if (samples.isEmpty() || maxDurationReached) return
                val remainingCap = (maxPostSamples - totalPostSamplesCaptured).coerceAtLeast(0L).toInt()
                if (remainingCap <= 0) {
                    maxDurationReached = true
                    return
                }
                val toCopy = minOf(samples.size, remainingCap)
                if (pendingSamples.isEmpty()) {
                    chunkStartTimestampEpochMs = System.currentTimeMillis()
                }
                for (i in 0 until toCopy) {
                    pendingSamples.add(samples[i])
                }
                totalPostSamplesCaptured += toCopy
                if (totalPostSamplesCaptured >= maxPostSamples) {
                    maxDurationReached = true
                }
            }

            try {
                while (isActive && !stopRequested.get() && !maxDurationReached) {
                    // Drain from live capture listener or circular buffer cursor (strictly non-overlapping delta)
                    if (useLiveCaptureFlow) {
                        var item = pcmInbox.tryReceive().getOrNull()
                        while (item != null) {
                            ingestNewSamples(item)
                            item = pcmInbox.tryReceive().getOrNull()
                        }
                    } else {
                        val currentTotal = circularBuffer.totalSamplesWritten()
                        val delta = (currentTotal - circularBaseline)
                            .coerceIn(0L, circularBuffer.capacitySamples.toLong())
                            .toInt()
                        if (delta > 0) {
                            circularBaseline = currentTotal
                            val deltaMs = ((delta * 1000L) / sampleRate).coerceAtLeast(1L)
                            val snap = circularBuffer.snapshot(deltaMs + 20L)
                            val exactTail = if (snap.size > delta) snap.copyOfRange(snap.size - delta, snap.size) else snap
                            ingestNewSamples(exactTail)
                        }
                    }

                    // Emit full 10-second chunks sequentially as soon as exact sample count accumulates
                    while (pendingSamples.size >= chunkSamplesTarget) {
                        val fullChunk = ShortArray(chunkSamplesTarget) { idx -> pendingSamples[idx] }
                        pendingSamples.subList(0, chunkSamplesTarget).clear()
                        val isFinalChunk = maxDurationReached && pendingSamples.isEmpty()
                        writeAndEmitChunk(fullChunk, isFinalChunk, chunkStartTimestampEpochMs)
                        chunkStartTimestampEpochMs = System.currentTimeMillis()
                    }

                    if (!stopRequested.get() && !maxDurationReached) {
                        delay(25)
                    }
                }

                // Final drain on stop/max-cap (ensure zero samples lost before closing)
                if (useLiveCaptureFlow) {
                    var item = pcmInbox.tryReceive().getOrNull()
                    while (item != null) {
                        ingestNewSamples(item)
                        item = pcmInbox.tryReceive().getOrNull()
                    }
                } else {
                    val currentTotal = circularBuffer.totalSamplesWritten()
                    val delta = (currentTotal - circularBaseline)
                        .coerceIn(0L, circularBuffer.capacitySamples.toLong())
                        .toInt()
                    if (delta > 0) {
                        circularBaseline = currentTotal
                        val deltaMs = ((delta * 1000L) / sampleRate).coerceAtLeast(1L)
                        val snap = circularBuffer.snapshot(deltaMs + 20L)
                        val exactTail = if (snap.size > delta) snap.copyOfRange(snap.size - delta, snap.size) else snap
                        ingestNewSamples(exactTail)
                    }
                }

                while (pendingSamples.size >= chunkSamplesTarget) {
                    val fullChunk = ShortArray(chunkSamplesTarget) { idx -> pendingSamples[idx] }
                    pendingSamples.subList(0, chunkSamplesTarget).clear()
                    val isFinalChunk = pendingSamples.isEmpty()
                    writeAndEmitChunk(fullChunk, isFinalChunk, chunkStartTimestampEpochMs)
                    chunkStartTimestampEpochMs = System.currentTimeMillis()
                }

                if (pendingSamples.isNotEmpty()) {
                    val partialChunk = ShortArray(pendingSamples.size) { idx -> pendingSamples[idx] }
                    pendingSamples.clear()
                    writeAndEmitChunk(partialChunk, isFinalChunk = true, captureTs = chunkStartTimestampEpochMs)
                }
            } finally {
                if (liveListenerId != null) {
                    captureManager?.removeSampleListener(liveListenerId)
                }
                pcmInbox.close()
                chunkChannel.close()
                finalizeCombinedRelayFile()
                activeRelaySessionJob = null
                val totalDurationMs = ((preTriggerSamples.size + totalPostSamplesCaptured) * 1000L) / sampleRate
                val combinedSegment = AudioSegment(
                    id = "full-$incidentId",
                    filePath = combinedFile.absolutePath,
                    durationMs = totalDurationMs,
                    sampleRate = sampleRate,
                    stage = AudioStage.STAGE_2_EVIDENCE,
                    timestampEpochMs = System.currentTimeMillis(),
                    fileSizeBytes = combinedFile.length()
                )
                if (!completionDeferred.isCompleted) {
                    completionDeferred.complete(
                        IncidentRecordingSummary(
                            incidentId = incidentId,
                            preTriggerSegment = preSegment,
                            combinedRecordingSegment = combinedSegment,
                            chunkSegments = generatedChunks.toList(),
                            totalPostTriggerChunks = generatedChunks.size,
                            totalDurationMs = totalDurationMs,
                            endedByCancellationOrStop = stopRequested.get()
                        )
                    )
                }
            }
        }

        stageTwoJob = job
        activeRelaySessionJob = job

        return IncidentAudioRelaySession(
            incidentId = incidentId,
            preTriggerSegment = preSegment,
            preTriggerWavBytes = preTriggerBytes,
            combinedRecordingFile = combinedFile,
            chunkFlow = chunkChannel.receiveAsFlow(),
            completionDeferred = completionDeferred
        )
    }

    /**
     * Enforces chunk retention safety: source chunks are NEVER deleted while still awaiting delivery
     * OR while within [retentionWindowMs]. Combined full recordings and pre-trigger backups follow the same rule.
     */
    fun cleanupSatisfiedChunks(
        deliveredFilePaths: Set<String>,
        retentionWindowMs: Long = DEFAULT_CHUNK_RETENTION_MS,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        val baseDir = getEvidenceDirectory()
        if (!baseDir.exists()) return 0
        var deleted = 0
        baseDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".wav")) return@forEach
            val isChunk = file.name.contains("_chunk_")
            if (!isChunk) return@forEach // Retain pre-trigger & complete chronological incident WAV unless explicitly purged
            val isDelivered = deliveredFilePaths.contains(file.absolutePath)
            val ageMs = nowEpochMs - file.lastModified()
            if (isDelivered && ageMs >= retentionWindowMs) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    companion object {
        const val DEFAULT_CHUNK_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private fun finalizeCombinedRelayFile() {
        synchronized(this) {
            isStageTwoRecording = false
            try {
                currentFileOutputStream?.flush()
                currentFileOutputStream?.close()
            } catch (_: Exception) {
            } finally {
                currentFileOutputStream = null
            }
            currentOutputFile?.let { file ->
                WavAudioEncoder.finalizeWavHeader(file)
            }
            currentOutputFile = null
        }
    }

    private fun getEvidenceDirectory(): File {
        val baseDir = cacheDirectory?.let { File(it, "evidence") }
            ?: File(System.getProperty("java.io.tmpdir"), "guardiangrid_evidence")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    private fun createStageOneFile(incidentId: String? = null): File {
        val baseDir = getEvidenceDirectory()
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
