package com.guardiangrid.modules.audio

import java.io.File
import kotlin.math.min

/**
 * Thread-safe, in-memory circular rolling audio buffer.
 *
 * Responsibilities:
 * - Continuously ingests 16-bit linear PCM audio samples in RAM.
 * - Enforces zero-disk retention during normal monitoring: old samples are overwritten automatically.
 * - Enables instant extraction of the past 3–5 seconds upon suspicious or distress triggers (Stage 1).
 *
 * Memory footprint:
 * At 16 kHz mono (16-bit):
 * - 1 second = 16,000 shorts = 32,000 bytes (32 KB)
 * - 5 seconds = 80,000 shorts = 160,000 bytes (~156 KB)
 *
 * Owned by Member 1 (modules/audio).
 */
class CircularAudioBuffer(
    val durationMs: Long = 5000L,
    val sampleRate: Int = 16000
) {
    // Total capacity in 16-bit Short samples
    val capacitySamples: Int = ((durationMs * sampleRate) / 1000L).toInt()

    private val buffer: ShortArray = ShortArray(capacitySamples)
    private var writeIndex: Int = 0
    private var totalSamplesWritten: Long = 0L
    private val lock = Any()

    /**
     * Ingests a chunk of 16-bit PCM Short samples into the circular ring buffer.
     * Overwrites the oldest samples when capacity is reached.
     */
    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        if (length <= 0) return
        synchronized(lock) {
            var srcPos = offset
            var remaining = length

            while (remaining > 0) {
                val spaceToEnd = capacitySamples - writeIndex
                val toCopy = min(remaining, spaceToEnd)
                System.arraycopy(samples, srcPos, buffer, writeIndex, toCopy)

                writeIndex = (writeIndex + toCopy) % capacitySamples
                srcPos += toCopy
                remaining -= toCopy
            }
            totalSamplesWritten += length
        }
    }

    /**
     * Ingests raw little-endian PCM byte data into the ring buffer.
     */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length < 2) return
        val shorts = WavAudioEncoder.bytesToShorts(bytes, offset, length)
        write(shorts, 0, shorts.size)
    }

    /**
     * Takes an atomic snapshot of the most recent audio samples up to [requestedDurationMs].
     * Unrolls the ring buffer into a chronological linear ShortArray.
     *
     * @param requestedDurationMs Duration in milliseconds to extract (capped at [durationMs]).
     * @return Chronologically ordered ShortArray of audio samples.
     */
    fun snapshot(requestedDurationMs: Long = durationMs): ShortArray {
        synchronized(lock) {
            val requestedSamples = ((requestedDurationMs * sampleRate) / 1000L).toInt()
            val available = min(totalSamplesWritten, capacitySamples.toLong()).toInt()
            val count = min(requestedSamples, available)

            if (count <= 0) {
                return ShortArray(0)
            }

            val result = ShortArray(count)
            // The oldest sample in the snapshot begins at (writeIndex - count + capacitySamples) % capacitySamples
            var readIndex = (writeIndex - count + capacitySamples) % capacitySamples

            val firstChunk = min(count, capacitySamples - readIndex)
            System.arraycopy(buffer, readIndex, result, 0, firstChunk)

            val secondChunk = count - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, result, firstChunk, secondChunk)
            }

            return result
        }
    }

    /**
     * Takes an atomic snapshot and returns it as a canonical 44-byte RIFF .wav byte array.
     */
    fun snapshotAsWavBytes(requestedDurationMs: Long = durationMs): ByteArray {
        val samples = snapshot(requestedDurationMs)
        return WavAudioEncoder.encodeToWavBytes(samples, sampleRate, 1)
    }

    /**
     * Dumps the snapshot directly into a .wav file on disk (for Stage 1 preview extraction).
     */
    fun dumpSnapshotToWav(outputFile: File, requestedDurationMs: Long = durationMs): File {
        val samples = snapshot(requestedDurationMs)
        WavAudioEncoder.writeWavFile(outputFile, samples, sampleRate, 1)
        return outputFile
    }

    /**
     * Returns the current duration of audio stored in the buffer in milliseconds.
     */
    fun availableDurationMs(): Long {
        synchronized(lock) {
            val availableSamples = min(totalSamplesWritten, capacitySamples.toLong())
            return (availableSamples * 1000L) / sampleRate
        }
    }

    /**
     * Returns total samples written since creation or last clear.
     */
    fun totalSamplesWritten(): Long {
        synchronized(lock) {
            return totalSamplesWritten
        }
    }

    /**
     * Resets the circular buffer state.
     */
    fun clear() {
        synchronized(lock) {
            buffer.fill(0)
            writeIndex = 0
            totalSamplesWritten = 0L
        }
    }
}
