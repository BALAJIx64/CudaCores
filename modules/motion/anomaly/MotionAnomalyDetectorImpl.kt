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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production implementation of [MotionAnomalyDetector].
 * Ingests 3-axis accelerometer and gyroscope data, extracts kinematic features,
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

    private val signalFlow = MutableSharedFlow<DistressSignal>(extraBufferCapacity = 64)
    private val sampleFlow = MutableSharedFlow<MotionSample>(extraBufferCapacity = 128)

    private val featureExtractor = MotionFeatureExtractor(
        windowCapacityMs = 1000L,
        baselineCalibrator = baselineCalibrator
    )

    private var lastAnomalyTimestampMs = 0L
    private val anomalyCooldownMs = 1200L

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

    override fun startMonitoring(): Flow<DistressSignal> {
        if (isMonitoring.getAndSet(true)) {
            return signalFlow.asSharedFlow()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        detectorScope = scope

        featureExtractor.reset()

        sensorSource.start { sample ->
            scope.launch {
                sampleFlow.tryEmit(sample)
                processSample(sample)
            }
        }

        return signalFlow.asSharedFlow()
    }

    override fun stopMonitoring() {
        if (!isMonitoring.getAndSet(false)) return

        sensorSource.stop()
        detectorScope?.cancel()
        detectorScope = null
        featureExtractor.reset()
    }

    override fun isMonitoring(): Boolean = isMonitoring.get()

    override fun calibrateBaseline() {
        baselineCalibrator.computeBaseline()
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
        val now = sample.timestampEpochMs

        // Avoid spamming duplicate anomalies within cooldown window
        if (now - lastAnomalyTimestampMs < anomalyCooldownMs) {
            return
        }

        val classified = detectAnomaly(features) ?: return

        // Run false alarm filter
        if (falseAlarmFilter.isFalseAlarm(features, classified.first)) {
            return
        }

        lastAnomalyTimestampMs = now

        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = classified.first,
            confidence = classified.second,
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
     */
    private fun detectAnomaly(features: MotionFeatures): Pair<DistressType, Float>? {
        val effImpact = config.effectiveImpactThreshold()
        val effVariance = config.effectiveShakeVarianceThreshold()
        val effAngular = config.effectiveAngularSpeedThreshold()
        val effSnatchJerk = config.effectiveSnatchJerkThreshold()

        // 1. Fall Detection: Freefall valley followed by sharp impact spike
        if (features.currentMagnitude >= effImpact && features.hasFreefallPrecedingImpact) {
            // Confidence scaled by impact severity
            val confidence = ((features.currentMagnitude - effImpact) / 20.0f + 0.80f).coerceIn(0.80f, 0.98f)
            return Pair(DistressType.FALL_IMPACT, confidence)
        }

        // Severe standalone impact without explicit freefall (e.g. violent crash or direct hit)
        if (features.currentMagnitude >= effImpact * 1.25f) {
            val confidence = ((features.currentMagnitude - effImpact) / 25.0f + 0.75f).coerceIn(0.75f, 0.95f)
            return Pair(DistressType.FALL_IMPACT, confidence)
        }

        // 2. Violent Struggle / Frantic Shaking:
        // High magnitude variance + high direction reversals + high angular speed
        val isViolentShake = features.varianceMagnitude >= effVariance &&
                features.directionReversals >= config.violentShakeReversalThreshold &&
                features.peakAngularSpeed >= effAngular

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
}
