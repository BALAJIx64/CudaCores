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
 * - Chunk Buffer Size: 1024 samples (64 ms)
 *
 * Hardware Resilience:
 * - Dynamic fallback: attempts MediaRecorder.AudioSource.MIC first; falls back to VOICE_RECOGNITION.
 * - Thread-safe live telemetry StateFlow updates without memory thrashing or UI thread blocking.
 * - Graceful degradation to synthetic mock capture during JVM testing or when hardware is unavailable.
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
    private val sampleListeners = java.util.concurrent.ConcurrentHashMap<String, (ShortArray) -> Unit>()
    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO)

    fun addSampleListener(listener: (ShortArray) -> Unit): String {
        val id = java.util.UUID.randomUUID().toString()
        sampleListeners[id] = listener
        return id
    }

    fun removeSampleListener(listenerId: String) {
        sampleListeners.remove(listenerId)
    }

    private fun dispatchCapturedSamples(samples: ShortArray) {
        circularBuffer?.write(samples)
        for (listener in sampleListeners.values) {
            listener.invoke(samples)
        }
    }

    // Android reflection / dynamic references to avoid compile-time failure on non-Android JVM environments
    private var nativeAudioRecord: Any? = null

    /**
     * Starts continuous microphone audio capture.
     * Samples are automatically routed to [circularBuffer] and emitted via [observePcmChunks].
     */
    fun startCapture(hasRecordAudioPermission: Boolean = true): Result<Flow<ShortArray>> {
        if (isCapturing) {
            return Result.success(pcmChunkFlow.asSharedFlow())
        }

        if (!hasRecordAudioPermission) {
            lastErrorMessage = "Microphone permission (android.permission.RECORD_AUDIO) not granted"
            _liveMetrics.value = LiveAudioMetrics(
                status = AudioCaptureStatus.PERMISSION_DENIED,
                errorMessage = lastErrorMessage
            )
            return Result.failure(SecurityException(lastErrorMessage))
        }

        // Detect genuine Android ART runtime vs host JVM unit-test stub environment
        val isAndroidDevice = System.getProperty("java.runtime.name")
            ?.contains("Android", ignoreCase = true) == true

        val initialized = initializeAndroidAudioRecord()

        if (isAndroidDevice && !initialized) {
            // Never report synthetic fallback as active microphone capture on a physical Android device
            isCapturing = false
            isUsingMockCapture = false
            val err = lastErrorMessage ?: "AudioRecord hardware initialization failed"
            _liveMetrics.value = LiveAudioMetrics(
                status = AudioCaptureStatus.CAPTURE_ERROR,
                sampleRate = sampleRate,
                isPhysicalRecord = false,
                isUsingMockCapture = false,
                activeSource = "NONE",
                errorMessage = err
            )
            return Result.failure(IllegalStateException(err))
        }

        isCapturing = true
        isUsingMockCapture = !initialized

        _liveMetrics.value = LiveAudioMetrics(
            status = AudioCaptureStatus.RECORDING,
            sampleRate = sampleRate,
            isPhysicalRecord = initialized,
            isUsingMockCapture = !initialized,
            activeSource = if (initialized) activeAudioSourceName else "JVM_SYNTHETIC",
            errorMessage = lastErrorMessage
        )

        captureJob = captureScope.launch {
            if (initialized) {
                runPhysicalAudioRecordLoop()
            } else {
                runSyntheticCaptureLoop()
            }
        }

        return Result.success(pcmChunkFlow.asSharedFlow())
    }

    /**
     * Stops microphone capture and releases hardware resources.
     */
    fun stopCapture() {
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
        dispatchCapturedSamples(samples)
        pcmChunkFlow.emit(samples)

        // Calculate and reflect metrics even for synthetic samples in tests
        var maxAmp = 0
        var sumSquares = 0.0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > maxAmp) maxAmp = a
            sumSquares += a * a
        }
        val count = samples.size.coerceAtLeast(1)
        val peak = (maxAmp / 32768.0f).coerceIn(0f, 1f)
        val rms = (sqrt(sumSquares / count).toFloat() / 32768.0f).coerceIn(0f, 1f)
        val dbfs = if (rms > 1e-5f) (20f * log10(rms)).coerceIn(-100f, 0f) else -100f

        _liveMetrics.value = LiveAudioMetrics(
            status = AudioCaptureStatus.RECORDING,
            peak = peak,
            rms = rms,
            dbfs = dbfs,
            sampleRate = sampleRate,
            isPhysicalRecord = false,
            isUsingMockCapture = true,
            activeSource = "SYNTHETIC"
        )
    }

    // --- Physical Android AudioRecord via Reflection with Hardware Fallback ---

    private fun initializeAndroidAudioRecord(): Boolean {
        return try {
            val audioRecordClass = Class.forName("android.media.AudioRecord")
            val mediaRecorderSourceClass = Class.forName("android.media.MediaRecorder\$AudioSource")
            val audioFormatClass = Class.forName("android.media.AudioFormat")

            val micSource = try {
                mediaRecorderSourceClass.getField("MIC").getInt(null)
            } catch (_: Throwable) { 1 }

            val voiceRecSource = try {
                mediaRecorderSourceClass.getField("VOICE_RECOGNITION").getInt(null)
            } catch (_: Throwable) { 6 }

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

            val getStateMethod = audioRecordClass.getMethod("getState")
            val stateInitialized = audioRecordClass.getField("STATE_INITIALIZED").getInt(null)
            val releaseMethod = audioRecordClass.getMethod("release")

            // Candidate hardware recording sources to try in priority order:
            // 1. MIC (standard hardware microphone)
            // 2. VOICE_RECOGNITION (speech-optimized audio source)
            val candidateSources = listOf(
                "MIC" to micSource,
                "VOICE_RECOGNITION" to voiceRecSource
            )

            var createdRecord: Any? = null
            var selectedSourceName = "NONE"

            for ((sourceName, sourceVal) in candidateSources) {
                try {
                    val record = constructor.newInstance(sourceVal, sampleRate, channelMono, encodingPcm16, bufferSize)
                    val state = getStateMethod.invoke(record) as Int
                    if (state == stateInitialized) {
                        createdRecord = record
                        selectedSourceName = sourceName
                        break
                    } else {
                        releaseMethod.invoke(record)
                    }
                } catch (_: Exception) {
                    // Try next fallback source
                }
            }

            if (createdRecord == null) {
                lastErrorMessage = "AudioRecord initialization failed on all candidate audio sources."
                _liveMetrics.value = LiveAudioMetrics(
                    status = AudioCaptureStatus.CAPTURE_ERROR,
                    errorMessage = lastErrorMessage
                )
                return false
            }

            val startRecordingMethod = audioRecordClass.getMethod("startRecording")
            startRecordingMethod.invoke(createdRecord)
            nativeAudioRecord = createdRecord
            activeAudioSourceName = selectedSourceName
            true
        } catch (t: Throwable) {
            lastErrorMessage = "AudioRecord initialization exception: ${t.javaClass.simpleName}: ${t.message}"
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
                    dispatchCapturedSamples(chunk)
                    pcmChunkFlow.emit(chunk)

                    // Compute live volume metrics from actual PCM samples consumed by classifier
                    var maxAmp = 0
                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val a = abs(chunk[i].toInt())
                        if (a > maxAmp) maxAmp = a
                        sumSquares += a * a
                    }
                    val peak = (maxAmp / 32768.0f).coerceIn(0f, 1f)
                    val rms = (sqrt(sumSquares / readCount).toFloat() / 32768.0f).coerceIn(0f, 1f)
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
                } else if (readCount < 0) {
                    delay(20) // Hardware buffer underrun or temporary stall
                }
            } catch (e: Exception) {
                lastErrorMessage = "AudioRecord read error: ${e.message}"
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
            dispatchCapturedSamples(silence)
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
