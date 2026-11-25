package com.guardiangrid.modules.motion.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.guardiangrid.core.models.MotionSample
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production sensor source connecting to Android's SensorManager.
 * Registers Sensor.TYPE_ACCELEROMETER and Sensor.TYPE_GYROSCOPE.
 * Converts hardware monotonic SensorEvent timestamps into consistent epoch milliseconds,
 * guards against missing/unreliable sensors and NaN values, and cleanly unregisters listeners.
 */
class AndroidMotionSensorSource(
    private val context: Context,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_GAME
) : MotionSensorSource, SensorEventListener {

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    private var sampleListener: ((MotionSample) -> Unit)? = null
    private val streaming = AtomicBoolean(false)

    @Volatile private var latestGx: Float = 0f
    @Volatile private var latestGy: Float = 0f
    @Volatile private var latestGz: Float = 0f
    @Volatile private var latestGyroTimestampMs: Long = 0L
    @Volatile private var lastAccelTimestampMs: Long = 0L
    @Volatile private var bootEpochOffsetMs: Long = 0L

    @Volatile private var hasAccel: Boolean = false
    @Volatile private var hasGyro: Boolean = false

    override fun start(onSample: (MotionSample) -> Unit) {
        if (!streaming.compareAndSet(false, true)) return

        val sm = sensorManager
        if (sm == null) {
            hasAccel = false
            hasGyro = false
            streaming.set(false)
            return
        }

        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        hasAccel = accelerometer != null
        hasGyro = gyroscope != null

        if (accelerometer == null) {
            streaming.set(false)
            sampleListener = null
            return
        }

        this.sampleListener = onSample
        bootEpochOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        lastAccelTimestampMs = 0L
        latestGyroTimestampMs = 0L

        val accelRegistered = sm.registerListener(this, accelerometer, sensorDelay)
        if (!accelRegistered) {
            hasAccel = false
            sampleListener = null
            streaming.set(false)
            return
        }

        gyroscope?.let {
            val gyroRegistered = sm.registerListener(this, it, sensorDelay)
            if (!gyroRegistered) {
                hasGyro = false
            }
        }
    }

    override fun stop() {
        if (!streaming.compareAndSet(true, false)) return
        sensorManager?.unregisterListener(this)
        sampleListener = null
        latestGx = 0f
        latestGy = 0f
        latestGz = 0f
        latestGyroTimestampMs = 0L
        lastAccelTimestampMs = 0L
    }

    override fun isStreaming(): Boolean = streaming.get()

    override fun hasGyroscope(): Boolean = hasGyro

    override fun hasAccelerometer(): Boolean = hasAccel

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !streaming.get()) return

        // Convert hardware monotonic nanoseconds (elapsedRealtimeNanos) to aligned epoch milliseconds
        // to preserve exact inter-sample dt even when HAL delivers batched sensor bursts.
        val hardwareMs = (event.timestamp / 1_000_000L) + bootEpochOffsetMs
        val sampleTimestampMs = if (event.timestamp > 0L) hardwareMs else System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGx = sanitizeAxis(event.values.getOrElse(0) { 0f })
                latestGy = sanitizeAxis(event.values.getOrElse(1) { 0f })
                latestGz = sanitizeAxis(event.values.getOrElse(2) { 0f })
                latestGyroTimestampMs = sampleTimestampMs
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = sanitizeAxis(event.values.getOrElse(0) { 0f })
                val ay = sanitizeAxis(event.values.getOrElse(1) { 0f })
                val az = sanitizeAxis(event.values.getOrElse(2) { 0f })

                // Enforce strictly non-decreasing monotonic timestamp for downstream jerk derivatives
                val monotonicMs = if (lastAccelTimestampMs > 0L && sampleTimestampMs <= lastAccelTimestampMs) {
                    lastAccelTimestampMs + 1L
                } else {
                    sampleTimestampMs
                }
                lastAccelTimestampMs = monotonicMs

                // Discard stale gyroscope readings older than 350ms
                val isGyroStale = (monotonicMs - latestGyroTimestampMs) > 350L
                val gx = if (hasGyro && !isGyroStale) latestGx else 0f
                val gy = if (hasGyro && !isGyroStale) latestGy else 0f
                val gz = if (hasGyro && !isGyroStale) latestGz else 0f

                val sample = MotionSample(
                    timestampEpochMs = monotonicMs,
                    ax = ax,
                    ay = ay,
                    az = az,
                    gx = gx,
                    gy = gy,
                    gz = gz
                )
                sampleListener?.invoke(sample)
            }
        }
    }

    private fun sanitizeAxis(value: Float): Float {
        return if (value.isNaN() || value.isInfinite()) 0f else value
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for inertial tracking
    }
}
