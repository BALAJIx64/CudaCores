package com.guardiangrid.modules.motion

import com.guardiangrid.core.interfaces.FusionResult
import com.guardiangrid.core.interfaces.MotionAnomalyDetector
import com.guardiangrid.core.interfaces.MultimodalFusionEngine
import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.MotionSample
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Bridge implementation of motion anomaly detection and sensor fusion.
 * Maintains full contract compatibility with tests and coordinator while
 * backed by [MotionAnomalyDetectorImpl] and [MultimodalFusionEngineImpl].
 */
class MotionModuleStub(
    private val realDetector: MotionAnomalyDetectorImpl = MotionAnomalyDetectorImpl(),
    private val realFusionEngine: MultimodalFusionEngineImpl = MultimodalFusionEngineImpl()
) : MotionAnomalyDetector, MultimodalFusionEngine {

    private val signalFlow = MutableSharedFlow<DistressSignal>(extraBufferCapacity = 64)

    override fun startMonitoring(): Flow<DistressSignal> {
        realDetector.startMonitoring()
        return realDetector.startMonitoring()
    }

    override fun stopMonitoring() {
        realDetector.stopMonitoring()
    }

    override fun isMonitoring(): Boolean = realDetector.isMonitoring()

    override fun calibrateBaseline() {
        realDetector.calibrateBaseline()
    }

    override fun observeMotionSamples(): Flow<MotionSample> {
        return realDetector.observeMotionSamples()
    }

    override fun evaluateSignals(signals: List<DistressSignal>, timeWindowMs: Long): FusionResult {
        return realFusionEngine.evaluateSignals(signals, timeWindowMs)
    }

    /**
     * Testing utility to simulate a detected motion anomaly.
     */
    suspend fun emitSimulatedMotionAnomaly(confidence: Float, type: DistressType = DistressType.FALL_IMPACT) {
        val signal = DistressSignal(
            id = UUID.randomUUID().toString(),
            type = type,
            confidence = confidence,
            source = "MotionStub",
            metadata = mapOf("stub" to "true")
        )
        signalFlow.emit(signal)
    }
}
