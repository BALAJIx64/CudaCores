package com.guardiangrid.modules.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, zero-dependency Kotlin utility for encoding 16-bit linear PCM audio
 * into standard canonical 44-byte RIFF/WAVE (.wav) format.
 *
 * Adheres strictly to the Microsoft RIFF WAVE specification:
 * - ChunkID: "RIFF", Format: "WAVE"
 * - Subchunk1: "fmt " (16 bytes for PCM format code 1)
 * - Subchunk2: "data" containing 16-bit little-endian samples
 *
 * Owned by Member 1 (modules/audio).
 */
object WavAudioEncoder {

    const val DEFAULT_SAMPLE_RATE = 16000
    const val DEFAULT_CHANNELS = 1 // Mono
    const val DEFAULT_BITS_PER_SAMPLE = 16
    const val WAV_HEADER_SIZE = 44

    /**
     * Generates a standard canonical 44-byte RIFF WAVE header.
     *
     * @param pcmDataLength Total byte length of the PCM raw audio payload.
     * @param sampleRate Sampling rate in Hz (default 16000).
     * @param channels Number of audio channels (default 1 = mono).
     * @param bitsPerSample Bit depth per sample (default 16).
     * @return 44-byte array containing the formatted header.
     */
    fun createWavHeader(
        pcmDataLength: Long,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE
    ): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalDataLen = pcmDataLength + 36 // 44 - 8 bytes for RIFF id and size

        val header = ByteArray(WAV_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen.toInt()) // ChunkSize
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        // "fmt " sub-chunk
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // Subchunk1Size for PCM (16 bytes)
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM (linear quantization)
        buffer.putShort(channels.toShort()) // NumChannels
        buffer.putInt(sampleRate) // SampleRate
        buffer.putInt(byteRate) // ByteRate
        buffer.putShort(blockAlign.toShort()) // BlockAlign
        buffer.putShort(bitsPerSample.toShort()) // BitsPerSample

        // "data" sub-chunk
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(pcmDataLength.toInt()) // Subchunk2Size

        return header
    }

    /**
     * Converts a 16-bit ShortArray into little-endian byte array.
     */
    fun shortsToBytes(shorts: ShortArray, offset: Int = 0, length: Int = shorts.size): ByteArray {
        val bytes = ByteArray(length * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until (offset + length)) {
            buffer.putShort(shorts[i])
        }
        return bytes
    }

    /**
     * Converts little-endian byte array to 16-bit ShortArray.
     */
    fun bytesToShorts(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ShortArray {
        val shortCount = length / 2
        val shorts = ShortArray(shortCount)
        val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = buffer.short
        }
        return shorts
    }

    /**
     * Encodes 16-bit PCM ShortArray samples into a standalone complete .wav ByteArray.
     */
    fun encodeToWavBytes(
        samples: ShortArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS
    ): ByteArray {
        val pcmBytes = shortsToBytes(samples)
        val header = createWavHeader(pcmBytes.size.toLong(), sampleRate, channels)
        val fullWav = ByteArray(header.size + pcmBytes.size)
        System.arraycopy(header, 0, fullWav, 0, header.size)
        System.arraycopy(pcmBytes, 0, fullWav, header.size, pcmBytes.size)
        return fullWav
    }

    /**
     * Writes 16-bit PCM ShortArray samples to a .wav file with valid header.
     */
    fun writeWavFile(
        outputFile: File,
        samples: ShortArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS
    ) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            val pcmBytes = shortsToBytes(samples)
            val header = createWavHeader(pcmBytes.size.toLong(), sampleRate, channels)
            fos.write(header)
            fos.write(pcmBytes)
            fos.flush()
        }
    }

    /**
     * Finalizes and patches the WAV header sizes in a previously streamed WAV file.
     * Useful for continuous/segmented streaming where total length is unknown up front.
     */
    fun finalizeWavHeader(wavFile: File) {
        if (!wavFile.exists() || wavFile.length() < WAV_HEADER_SIZE) return
        val totalLength = wavFile.length()
        val pcmDataLength = totalLength - WAV_HEADER_SIZE
        val totalDataLength = totalLength - 8

        RandomAccessFile(wavFile, "rw").use { raf ->
            // Seek to ChunkSize offset (index 4)
            raf.seek(4)
            raf.write(intToLittleEndian(totalDataLength.toInt()))

            // Seek to Subchunk2Size offset (index 40)
            raf.seek(40)
            raf.write(intToLittleEndian(pcmDataLength.toInt()))
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
