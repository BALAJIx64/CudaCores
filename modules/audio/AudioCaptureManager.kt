package com.guardiangrid.modules.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Status indicator for live audio microphone capture.
 */
enum class AudioCaptureStatus {
    STOPPED,
    RECORDING,
    PERMISSION_DENIED,
    CAPTURE_ERROR
}

/**
 * Lightweight telemetry metrics for live volume metering and UI diagnosis.
 */
data class LiveAudioMetrics(
    val status: AudioCaptureStatus = AudioCaptureStatus.STOPPED,
    val peak: Float = 0.0f,
    val rms: Float = 0.0f,
    val dbfs: Float = -100.0f,
    val sampleRate: Int = 16000,
    val isPhysicalRecord: Boolean = false,
    val isUsingMockCapture: Boolean = false,
    val activeSource: String = "NONE",
    val errorMessage: String? = null
)

/**
 * Manages real-time microphone audio capture with Android permission compliance,
 * hardware initialization safeguards, and audio interruption recovery.
 *
 * Audio Pipeline Specifications:
 * - Format: Linear PCM, 16-bit signed little-endian
 * - Channel: Mono (1 channel)
 * - Sample Rate: 16,000 Hz (Standard across edge speech & sound classification models)
 * - Chunk Buffer Size: 1024–2048 samples (~64–128 ms)
 *
 * Resilience & Portability:
 * - Operates transparently on Android devices with physical microphone hardware via `AudioRecord`.
 * - Automatically degrades gracefully to mock/simulation mode during JVM unit testing or when
 *   hardware permissions are denied, preventing fatal application crashes.
 *
 * Owned by Member 1 (modules/audio).
 */
class AudioCaptureManager(
    val sampleRate: Int = 16000,
    private val circularBuffer: CircularAudioBuffer? = null
) {

    var isCapturing: Boolean = false
        private set

    var isUsingMockCapture: Boolean = false
        private set

    var lastErrorMessage: String? = null
        private set

    var activeAudioSourceName: String = "NONE"
        private set

    private val _liveMetrics = MutableStateFlow(LiveAudioMetrics())
    val liveMetrics: StateFlow<LiveAudioMetrics> = _liveMetrics.asStateFlow()

    private val pcmChunkFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO)

    // Android reflection / dynamic references to avoid compile-time failure on non-Android JVM environments
    private var nativeAudioRecord: Any? = null
    private var lastChunkLogTime = 0L

    private fun logAudio(msg: String, isError: Boolean = false) {
        try {
            if (isError) android.util.Log.e("GuardianAudio", msg)
            else android.util.Log.i("GuardianAudio", msg)
        } catch (_: Throwable) {
            println("[GuardianAudio] $msg")
        }
    }

    /**
     * Starts continuous microphone audio capture.
     * Samples are automatically routed to [circularBuffer] and emitted via [observePcmChunks].
     */
    fun startCapture(hasRecordAudioPermission: Boolean = true): Result<Flow<ShortArray>> {
        if (isCapturing) {
            logAudio("startCapture called while already capturing.")
            return Result.success(pcmChunkFlow.asSharedFlow())
        }

        if (!hasRecordAudioPermission) {
            lastErrorMessage = "Microphone permission (android.permission.RECORD_AUDIO) not granted"
            logAudio(lastErrorMessage ?: "", isError = true)
            _liveMetrics.value = LiveAudioMetrics(
                status = AudioCaptureStatus.PERMISSION_DENIED,
                errorMessage = lastErrorMessage
            )
            return Result.failure(SecurityException(lastErrorMessage))
        }

        // Attempt initialization of physical Android AudioRecord
        val initialized = initializeAndroidAudioRecord()

        isCapturing = true
        isUsingMockCapture = !initialized

        _liveMetrics.value = LiveAudioMetrics(
            status = AudioCaptureStatus.RECORDING,
            sampleRate = sampleRate,
            isPhysicalRecord = initialized,
            isUsingMockCapture = !initialized,
            activeSource = activeAudioSourceName,
            errorMessage = lastErrorMessage
        )

        captureJob = captureScope.launch {
            if (initialized) {
                logAudio("Physical AudioRecord capture loop started on hardware source: $activeAudioSourceName")
                runPhysicalAudioRecordLoop()
            } else {
                logAudio("Fallback synthetic silence capture loop started (isUsingMockCapture=true)")
                runSyntheticCaptureLoop()
            }
        }

        return Result.success(pcmChunkFlow.asSharedFlow())
    }

    /**
     * Stops microphone capture and releases hardware resources.
     */
    fun stopCapture() {
        logAudio("stopCapture called. Releasing hardware microphone resources.")
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        releaseAndroidAudioRecord()
        _liveMetrics.value = LiveAudioMetrics(status = AudioCaptureStatus.STOPPED)
    }

    /**
     * Emits a stream of raw 16-bit PCM ShortArray chunks.
     */
    fun observePcmChunks(): Flow<ShortArray> = pcmChunkFlow.asSharedFlow()

    /**
     * Injects synthetic audio samples directly into the capture pipeline.
     * Useful for automated unit testing and hardware simulation without an active microphone.
     */
    suspend fun injectSyntheticSamples(samples: ShortArray) {
        circularBuffer?.write(samples)
        pcmChunkFlow.emit(samples)

        // Update live metrics from injected chunk
        if (samples.isNotEmpty()) {
            var maxAmp = 0
            var sumSquares = 0.0
            for (s in samples) {
                val a = abs(s.toInt())
                if (a > maxAmp) maxAmp = a
                sumSquares += a * a
            }
            val peak = (maxAmp / 32768.0f).coerceIn(0f, 1f)
            val rms = sqrt(sumSquares / samples.size).toFloat() / 32768.0f
            val dbfs = if (rms > 1e-5f) (20f * log10(rms)).coerceIn(-100f, 0f) else -100f
            _liveMetrics.value = LiveAudioMetrics(
                status = AudioCaptureStatus.RECORDING,
                peak = peak,
                rms = rms,
                dbfs = dbfs,
                sampleRate = sampleRate,
                isPhysicalRecord = false,
                isUsingMockCapture = true,
                activeSource = "INJECTED_TEST_STREAM"
            )
        }
    }

    // --- Physical Android AudioRecord via Reflection ---

    private fun initializeAndroidAudioRecord(): Boolean {
        return try {
            val audioRecordClass = Class.forName("android.media.AudioRecord")
            val mediaRecorderSourceClass = Class.forName("android.media.MediaRecorder\$AudioSource")
            val audioFormatClass = Class.forName("android.media.AudioFormat")

            val voiceRecSource = try {
                mediaRecorderSourceClass.getField("VOICE_RECOGNITION").getInt(null)
            } catch (_: Throwable) { 6 }
            val micSource = try {
                mediaRecorderSourceClass.getField("MIC").getInt(null)
            } catch (_: Throwable) { 1 }

            val channelMono = audioFormatClass.getField("CHANNEL_IN_MONO").getInt(null)
            val encodingPcm16 = audioFormatClass.getField("ENCODING_PCM_16BIT").getInt(null)

            val getMinBufferSizeMethod = audioRecordClass.getMethod(
                "getMinBufferSize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val minBuf = getMinBufferSizeMethod.invoke(null, sampleRate, channelMono, encodingPcm16) as Int
            if (minBuf <= 0) {
                lastErrorMessage = "AudioRecord.getMinBufferSize returned invalid buffer size: $minBuf"
                logAudio(lastErrorMessage ?: "", isError = true)
                return false
            }

            val bufferSize = minBuf.coerceAtLeast(sampleRate * 2)

            val constructor = audioRecordClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            // Try VOICE_RECOGNITION first (avoids aggressive noise gate/suppression), then MIC
            val sourcesToTry = listOf(
                "VOICE_RECOGNITION" to voiceRecSource,
                "MIC" to micSource
            )

            var createdRecord: Any? = null
            var selectedSourceName = "NONE"

            for ((sourceName, sourceVal) in sourcesToTry) {
                try {
                    val record = constructor.newInstance(sourceVal, sampleRate, channelMono, encodingPcm16, bufferSize)
                    val getStateMethod = audioRecordClass.getMethod("getState")
                    val state = getStateMethod.invoke(record) as Int
                    val stateInitialized = audioRecordClass.getField("STATE_INITIALIZED").getInt(null)
                    if (state == stateInitialized) {
                        createdRecord = record
                        selectedSourceName = sourceName
                        break
                    } else {
                        val releaseMethod = audioRecordClass.getMethod("release")
                        releaseMethod.invoke(record)
                    }
                } catch (e: Exception) {
                    logAudio("Failed AudioRecord init on source $sourceName: ${e.message}")
                }
            }

            if (createdRecord == null) {
                lastErrorMessage = "AudioRecord could not be initialized into STATE_INITIALIZED on any audio source."
                logAudio(lastErrorMessage ?: "", isError = true)
                return false
            }

            val startRecordingMethod = audioRecordClass.getMethod("startRecording")
            startRecordingMethod.invoke(createdRecord)
            nativeAudioRecord = createdRecord
            activeAudioSourceName = selectedSourceName
            logAudio("Physical AudioRecord initialized successfully (Source: $selectedSourceName, SR: $sampleRate, BufSize: $bufferSize)")
            true
        } catch (t: Throwable) {
            lastErrorMessage = "AudioRecord initialization exception: ${t.javaClass.simpleName}: ${t.message}"
            logAudio(lastErrorMessage ?: "", isError = true)
            nativeAudioRecord = null
            activeAudioSourceName = "NONE"
            false
        }
    }

    private suspend fun runPhysicalAudioRecordLoop() {
        val record = nativeAudioRecord ?: return
        val audioRecordClass = record.javaClass
        val readMethod = audioRecordClass.getMethod(
            "read",
            ShortArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        val chunkSize = 1024
        val tempBuffer = ShortArray(chunkSize)

        while (isCapturing && captureScope.isActive) {
            try {
                val readCount = readMethod.invoke(record, tempBuffer, 0, chunkSize) as Int
                if (readCount > 0) {
                    val chunk = ShortArray(readCount)
                    System.arraycopy(tempBuffer, 0, chunk, 0, readCount)
                    circularBuffer?.write(chunk)
                    pcmChunkFlow.emit(chunk)

                    // Compute live volume metrics from the exact PCM samples consumed by classifier
                    var maxAmp = 0
                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val a = abs(chunk[i].toInt())
                        if (a > maxAmp) maxAmp = a
                        sumSquares += a * a
                    }
                    val peak = (maxAmp / 32768.0f).coerceIn(0f, 1f)
                    val rms = sqrt(sumSquares / readCount).toFloat() / 32768.0f
                    val dbfs = if (rms > 1e-5f) (20f * log10(rms)).coerceIn(-100f, 0f) else -100f

                    _liveMetrics.value = LiveAudioMetrics(
                        status = AudioCaptureStatus.RECORDING,
                        peak = peak,
                        rms = rms,
                        dbfs = dbfs,
                        sampleRate = sampleRate,
                        isPhysicalRecord = true,
                        activeSource = activeAudioSourceName
                    )

                    val now = System.currentTimeMillis()
                    if (now - lastChunkLogTime > 3000L || peak > 0.15f) {
                        lastChunkLogTime = now
                        logAudio("Physical PCM read: $readCount samples | peak=%.3f, rms=%.4f, dbfs=%.1f dBFS (Source: $activeAudioSourceName)".format(peak, rms, dbfs))
                    }
                } else if (readCount < 0) {
                    logAudio("AudioRecord.read returned error code: $readCount", isError = true)
                    delay(20) // Hardware buffer underrun or temporary stall
                }
            } catch (e: Exception) {
                lastErrorMessage = "AudioRecord read error: ${e.message}"
                logAudio(lastErrorMessage ?: "", isError = true)
                _liveMetrics.value = LiveAudioMetrics(
                    status = AudioCaptureStatus.CAPTURE_ERROR,
                    errorMessage = lastErrorMessage
                )
                delay(50)
            }
        }
    }

    private suspend fun runSyntheticCaptureLoop() {
        val chunkSize = 1024
        val silence = ShortArray(chunkSize)
        while (isCapturing && captureScope.isActive) {
            circularBuffer?.write(silence)
            pcmChunkFlow.emit(silence)
            // 1024 samples at 16000 Hz = 64 ms
            delay(64)
        }
    }

    private fun releaseAndroidAudioRecord() {
        try {
            nativeAudioRecord?.let { record ->
                val stopMethod = record.javaClass.getMethod("stop")
                val releaseMethod = record.javaClass.getMethod("release")
                stopMethod.invoke(record)
                releaseMethod.invoke(record)
            }
        } catch (_: Throwable) {
            // Ignored on teardown
        } finally {
            nativeAudioRecord = null
            activeAudioSourceName = "NONE"
        }
    }
}
