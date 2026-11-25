package com.guardiangrid.modules.motion.sensors

import com.guardiangrid.core.models.MotionSample
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synthetic sensor source allowing programmatic injection of motion samples for testing and scenario verification.
 */
class SyntheticMotionSensorSource : MotionSensorSource {

    private var sampleListener: ((MotionSample) -> Unit)? = null
    private val streaming = AtomicBoolean(false)
    private var simulatedGyroAvailable: Boolean = true
    private var simulatedAccelAvailable: Boolean = true

    override fun start(onSample: (MotionSample) -> Unit) {
        if (!simulatedAccelAvailable) {
            streaming.set(false)
            sampleListener = null
            return
        }
        streaming.set(true)
        sampleListener = onSample
    }

    override fun stop() {
        streaming.set(false)
        sampleListener = null
    }

    override fun isStreaming(): Boolean = streaming.get()

    override fun hasGyroscope(): Boolean = simulatedGyroAvailable

    override fun hasAccelerometer(): Boolean = simulatedAccelAvailable

    fun setGyroscopeAvailable(available: Boolean) {
        simulatedGyroAvailable = available
    }

    fun setAccelerometerAvailable(available: Boolean) {
        simulatedAccelAvailable = available
        if (!available) {
            stop()
        }
    }

    /**
     * Inject a single motion sample.
     */
    fun emitSample(sample: MotionSample) {
        if (streaming.get()) {
            sampleListener?.invoke(sample)
        }
    }

    /**
     * Inject a sequence of samples.
     */
    fun emitSamples(samples: List<MotionSample>) {
        samples.forEach { emitSample(it) }
    }
}
