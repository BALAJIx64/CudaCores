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

    override fun start(onSample: (MotionSample) -> Unit) {
        streaming.set(true)
        sampleListener = onSample
    }

    override fun stop() {
        streaming.set(false)
        sampleListener = null
    }

    override fun isStreaming(): Boolean = streaming.get()

    override fun hasGyroscope(): Boolean = simulatedGyroAvailable

    fun setGyroscopeAvailable(available: Boolean) {
        simulatedGyroAvailable = available
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
