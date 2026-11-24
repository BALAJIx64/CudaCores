package com.guardiangrid.modules.motion.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.guardiangrid.core.models.MotionSample
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production sensor source connecting to Android's SensorManager.
 * Registers Sensor.TYPE_ACCELEROMETER and Sensor.TYPE_GYROSCOPE.
 * Ensures consistent timestamp correlation, resource cleanup, and handles missing gyroscope gracefully.
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

    @Volatile private var hasGyro: Boolean = false

    override fun start(onSample: (MotionSample) -> Unit) {
        if (!streaming.compareAndSet(false, true)) return
        this.sampleListener = onSample

        val sm = sensorManager
        if (sm == null) {
            streaming.set(false)
            return
        }

        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        hasGyro = gyroscope != null

        accelerometer?.let {
            sm.registerListener(this, it, sensorDelay)
        }
        gyroscope?.let {
            sm.registerListener(this, it, sensorDelay)
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
    }

    override fun isStreaming(): Boolean = streaming.get()

    override fun hasGyroscope(): Boolean = hasGyro

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !streaming.get()) return

        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGx = event.values.getOrElse(0) { 0f }
                latestGy = event.values.getOrElse(1) { 0f }
                latestGz = event.values.getOrElse(2) { 0f }
                latestGyroTimestampMs = now
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrElse(0) { 0f }
                val ay = event.values.getOrElse(1) { 0f }
                val az = event.values.getOrElse(2) { 0f }

                // Discard stale gyroscope readings older than 350ms
                val isGyroStale = (now - latestGyroTimestampMs) > 350L
                val gx = if (hasGyro && !isGyroStale) latestGx else 0f
                val gy = if (hasGyro && !isGyroStale) latestGy else 0f
                val gz = if (hasGyro && !isGyroStale) latestGz else 0f

                val sample = MotionSample(
                    timestampEpochMs = now,
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for inertial tracking
    }
}
