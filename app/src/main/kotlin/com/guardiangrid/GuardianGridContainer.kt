package com.guardiangrid

import android.content.Context
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.FileIncidentRepository
import com.guardiangrid.core.state.IncidentRepository
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.persistence.FileSettingsRepository
import com.guardiangrid.modules.communication.persistence.SettingsRepository
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.retry.EmergencyDeliveryWorkScheduler
import com.guardiangrid.modules.communication.security.SecureCredentialStorage
import com.guardiangrid.modules.communication.security.SecureTelegramCredentialStore
import com.guardiangrid.modules.communication.sms.AndroidSmsSender
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import com.guardiangrid.modules.motion.sensors.AndroidMotionSensorSource
import com.guardiangrid.modules.motion.sensors.MotionSensorSource
import java.io.File

/**
 * Application-level dependency container for GuardianGrid.
 * Ensures a single shared instance of the coordinator, sensor sources, audio classifiers,
 * and communication pipelines across the entire application process (Activities and Services).
 *
 * Guarantees:
 * - Deterministic lazy initialization.
 * - Zero Activity leaks (retains only applicationContext).
 * - Avoids duplicate microphone or sensor capture sessions during Activity recreation.
 */
interface GuardianGridContainer {
    val locationManager: GuardianLocationManager
    val communicator: EmergencyCommunicator
    val audioModule: AudioModuleStub
    val motionDetector: MotionAnomalyDetectorImpl
    val fusionEngine: MultimodalFusionEngineImpl
    val coordinator: EmergencyCoordinatorImpl
    val settingsRepository: SettingsRepository
    val incidentRepository: IncidentRepository
    val secureCredentialStore: SecureCredentialStorage
}

/**
 * Production implementation of [GuardianGridContainer] using real Android system services
 * and application storage paths.
 */
class DefaultGuardianGridContainer(
    context: Context? = null,
    customLocationManager: GuardianLocationManager? = null,
    customCommunicator: EmergencyCommunicator? = null,
    customAudioModule: AudioModuleStub? = null,
    customMotionSource: MotionSensorSource? = null,
    customMotionDetector: MotionAnomalyDetectorImpl? = null,
    customCoordinator: EmergencyCoordinatorImpl? = null,
    customSettingsRepository: SettingsRepository? = null,
    customIncidentRepository: IncidentRepository? = null,
    customCredentialStore: SecureCredentialStorage? = null
) : GuardianGridContainer {

    val appContext: Context? = context?.applicationContext ?: context

    override val settingsRepository: SettingsRepository by lazy {
        customSettingsRepository ?: run {
            val baseDir = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "gg_container_files")
            FileSettingsRepository(File(baseDir, "settings").apply { mkdirs() })
        }
    }

    override val incidentRepository: IncidentRepository by lazy {
        customIncidentRepository ?: run {
            val baseDir = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "gg_container_files")
            FileIncidentRepository(File(baseDir, "incidents").apply { mkdirs() })
        }
    }

    override val secureCredentialStore: SecureCredentialStorage by lazy {
        customCredentialStore ?: run {
            val baseDir = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "gg_container_files")
            SecureTelegramCredentialStore(File(baseDir, "security").apply { mkdirs() })
        }
    }

    override val locationManager: GuardianLocationManager by lazy {
        customLocationManager ?: GuardianLocationManager(appContext)
    }

    override val communicator: EmergencyCommunicator by lazy {
        customCommunicator ?: run {
            val baseDir = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "gg_container_files").apply { mkdirs() }
            val queueDir = File(baseDir, "emergency_queue")
            val queueRepo = FileEmergencyQueueRepository(queueDir)
            EmergencyCommunicatorImpl(
                queueRepository = queueRepo,
                smsSender = if (appContext != null) AndroidSmsSender(appContext) else MockSmsSender(),
                onOfflineRetryScheduled = {
                    appContext?.let { EmergencyDeliveryWorkScheduler.scheduleRetry(it) }
                }
            )
        }
    }

    override val audioModule: AudioModuleStub by lazy {
        customAudioModule ?: run {
            val baseDir = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "gg_container_files").apply { mkdirs() }
            val cacheDir = appContext?.cacheDir ?: File(baseDir, "cache").apply { mkdirs() }
            val modelsDir = File(baseDir, "models").apply { mkdirs() }

            // Ensure neural model assets (YAMNet) are extracted from APK assets to modelsDir once
            extractModelAssetIfNeeded("models/yamnet.tflite", File(modelsDir, "yamnet.tflite"))
            extractModelAssetIfNeeded("models/yamnet_label_list.txt", File(modelsDir, "yamnet_label_list.txt"))

            AudioModuleStub(
                cacheDirectory = cacheDir,
                modelDirectory = modelsDir
            )
        }
    }

    private fun extractModelAssetIfNeeded(assetPath: String, targetFile: File) {
        val ctx = appContext ?: return
        try {
            if (targetFile.exists() && targetFile.length() > 0L) return
            ctx.assets.open(assetPath).use { input ->
                java.io.FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            // Handled gracefully for unit tests or environments without APK asset packaging
        }
    }

    private val motionSensorSource: MotionSensorSource by lazy {
        customMotionSource ?: (appContext?.let { AndroidMotionSensorSource(it) }
            ?: throw IllegalStateException("MotionSensorSource requires non-null context or customMotionSource"))
    }

    override val motionDetector: MotionAnomalyDetectorImpl by lazy {
        customMotionDetector ?: MotionAnomalyDetectorImpl(sensorSource = motionSensorSource)
    }

    override val fusionEngine: MultimodalFusionEngineImpl by lazy {
        MultimodalFusionEngineImpl()
    }

    override val coordinator: EmergencyCoordinatorImpl by lazy {
        customCoordinator ?: EmergencyCoordinatorImpl(
            locationProvider = {
                locationManager.lastLocation.value ?: locationManager.getLastKnownLocation()
            },
            communicator = communicator,
            audioDetector = audioModule,
            motionDetector = motionDetector,
            fusionEngine = fusionEngine,
            incidentRepository = incidentRepository
        )
    }
}
