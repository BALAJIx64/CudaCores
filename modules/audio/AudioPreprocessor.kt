package com.guardiangrid.modules.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio preprocessing and acoustic feature extraction engine.
 *
 * Provides:
 * - PCM Short-to-Float normalization ([-1.0f, 1.0f])
 * - Framing and windowing (Hann / Hamming)
 * - Time-domain metrics: RMS energy, dBFS, Zero-Crossing Rate (ZCR), Peak Amplitude
 * - Frequency-domain metrics via Radix-2 Real FFT:
 *   - Power Spectrum & Magnitude Spectrum
 *   - Spectral Centroid (frequency center of mass)
 *   - Scream-Band High-Frequency Energy Ratio (1.5 kHz - 4.5 kHz)
 *   - Spectral Rolloff (85% energy threshold)
 *   - Spectral Flux (inter-frame spectral change)
 * - 16-band Mel-scale Filterbank energy profiles for speech keyword matching
 *
 * Owned by Member 1 (modules/audio).
 */
object AudioPreprocessor {

    const val DEFAULT_SAMPLE_RATE = 16000
    const val DEFAULT_FRAME_SIZE = 512    // 32 ms at 16 kHz
    const val DEFAULT_HOP_SIZE = 256      // 16 ms at 16 kHz (50% overlap)
    const val SCREAM_BAND_LOW_HZ = 1500.0
    const val SCREAM_BAND_HIGH_HZ = 4500.0

    /**
     * Converts a 16-bit linear PCM ShortArray to normalized FloatArray in [-1.0f, 1.0f].
     */
    fun toNormalizedFloat(samples: ShortArray, offset: Int = 0, length: Int = samples.size): FloatArray {
        val result = FloatArray(length)
        for (i in 0 until length) {
            result[i] = samples[offset + i] / 32768.0f
        }
        return result
    }

    /**
     * Computes the Root Mean Square (RMS) energy of a signal window.
     */
    fun computeRms(samples: FloatArray, offset: Int = 0, length: Int = samples.size): Float {
        if (length == 0) return 0.0f
        var sumSquares = 0.0
        for (i in offset until (offset + length)) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / length).toFloat()
    }

    /**
     * Converts RMS to Decibels Full Scale (dBFS).
     * Silence / zero amplitude maps to -100 dBFS floor.
     */
    fun rmsToDbfs(rms: Float): Float {
        if (rms <= 1e-5f) return -100.0f
        return (20.0 * log10(rms.toDouble().coerceIn(1e-5, 1.0))).toFloat()
    }

    /**
     * Computes the Zero-Crossing Rate (ZCR) of a frame.
     */
    fun computeZeroCrossingRate(samples: FloatArray, offset: Int = 0, length: Int = samples.size): Float {
        if (length <= 1) return 0.0f
        var zeroCrossings = 0
        for (i in (offset + 1) until (offset + length)) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        return zeroCrossings.toFloat() / (length - 1)
    }

    /**
     * Applies a Hann window in-place to reduce spectral leakage.
     * w[n] = 0.5 * (1 - cos(2*pi*n / (N-1)))
     */
    fun applyHannWindow(frame: FloatArray) {
        val n = frame.size
        if (n <= 1) return
        for (i in 0 until n) {
            val factor = 0.5 * (1.0 - cos((2.0 * PI * i) / (n - 1)))
            frame[i] = (frame[i] * factor).toFloat()
        }
    }

    /**
     * Slices an audio waveform into overlapping frames.
     */
    fun frameSignal(
        signal: FloatArray,
        frameSize: Int = DEFAULT_FRAME_SIZE,
        hopSize: Int = DEFAULT_HOP_SIZE
    ): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        if (signal.size < frameSize) {
            val padded = FloatArray(frameSize)
            System.arraycopy(signal, 0, padded, 0, signal.size)
            frames.add(padded)
            return frames
        }

        var start = 0
        while (start + frameSize <= signal.size) {
            val frame = FloatArray(frameSize)
            System.arraycopy(signal, start, frame, 0, frameSize)
            frames.add(frame)
            start += hopSize
        }
        return frames
    }

    /**
     * Computes the power spectrum (magnitude squared) for a frame of length N (power of 2)
     * using Cooley-Tukey Radix-2 FFT.
     *
     * @return Array of size (N / 2 + 1) containing power at each frequency bin.
     */
    fun computePowerSpectrum(frame: FloatArray): FloatArray {
        // Pad to next power of 2 if necessary
        val n = nextPowerOfTwo(frame.size)
        val real = DoubleArray(n)
        val imag = DoubleArray(n)

        for (i in frame.indices) {
            real[i] = frame[i].toDouble()
        }

        fft(real, imag)

        val halfSize = n / 2 + 1
        val power = FloatArray(halfSize)
        for (i in 0 until halfSize) {
            power[i] = ((real[i] * real[i] + imag[i] * imag[i]) / n).toFloat()
        }
        return power
    }

    /**
     * Computes the Spectral Centroid in Hz.
     * Represents the "brightness" or center of mass of the sound spectrum.
     * Human screams show an elevated centroid (> 2200 Hz) compared to normal speech (600–1400 Hz).
     */
    fun computeSpectralCentroid(powerSpectrum: FloatArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): Float {
        val numBins = powerSpectrum.size
        val binWidth = (sampleRate / 2.0) / (numBins - 1)

        var weightedSum = 0.0
        var totalPower = 0.0

        for (i in 0 until numBins) {
            val freq = i * binWidth
            val p = powerSpectrum[i].toDouble()
            weightedSum += freq * p
            totalPower += p
        }

        return if (totalPower > 1e-9) {
            (weightedSum / totalPower).toFloat()
        } else {
            0.0f
        }
    }

    /**
     * Computes the Scream-Band Energy Ratio:
     * Ratio of power in the characteristic human scream resonant zone (1500 Hz – 4500 Hz)
     * to total spectral power.
     */
    fun computeScreamBandRatio(
        powerSpectrum: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        lowHz: Double = SCREAM_BAND_LOW_HZ,
        highHz: Double = SCREAM_BAND_HIGH_HZ
    ): Float {
        val numBins = powerSpectrum.size
        val binWidth = (sampleRate / 2.0) / (numBins - 1)

        val lowBin = (lowHz / binWidth).toInt().coerceIn(0, numBins - 1)
        val highBin = (highHz / binWidth).toInt().coerceIn(lowBin, numBins - 1)

        var screamBandPower = 0.0
        var totalPower = 0.0

        for (i in 0 until numBins) {
            val p = powerSpectrum[i].toDouble()
            totalPower += p
            if (i in lowBin..highBin) {
                screamBandPower += p
            }
        }

        return if (totalPower > 1e-9) {
            (screamBandPower / totalPower).toFloat().coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
    }

    /**
     * Computes 16-band Mel-scale Filterbank energies across a power spectrum.
     * Provides a compact perceptual acoustic fingerprint used for speech keyword template matching.
     */
    fun computeMelFilterbank(
        powerSpectrum: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        numFilters: Int = 16,
        lowFreq: Double = 100.0,
        highFreq: Double = 7000.0
    ): FloatArray {
        val melLow = hzToMel(lowFreq)
        val melHigh = hzToMel(highFreq)
        val melPoints = DoubleArray(numFilters + 2)
        val melStep = (melHigh - melLow) / (numFilters + 1)

        for (i in 0 until (numFilters + 2)) {
            melPoints[i] = melLow + i * melStep
        }

        val binWidth = (sampleRate / 2.0) / (powerSpectrum.size - 1)
        val binIndices = IntArray(numFilters + 2)
        for (i in 0 until (numFilters + 2)) {
            val hz = melToHz(melPoints[i])
            binIndices[i] = (hz / binWidth).toInt().coerceIn(0, powerSpectrum.size - 1)
        }

        val filterbankEnergies = FloatArray(numFilters)
        for (m in 1..numFilters) {
            val left = binIndices[m - 1]
            val center = binIndices[m]
            val right = binIndices[m + 1]

            var energy = 0.0
            for (k in left until center) {
                val weight = if (center != left) (k - left).toDouble() / (center - left) else 1.0
                energy += powerSpectrum[k] * weight
            }
            for (k in center..right) {
                val weight = if (right != center) (right - k).toDouble() / (right - center) else 1.0
                energy += powerSpectrum[k] * weight
            }
            // Logarithmic compression (Log Mel Energy)
            filterbankEnergies[m - 1] = ln(max(energy, 1e-6)).toFloat()
        }

        return filterbankEnergies
    }

    /**
     * Computes gain-invariant normalized 16-band Mel filterbank energies.
     * The peak filterbank is set to 0.0 dB and all other filters are represented
     * as relative negative dB (typically 0.0 dB to -6.0 dB), ensuring speech formant
     * matching is robust to microphone gain variations and loudness levels.
     */
    fun computeNormalizedMelFilterbank(
        powerSpectrum: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        numFilters: Int = 16
    ): FloatArray {
        val rawMel = computeMelFilterbank(powerSpectrum, sampleRate, numFilters)
        var maxM = -100.0f
        for (v in rawMel) {
            if (v > maxM) maxM = v
        }
        val normalized = FloatArray(numFilters)
        for (i in 0 until numFilters) {
            normalized[i] = (rawMel[i] - maxM).coerceIn(-6.0f, 0.0f)
        }
        return normalized
    }

    /**
     * Estimates dynamic background noise floor (dBFS) from a sequence of frame dBFS values
     * using the 25th percentile of quiet frames. Clamped between -65.0 dBFS and -32.0 dBFS.
     */
    fun estimateNoiseFloorDbfs(frameDbfs: FloatArray): Float {
        if (frameDbfs.isEmpty()) return -50.0f
        val sorted = frameDbfs.clone().apply { sort() }
        val p25Index = (sorted.size * 0.25f).toInt().coerceIn(0, sorted.size - 1)
        return sorted[p25Index].coerceIn(-65.0f, -32.0f)
    }

    /**
     * Counts syllable energy peaks in a smoothed dBFS envelope using a peak-valley hysteresis
     * state machine. Enforces a minimum prominence dip (minValleyDipDb) between successive peaks,
     * ensuring falling utterance tails and intra-phone fluctuations are not miscounted.
     */
    fun countSyllablePeaks(frameDbfs: FloatArray, thresholdDbfs: Float, minValleyDipDb: Float = 3.0f): Int {
        if (frameDbfs.isEmpty()) return 0
        val smoothed = FloatArray(frameDbfs.size)
        for (i in frameDbfs.indices) {
            var sum = frameDbfs[i]
            var count = 1
            if (i > 0) { sum += frameDbfs[i - 1]; count++ }
            if (i < frameDbfs.size - 1) { sum += frameDbfs[i + 1]; count++ }
            smoothed[i] = sum / count
        }

        var peaks = 0
        var state = "IDLE" // IDLE, RISING, FALLING
        var peakMax = -100.0f
        var valleyMin = 100.0f

        for (v in smoothed) {
            when (state) {
                "IDLE" -> {
                    if (v >= thresholdDbfs) {
                        state = "RISING"
                        peakMax = v
                    }
                }
                "RISING" -> {
                    if (v > peakMax) {
                        peakMax = v
                    } else if (v < peakMax - minValleyDipDb) {
                        peaks++
                        state = "FALLING"
                        valleyMin = v
                    }
                }
                "FALLING" -> {
                    if (v < valleyMin) {
                        valleyMin = v
                    } else if (v > valleyMin + minValleyDipDb && v >= thresholdDbfs) {
                        state = "RISING"
                        peakMax = v
                    }
                }
            }
        }
        if (state == "RISING" && peakMax >= thresholdDbfs) {
            peaks++
        }
        return max(1, peaks)
    }

    // --- Helper Conversions & In-Place Cooley-Tukey Radix-2 FFT ---

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    private fun nextPowerOfTwo(value: Int): Int {
        var n = 1
        while (n < value) {
            n = n shl 1
        }
        return n
    }

    /**
     * In-place Cooley-Tukey Radix-2 Fast Fourier Transform.
     */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Danielson-Lanczos section
        var step = 2
        while (step <= n) {
            val halfStep = step shr 1
            val theta = -2.0 * PI / step
            val wStepR = cos(theta)
            val wStepI = sin(theta)

            var k = 0
            while (k < n) {
                var wR = 1.0
                var wI = 0.0
                for (m in 0 until halfStep) {
                    val idx = k + m
                    val match = idx + halfStep

                    val tr = wR * real[match] - wI * imag[match]
                    val ti = wR * imag[match] + wI * real[match]

                    real[match] = real[idx] - tr
                    imag[match] = imag[idx] - ti
                    real[idx] += tr
                    imag[idx] += ti

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                k += step
            }
            step = step shl 1
        }
    }
}
