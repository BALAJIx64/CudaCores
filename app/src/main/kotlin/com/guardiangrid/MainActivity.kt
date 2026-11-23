package com.guardiangrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.communication.EmergencyCommunicatorStub
import com.guardiangrid.modules.motion.MotionModuleStub
import com.guardiangrid.ui.DashboardScreen
import com.guardiangrid.ui.theme.GuardianGridTheme

class MainActivity : ComponentActivity() {

    private val coordinator = EmergencyCoordinatorImpl()
    private val audioStub = AudioModuleStub()
    private val motionStub = MotionModuleStub()
    private val commStub = EmergencyCommunicatorStub()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GuardianGridTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        coordinator = coordinator,
                        audioStub = audioStub,
                        motionStub = motionStub,
                        commStub = commStub
                    )
                }
            }
        }
    }
}
