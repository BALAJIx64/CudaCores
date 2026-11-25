package com.guardiangrid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.core.interfaces.EmergencyCommunicator
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.motion.anomaly.MotionAnomalyDetectorImpl
import com.guardiangrid.ui.brand.GuardianGridSplashScreen
import com.guardiangrid.ui.brand.rememberIsReducedMotion
import com.guardiangrid.ui.guardian.GuardianPairingScreen
import kotlinx.coroutines.delay
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary

enum class MainNavDestination {
    DASHBOARD,
    INCIDENTS,
    SETTINGS,
    EMERGENCY_CONTACTS,
    COMMUNICATION_CONFIG,
    DELIVERY_STATUS,
    OFFLINE_QUEUE,
    GUARDIAN_PAIRING
}

/**
 * Top-level navigation coordinator for GuardianGrid.
 *
 * Implements:
 * - Immediate high-priority emergency preemption ([EmergencyAlertScreen], [EmergencyVerificationScreen])
 * - Persistent, responsive bottom navigation between Home, Incidents, and Settings
 * - Reliable back stack handling (returning to Home before exit)
 * - Service lifecycle isolation (navigation never interrupts foreground monitoring)
 */
@Composable
fun GuardianGridMainScreen(
    coordinator: EmergencyCoordinatorImpl,
    locationManager: GuardianLocationManager,
    audioModule: AudioModuleStub,
    motionDetector: MotionAnomalyDetectorImpl,
    communicator: EmergencyCommunicator,
    onRequestPermissions: () -> Unit
) {
    val state by EmergencyStateHolder.state.collectAsState()
    var currentDestination by remember { mutableStateOf(MainNavDestination.DASHBOARD) }
    val isReducedMotion = rememberIsReducedMotion()
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(isReducedMotion) {
        delay(if (isReducedMotion) 350L else 950L)
        showSplash = false
    }

    // Intercept back button if on a secondary tab
    BackHandler(enabled = currentDestination != MainNavDestination.DASHBOARD && state.emergencyLevel != EmergencyLevel.CONFIRMED_EMERGENCY && state.emergencyLevel != EmergencyLevel.VERIFYING) {
        currentDestination = when (currentDestination) {
            MainNavDestination.EMERGENCY_CONTACTS,
            MainNavDestination.COMMUNICATION_CONFIG,
            MainNavDestination.DELIVERY_STATUS,
            MainNavDestination.OFFLINE_QUEUE,
            MainNavDestination.GUARDIAN_PAIRING -> MainNavDestination.SETTINGS
            else -> MainNavDestination.DASHBOARD
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // High-priority emergency state navigation overrides splash and normal view hierarchy
        when {
            state.emergencyLevel == EmergencyLevel.CONFIRMED_EMERGENCY -> {
                EmergencyAlertScreen(
                    coordinator = coordinator,
                    onResolved = {
                        currentDestination = MainNavDestination.DASHBOARD
                    }
                )
            }
            state.emergencyLevel == EmergencyLevel.VERIFYING || state.verificationCountdownRemainingMs > 0L -> {
                EmergencyVerificationScreen(
                    coordinator = coordinator,
                    onCancelled = {
                        currentDestination = MainNavDestination.DASHBOARD
                    },
                    onDispatched = {
                        // Automatically transitions via state change
                    }
                )
            }
            else -> {
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(durationMillis = if (isReducedMotion) 120 else 280),
                    label = "SplashToAppCrossfade"
                ) { isSplashVisible ->
                    if (isSplashVisible) {
                        GuardianGridSplashScreen(
                            onSkip = { showSplash = false }
                        )
                    } else {
                        Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .navigationBarsPadding()
                        ) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            NavigationBar(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentDestination == MainNavDestination.DASHBOARD,
                                    onClick = { currentDestination = MainNavDestination.DASHBOARD },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = "Home",
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "Home",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (currentDestination == MainNavDestination.DASHBOARD) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = BrandMagenta,
                                        selectedTextColor = BrandMagenta,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = BrandMagenta.copy(alpha = 0.14f)
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentDestination == MainNavDestination.INCIDENTS,
                                    onClick = { currentDestination = MainNavDestination.INCIDENTS },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "Incidents",
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "Incidents",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (currentDestination == MainNavDestination.INCIDENTS) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = BrandMagenta,
                                        selectedTextColor = BrandMagenta,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = BrandMagenta.copy(alpha = 0.14f)
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentDestination == MainNavDestination.SETTINGS ||
                                            currentDestination == MainNavDestination.EMERGENCY_CONTACTS ||
                                            currentDestination == MainNavDestination.COMMUNICATION_CONFIG ||
                                            currentDestination == MainNavDestination.DELIVERY_STATUS ||
                                            currentDestination == MainNavDestination.OFFLINE_QUEUE ||
                                            currentDestination == MainNavDestination.GUARDIAN_PAIRING,
                                    onClick = { currentDestination = MainNavDestination.SETTINGS },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "Settings",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (currentDestination == MainNavDestination.SETTINGS) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = BrandMagenta,
                                        selectedTextColor = BrandMagenta,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = BrandMagenta.copy(alpha = 0.14f)
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AnimatedContent(
                            targetState = currentDestination,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "NavTransition"
                        ) { destination ->
                            when (destination) {
                                MainNavDestination.DASHBOARD -> {
                                    DashboardScreen(
                                        coordinator = coordinator,
                                        locationManager = locationManager,
                                        audioModule = audioModule,
                                        motionDetector = motionDetector,
                                        communicator = communicator,
                                        onRequestPermissions = onRequestPermissions,
                                        onNavigateToSettings = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        },
                                        onNavigateToIncidents = {
                                            currentDestination = MainNavDestination.INCIDENTS
                                        },
                                        onNavigateToContacts = {
                                            currentDestination = MainNavDestination.EMERGENCY_CONTACTS
                                        },
                                        onNavigateToCommunication = {
                                            currentDestination = MainNavDestination.COMMUNICATION_CONFIG
                                        },
                                        onNavigateToOfflineQueue = {
                                            currentDestination = MainNavDestination.OFFLINE_QUEUE
                                        },
                                        onNavigateToGuardianPairing = {
                                            currentDestination = MainNavDestination.GUARDIAN_PAIRING
                                        }
                                    )
                                }
                                MainNavDestination.INCIDENTS -> {
                                    IncidentHistoryScreen(
                                        onNavigateToHome = {
                                            currentDestination = MainNavDestination.DASHBOARD
                                        }
                                    )
                                }
                                MainNavDestination.SETTINGS -> {
                                    SettingsScreen(
                                        audioModule = audioModule,
                                        motionDetector = motionDetector,
                                        coordinator = coordinator,
                                        onRequestPermissions = onRequestPermissions,
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.DASHBOARD
                                        },
                                        onNavigateToContacts = {
                                            currentDestination = MainNavDestination.EMERGENCY_CONTACTS
                                        },
                                        onNavigateToCommunication = {
                                            currentDestination = MainNavDestination.COMMUNICATION_CONFIG
                                        },
                                        onNavigateToDeliveryStatus = {
                                            currentDestination = MainNavDestination.DELIVERY_STATUS
                                        },
                                        onNavigateToOfflineQueue = {
                                            currentDestination = MainNavDestination.OFFLINE_QUEUE
                                        },
                                        onNavigateToGuardianPairing = {
                                            currentDestination = MainNavDestination.GUARDIAN_PAIRING
                                        }
                                    )
                                }
                                MainNavDestination.GUARDIAN_PAIRING -> {
                                    GuardianPairingScreen(
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        }
                                    )
                                }
                                MainNavDestination.EMERGENCY_CONTACTS -> {
                                    EmergencyContactsScreen(
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        }
                                    )
                                }
                                MainNavDestination.COMMUNICATION_CONFIG -> {
                                    CommunicationConfigScreen(
                                        onRequestPermissions = onRequestPermissions,
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        }
                                    )
                                }
                                MainNavDestination.DELIVERY_STATUS -> {
                                    DeliveryStatusScreen(
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        }
                                    )
                                }
                                MainNavDestination.OFFLINE_QUEUE -> {
                                    OfflineQueueScreen(
                                        onNavigateBack = {
                                            currentDestination = MainNavDestination.SETTINGS
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                }
                }
            }
        }
    }
}
