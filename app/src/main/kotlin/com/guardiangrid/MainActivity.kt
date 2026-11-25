package com.guardiangrid

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import com.guardiangrid.modules.communication.queue.FileEmergencyQueueRepository
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.modules.motion.fusion.MultimodalFusionEngineImpl
import com.guardiangrid.modules.motion.sensors.AndroidMotionSensorSource
import com.guardiangrid.ui.DashboardScreen
import com.guardiangrid.ui.GuardianGridMainScreen
import com.guardiangrid.ui.permissions.PermissionManager
import com.guardiangrid.ui.theme.GuardianGridTheme
import java.io.File

/**
 * Main application entry point for GuardianGrid.
 * Owned by Member 4 (Core / UI / Integration Coordinator).
 * Manages UI composition, system permission flow, location updates, and hardware discreet triggers.
 */
class MainActivity : ComponentActivity() {

    private lateinit var locationManager: GuardianLocationManager
    private lateinit var coordinator: EmergencyCoordinatorImpl

    private lateinit var audioModule: AudioModuleStub
    private lateinit var motionDetector: MotionAnomalyDetectorImpl
    private lateinit var fusionEngine: MultimodalFusionEngineImpl
    private lateinit var communicator: EmergencyCommunicator

    // Hardware volume key triple-press stealth activation tracker
    private var volumePressCount = 0
    private var lastVolumePressTime = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        PermissionManager.syncPermissionsWithState(this)
        if (locationManager.hasLocationPermission()) {
            locationManager.getLastKnownLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enforce consistent midnight-navy system bar icons
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Retrieve application-scoped dependencies from GuardianGridContainer
        val app = application as GuardianGridApp
        val container = app.container

        locationManager = container.locationManager
        communicator = container.communicator
        audioModule = container.audioModule
        motionDetector = container.motionDetector
        coordinator = container.coordinator

        // Sync initial permission states
        PermissionManager.syncPermissionsWithState(this)
        if (locationManager.hasLocationPermission()) {
            locationManager.getLastKnownLocation()
        }

        setContent {
            GuardianGridTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GuardianGridMainScreen(
                        coordinator = coordinator,
                        locationManager = locationManager,
                        audioModule = audioModule,
                        motionDetector = motionDetector,
                        communicator = communicator,
                        onRequestPermissions = { requestAppPermissions() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionManager.syncPermissionsWithState(this)
        if (locationManager.hasLocationPermission()) {
            locationManager.getLastKnownLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Protection is maintained by GuardianForegroundService across Activity destruction / recreation.
        // Only stop location updates if protection is NOT active.
        val state = EmergencyStateHolder.state.value
        if (!state.isProtectionActive && ::locationManager.isInitialized) {
            locationManager.stopLocationUpdates()
        }
    }

    /**
     * Request all missing runtime safety permissions.
     */
    private fun requestAppPermissions() {
        val permissionsToRequest = PermissionManager.REQUIRED_PERMISSIONS
            .map { it.permission }
            .filter { !PermissionManager.isPermissionGranted(this, it) }
            .toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            PermissionManager.syncPermissionsWithState(this)
        }
    }

    /**
     * Stealth / Discreet Hardware SOS Trigger.
     * Pressing Volume Down or Volume Up 3 times rapidly within 1.5 seconds triggers discreet emergency mode.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastVolumePressTime < 1500L) {
                volumePressCount++
                if (volumePressCount >= 3) {
                    coordinator.triggerDiscreetSos(triggerSource = "Hardware_Volume_Key_Triple_Press")
                    volumePressCount = 0
                    return true
                }
            } else {
                volumePressCount = 1
            }
            lastVolumePressTime = currentTime
        }
        return super.onKeyDown(keyCode, event)
    }
}
