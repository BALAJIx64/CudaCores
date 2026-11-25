package com.guardiangrid.modules.motion.anomaly

import com.guardiangrid.core.interfaces.MotionAnomalyDetector
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.modules.motion.falsealarm.FalseAlarmFilter
import com.guardiangrid.modules.motion.features.MotionBaselineCalibrator
import com.guardiangrid.modules.motion.features.MotionFeatureExtractor
import com.guardiangrid.modules.motion.features.MotionFeatures
import com.guardiangrid.modules.motion.sensors.MotionSensorSource
import com.guardiangrid.modules.motion.sensors.SyntheticMotionSensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Snapshot of live inertial motion telemetry for UI diagnostics and dashboard health indicators.
 */
data class LiveMotionMetrics(
    val magnitude: Float = 9.81f,
    val peakMagnitude: Float = 9.81f,
    val angularSpeed: Float = 0f,
    val dynamicAcceleration: Float = 0f,
    val hasAccelerometer: Boolean = true,
    val hasGyroscope: Boolean = false,
    val isCalibrated: Boolean = false,
    val lastSampleEpochMs: Long = 0L
)

/**
 * Production implementation of [MotionAnomalyDetector].
 * Ingests 3-axis accelerometer and gyroscope data, extracts kinematic features sequentially,
 * filters benign movements, and emits high-confidence distress signals for falls and struggles.
 */
class MotionAnomalyDetectorImpl(
    private var sensorSource: MotionSensorSource = SyntheticMotionSensorSource(),
    private var config: MotionThresholdConfig = MotionThresholdConfig(),
    private val baselineCalibrator: MotionBaselineCalibrator = MotionBaselineCalibrator(),
    private val falseAlarmFilter: FalseAlarmFilter = FalseAlarmFilter()
) : MotionAnomalyDetector {

    private val isMonitoring = AtomicBoolean(false)
    private var detectorScope: CoroutineScope? = null
    private var sampleChannel: Channel<MotionSample>? = null

    private val signalFlow = MutableSharedFlow<DistressSignal>(extraBufferCapacity = 64)
    private val sampleFlow = MutableSharedFlow<MotionSample>(extraBufferCapacity = 128)
    private val _liveMetrics = MutableStateFlow(LiveMotionMetrics())
    val liveMetrics: StateFlow<LiveMotionMetrics> = _liveMetrics.asStateFlow()

    private val featureExtractor = MotionFeatureExtractor(
        windowCapacityMs = 1000L,
        baselineCalibrator = baselineCalibrator
    )

    private var lastAnomalyTimestampMs = 0L
    private val anomalyCooldownMs = 1000L

    fun setSensorSource(source: MotionSensorSource) {
        val wasActive = isMonitoring.get()
        if (wasActive) {
            stopMonitoring()
        }
        this.sensorSource = source
        if (wasActive) {
            startMonitoring()
        }
    }

    fun updateConfig(newConfig: MotionThresholdConfig) {
        this.config = newConfig
    }

    fun getConfig(): MotionThresholdConfig = config

    fun hasAccelerometer(): Boolean = sensorSource.hasAccelerometer()

    override fun startMonitoring(): Flow<DistressSignal> {
        if (!isMonitoring.compareAndSet(false, true)) {
            return signalFlow.asSharedFlow()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        detectorScope = scope
        val channel = Channel<MotionSample>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        sampleChannel = channel

        featureExtractor.reset()

        // Single sequential consumer preserves strict chronological sample order without per-sample coroutine allocation
        scope.launch {
            for (sample in channel) {
                sampleFlow.tryEmit(sample)
                processSample(sample)
            }
        }

        sensorSource.start { sample ->
            channel.trySend(sample)
        }

        // If hardware accelerometer is missing or failed registration, do not claim monitoring is active
        if (!sensorSource.isStreaming()) {
            _liveMetrics.value = _liveMetrics.value.copy(
                hasAccelerometer = sensorSource.hasAccelerometer(),
                hasGyroscope = sensorSource.hasGyroscope()
            )
            channel.close()
            sampleChannel = null
            scope.cancel()
            detectorScope = null
            isMonitoring.set(false)
        } else {
            _liveMetrics.value = _liveMetrics.value.copy(
                hasAccelerometer = sensorSource.hasAccelerometer(),
                hasGyroscope = sensorSource.hasGyroscope()
            )
        }

        return signalFlow.asSharedFlow()
    }

    override fun stopMonitoring() {
        if (!isMonitoring.compareAndSet(true, false)) return

        sensorSource.stop()
        sampleChannel?.close()
        sampleChannel = null
        detectorScope?.cancel()
        detectorScope = null
        featureExtractor.reset()
        _liveMetrics.value = LiveMotionMetrics(
            hasAccelerometer = sensorSource.hasAccelerometer(),
            hasGyroscope = sensorSource.hasGyroscope(),
            isCalibrated = baselineCalibrator.isCalibrated
        )
    }

    override fun isMonitoring(): Boolean = isMonitoring.get()

    override fun calibrateBaseline() {
        baselineCalibrator.computeBaseline()
        _liveMetrics.value = _liveMetrics.value.copy(isCalibrated = baselineCalibrator.isCalibrated)
    }

    override fun observeMotionSamples(): Flow<MotionSample> {
        return sampleFlow.asSharedFlow()
    }

    /**
     * Process each incoming inertial sample and classify motion anomalies.
     */
    private suspend fun processSample(sample: MotionSample) {
        if (!isMonitoring.get()) return

        // Baseline feeding if in calibration stage
        if (!baselineCalibrator.isCalibrated) {
            baselineCalibrator.addCalibrationSample(sample)
        }

        val features = featureExtractor.processSample(sample)
        _liveMetrics.value = LiveMotionMetrics(
            magnitude = features.currentMagnitude,
            peakMagnitude = features.peakMagnitude,
            angularSpeed = features.currentAngularSpeed,
            dynamicAcceleration = features.dynamicAcceleration,
            hasAccelerometer = sensorSource.hasAccelerometer(),
            hasGyroscope = sensorSource.hasGyroscope(),
            isCalibrated = baselineCalibrator.isCalibrated,
            lastSampleEpochMs = features.timestampEpochMs
        )

        val now = sample.timestampEpochMs

        // Avoid spamming duplicate anomalies within cooldown window
        if (now - lastAnomalyTimestampMs in 1..anomalyCooldownMs) {
            return
        }

        val classified = detectAnomaly(features) ?: return

        // Run false alarm filter
        if (falseAlarmFilter.isFalseAlarm(features, classified.first)) {
            return
        }

        lastAnomalyTimestampMs = now

        val sanitizedConfidence = sanitizeConfidence(classified.second)

        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = classified.first,
            confidence = sanitizedConfidence,
            timestampEpochMs = now,
            source = "MotionAnomalyDetector",
            rawScore = features.currentMagnitude,
            metadata = mapOf(
                "peakMagnitude" to "%.2f".format(features.peakMagnitude),
                "peakAngularSpeed" to "%.2f".format(features.peakAngularSpeed),
                "peakJerk" to "%.2f".format(features.peakJerk),
                "variance" to "%.2f".format(features.varianceMagnitude),
                "hasPrecedingFreefall" to features.hasFreefallPrecedingImpact.toString(),
                "sensorHasGyro" to sensorSource.hasGyroscope().toString()
            )
        )

        signalFlow.emit(signal)
    }

    /**
     * Anomaly classification rules evaluating fall impact, struggle, and snatch patterns.
     * Incorporates graceful accelerometer-only fallback when gyroscope is missing.
     */
    private fun detectAnomaly(features: MotionFeatures): Pair<DistressType, Float>? {
        val effImpact = config.effectiveImpactThreshold()
        val effVariance = config.effectiveShakeVarianceThreshold()
        val effAngular = config.effectiveAngularSpeedThreshold()
        val effSnatchJerk = config.effectiveSnatchJerkThreshold()
        val hasGyro = sensorSource.hasGyroscope()

        // 1. Fall Detection: Freefall valley followed by sharp impact spike
        if (features.currentMagnitude >= effImpact && features.hasFreefallPrecedingImpact) {
            val confidence = ((features.currentMagnitude - effImpact) / 20.0f + 0.80f).coerceIn(0.80f, 0.98f)
            return Pair(DistressType.FALL_IMPACT, confidence)
        }

        // Severe standalone impact without explicit freefall
        if (features.currentMagnitude >= effImpact * 1.25f) {
            val confidence = ((features.currentMagnitude - effImpact) / 25.0f + 0.75f).coerceIn(0.75f, 0.95f)
            return Pair(DistressType.FALL_IMPACT, confidence)
        }

        // 2. Violent Struggle / Frantic Shaking:
        val isViolentShake = if (hasGyro) {
            features.varianceMagnitude >= effVariance &&
                    features.directionReversals >= config.violentShakeReversalThreshold &&
                    features.peakAngularSpeed >= effAngular
        } else {
            // Accelerometer fallback when gyroscope hardware is absent
            features.varianceMagnitude >= effVariance * 1.25f &&
                    features.directionReversals >= (config.violentShakeReversalThreshold + 1)
        }

        if (isViolentShake) {
            val confidence = ((features.varianceMagnitude - effVariance) / 20.0f + 0.78f).coerceIn(0.78f, 0.94f)
            return Pair(DistressType.MOTION_ANOMALY, confidence)
        }

        // 3. Device Snatch: Extreme sudden jerk + high acceleration magnitude
        val isSnatch = features.peakJerk >= effSnatchJerk &&
                features.currentMagnitude >= config.snatchMagnitudeThreshold

        if (isSnatch) {
            val confidence = ((features.peakJerk - effSnatchJerk) / 80.0f + 0.72f).coerceIn(0.72f, 0.90f)
            return Pair(DistressType.MOTION_ANOMALY, confidence)
        }

        return null
    }

    private fun sanitizeConfidence(value: Float): Float {
        return if (value.isNaN() || value.isInfinite()) 0.0f else value.coerceIn(0.0f, 1.0f)
    }

    /**
     * Testing / simulation bridge to emit a simulated motion anomaly through the detector's flow.
     */
    suspend fun emitSimulatedAnomaly(confidence: Float, type: DistressType = DistressType.FALL_IMPACT) {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = type,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            timestampEpochMs = System.currentTimeMillis(),
            source = "SimulatedMotionAnomaly",
            rawScore = confidence,
            metadata = mapOf("simulation" to "true")
        )
        signalFlow.emit(signal)
    }
}
