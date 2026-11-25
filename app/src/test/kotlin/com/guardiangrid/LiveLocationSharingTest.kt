package com.guardiangrid

import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.location.GuardianLocationManager
import com.guardiangrid.location.LiveLocationShareManager
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.sms.MockSmsSender
import com.guardiangrid.modules.communication.supabase.SupabaseBackendConfig
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiveLocationSharingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockTransport: MockHttpTransport
    private lateinit var mockSmsSender: MockSmsSender
    private lateinit var locationManager: GuardianLocationManager
    private lateinit var backendClient: SupabaseEmergencyBackendClient

    private val contactAlice = EmergencyContact(
        id = "c-1",
        name = "Alice",
        phoneNumber = "+15551234567",
        relationship = "Sister",
        isPrimary = true
    )

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
        mockTransport = MockHttpTransport()
        mockSmsSender = MockSmsSender()
        locationManager = GuardianLocationManager(context = null)
        // Seed a current location in state holder + location manager
        val loc = LocationData(latitude = 12.9716, longitude = 77.5946, accuracyMeters = 8.0f, provider = "gps")
        EmergencyStateHolder.updateLocation(loc)

        val connectivity = GuardianConnectivityManager(File(tempFolder.root, "conn"))
        val cfg = SupabaseBackendConfig(
            projectUrl = "https://yhzvpuycztuwdqttpoid.supabase.co",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test_anon_key_for_unit_tests"
        )
        backendClient = SupabaseEmergencyBackendClient(
            connectivityManager = connectivity,
            transport = mockTransport,
            configProvider = { cfg }
        )
    }

    @Test
    fun startSharingRequiresLiveBackendAndSendsLiveUrlNotStaticCoords() = runBlocking {
        // Test failure when backend returns error (never falls back to static Google Maps coordinate link)
        mockTransport.setHandler { req ->
            when {
                req.url.endsWith("/register-device") -> HttpResponse(200, """{"ok":true}""")
                req.url.endsWith("/live-location/start") -> HttpResponse(503, """{"ok":false,"error":"Unavailable"}""")
                else -> HttpResponse(404, "{}")
            }
        }

        // Inject location via reflection-free helper or test subclass
        val testLocManager = GuardianLocationManager(null)
        val field = GuardianLocationManager::class.java.getDeclaredField("_lastLocation")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(testLocManager) as kotlinx.coroutines.flow.MutableStateFlow<LocationData?>).value =
            LocationData(12.9716, 77.5946, 8.0f, provider = "gps")

        val manager = LiveLocationShareManager(
            locationManager = testLocManager,
            backendClient = backendClient,
            smsSender = mockSmsSender,
            ioDispatcher = Dispatchers.Unconfined,
            updateIntervalMs = 5000L
        )

        val failedRes = manager.startSharing(listOf(contactAlice), 60)
        assertTrue("Must fail when live backend session cannot be created", failedRes.isFailure)
        assertEquals("Must not send static map SMS on backend failure", 0, mockSmsSender.sentMessages.size)

        // Now backend succeeds and returns a private live viewer URL
        val expectedUrl = "https://yhzvpuycztuwdqttpoid.supabase.co/functions/v1/emergency-relay/live-location/view?token=99887766554433221100aabbccddeeff"
        mockTransport.setHandler { req ->
            when {
                req.url.endsWith("/register-device") -> HttpResponse(200, """{"ok":true}""")
                req.url.endsWith("/live-location/start") -> HttpResponse(
                    200,
                    """{"ok":true,"sessionId":"s1","shareUrl":"$expectedUrl","expiresAtEpochMs":${System.currentTimeMillis() + 3600_000L}}"""
                )
                req.url.endsWith("/live-location/stop") -> HttpResponse(200, """{"ok":true,"status":"STOPPED"}""")
                else -> HttpResponse(200, """{"ok":true}""")
            }
        }

        val successRes = manager.startSharing(listOf(contactAlice), 60)
        assertTrue("Live sharing must succeed when backend is available", successRes.isSuccess)
        assertEquals(1, mockSmsSender.sentMessages.size)
        val smsBody = mockSmsSender.sentMessages.first().text
        assertTrue("SMS must contain live viewer URL", smsBody.contains(expectedUrl))
        assertFalse("SMS must NOT contain static Google Maps coordinate link", smsBody.contains("maps.google.com"))
        assertTrue("Sharing state must be active", manager.state.value.isSharing)
        assertNull("SOS active incident must remain untouched", EmergencyStateHolder.state.value.activeIncident)

        // Stop sharing early
        manager.stopSharing()
        assertFalse("Sharing state must stop immediately", manager.state.value.isSharing)
        assertNotNull(mockTransport.recordedRequests.firstOrNull { it.url.endsWith("/live-location/stop") })
    }

    @Test
    fun liveLocationSharingDoesNotTriggerOrAlterSosFlow() = runBlocking {
        val coordinator = EmergencyCoordinatorImpl(
            locationProvider = { LocationData(12.9716, 77.5946, 5.0f) }
        )
        assertNull(EmergencyStateHolder.state.value.activeIncident)

        // Triggering manual SOS still works independently
        coordinator.triggerManualSos("UnitTest_SOS", LocationData(12.9716, 77.5946, 5.0f))
        assertNotNull(EmergencyStateHolder.state.value.activeIncident)
        coordinator.cancelEmergency("Cleanup")
    }
}
