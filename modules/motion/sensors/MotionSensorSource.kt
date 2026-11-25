package com.guardiangrid.modules.motion.sensors

import com.guardiangrid.core.models.MotionSample

/**
 * Abstraction layer for motion hardware and synthetic data sources.
 * Enables running pure unit tests and reproducible simulations without Android hardware dependencies.
 */
interface MotionSensorSource {
    /**
     * Start streaming motion samples to the consumer.
     */
    fun start(onSample: (MotionSample) -> Unit)

    /**
     * Stop sensor streaming and release listeners.
     */
    fun stop()

    /**
     * Check if the underlying sensor hardware or stream is active.
     */
    fun isStreaming(): Boolean

    /**
     * Check whether hardware gyroscope is available.
     */
    fun hasGyroscope(): Boolean

    /**
     * Check whether hardware accelerometer is available.
     */
    fun hasAccelerometer(): Boolean = true
}
