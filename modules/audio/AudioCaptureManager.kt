package com.guardiangrid.modules.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private val pcmChunkFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO)

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
            return Result.failure(SecurityException(lastErrorMessage))
        }

        // Attempt initialization of physical Android AudioRecord
        val initialized = initializeAndroidAudioRecord()

        isCapturing = true
        isUsingMockCapture = !initialized

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
    }

    // --- Physical Android AudioRecord via Reflection ---

    private fun initializeAndroidAudioRecord(): Boolean {
        return try {
            val audioRecordClass = Class.forName("android.media.AudioRecord")
            val mediaRecorderSourceClass = Class.forName("android.media.MediaRecorder\$AudioSource")
            val audioFormatClass = Class.forName("android.media.AudioFormat")

            val micSource = mediaRecorderSourceClass.getField("MIC").getInt(null)
            val channelMono = audioFormatClass.getField("CHANNEL_IN_MONO").getInt(null)
            val encodingPcm16 = audioFormatClass.getField("ENCODING_PCM_16BIT").getInt(null)

            val getMinBufferSizeMethod = audioRecordClass.getMethod(
                "getMinBufferSize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val minBuf = getMinBufferSizeMethod.invoke(null, sampleRate, channelMono, encodingPcm16) as Int
            if (minBuf <= 0) return false

            val bufferSize = minBuf.coerceAtLeast(sampleRate * 2)

            val constructor = audioRecordClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val record = constructor.newInstance(micSource, sampleRate, channelMono, encodingPcm16, bufferSize)

            val getStateMethod = audioRecordClass.getMethod("getState")
            val state = getStateMethod.invoke(record) as Int
            val stateInitialized = audioRecordClass.getField("STATE_INITIALIZED").getInt(null)

            if (state == stateInitialized) {
                val startRecordingMethod = audioRecordClass.getMethod("startRecording")
                startRecordingMethod.invoke(record)
                nativeAudioRecord = record
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            // JVM desktop test environment or device without AudioRecord access
            nativeAudioRecord = null
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
                } else if (readCount < 0) {
                    delay(20) // Hardware buffer underrun or temporary stall
                }
            } catch (e: Exception) {
                lastErrorMessage = "AudioRecord read error: ${e.message}"
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
        }
    }
}
