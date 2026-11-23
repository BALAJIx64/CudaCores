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

    private var hasGyro: Boolean = false

    override fun start(onSample: (MotionSample) -> Unit) {
        if (streaming.getAndSet(true)) return
        this.sampleListener = onSample

        val sm = sensorManager ?: return
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
        if (!streaming.getAndSet(false)) return
        sensorManager?.unregisterListener(this)
        sampleListener = null
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
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrElse(0) { 0f }
                val ay = event.values.getOrElse(1) { 0f }
                val az = event.values.getOrElse(2) { 0f }

                val sample = MotionSample(
                    timestampEpochMs = now,
                    ax = ax,
                    ay = ay,
                    az = az,
                    gx = latestGx,
                    gy = latestGy,
                    gz = latestGz
                )
                sampleListener?.invoke(sample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for inertial tracking
    }
}
