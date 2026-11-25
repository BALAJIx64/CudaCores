package com.guardiangrid

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.modules.communication.guardian.GuardianConnectivityManager
import com.guardiangrid.modules.communication.guardian.GuardianCryptoCodec
import com.guardiangrid.modules.communication.guardian.HttpGuardianRelayClient
import com.guardiangrid.modules.communication.guardian.PairingRole
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import com.guardiangrid.modules.communication.network.HttpResponse
import com.guardiangrid.modules.communication.network.MockHttpTransport
import com.guardiangrid.modules.communication.supabase.SupabaseBackendConfig
import com.guardiangrid.modules.communication.supabase.SupabaseConfigManager
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardianQrPairingFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testConfig = SupabaseBackendConfig(
        projectUrl = "https://example-project.supabase.co",
        anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test_public_anon_key_value"
    )

    @Before
    fun setUp() {
        SupabaseConfigManager.clear()
    }

    @After
    fun tearDown() {
        SupabaseConfigManager.clear()
    }

    @Test
    fun missingSupabaseConfigurationRejectsInviteRegistrationAcceptanceAndSync() {
        val mgr = GuardianConnectivityManager(tempFolder.newFolder("unconfigured_host"), "SenderPhone")
        val transport = MockHttpTransport()
        val client = SupabaseEmergencyBackendClient(mgr, transport) { null }

        assertFalse(client.isConfigured())

        val inviteRes = client.createPairingInvitation(mgr.getActiveInvitationModel())
        assertTrue(inviteRes.isFailure)
        assertTrue(inviteRes.exceptionOrNull()?.message?.contains("not configured") == true)
        assertFalse(mgr.state.value.isActiveInvitationRegisteredWithBackend)

        val acceptRes = client.acceptPairingInvitationRemote(mgr.getActiveInvitationModel())
        assertTrue(acceptRes.isFailure)
        assertTrue(acceptRes.exceptionOrNull()?.message?.contains("not configured") == true)

        val syncRes = client.fetchConfirmedPairings()
        assertTrue(syncRes.isFailure)
        assertTrue(syncRes.exceptionOrNull()?.message?.contains("not configured") == true)

        assertTrue("No HTTP requests may be sent when unconfigured", transport.recordedRequests.isEmpty())
    }

    @Test
    fun backend404And401And500SurfaceSafeHttpErrorsAndBlockActiveQr() {
        val mgr = GuardianConnectivityManager(tempFolder.newFolder("error_host"), "SenderPhone")
        val transport = MockHttpTransport()
        val client = SupabaseEmergencyBackendClient(mgr, transport) { testConfig }

        // 1. 404 Edge Function missing
        transport.enqueueResponse(HttpResponse(404, """{"code":"NOT_FOUND","message":"Requested function was not found"}"""))
        val res404 = client.createPairingInvitation(mgr.getActiveInvitationModel())
        assertTrue(res404.isFailure)
        val msg404 = res404.exceptionOrNull()?.message.orEmpty()
        assertTrue("Expected HTTP 404 in message, got: $msg404", msg404.contains("HTTP 404"))
        assertFalse(mgr.state.value.isActiveInvitationRegisteredWithBackend)
        assertTrue(mgr.state.value.lastSyncStatus.contains("HTTP 404"))

        // 2. 401 Invalid auth / timestamp skew on device registration
        transport.enqueueResponse(HttpResponse(401, """{"ok":false,"error":"Expired or invalid request timestamp"}"""))
        val res401 = client.createPairingInvitation(mgr.getActiveInvitationModel())
        assertTrue(res401.isFailure)
        assertTrue(res401.exceptionOrNull()?.message?.contains("HTTP 401") == true)

        // 3. 500 Server secrets misconfigured after registration succeeds
        transport.enqueueResponse(HttpResponse(200, """{"ok":true,"registered":true}"""))
        transport.enqueueResponse(HttpResponse(500, """{"ok":false,"error":"Server secrets misconfigured"}"""))
        val res500 = client.createPairingInvitation(mgr.getActiveInvitationModel())
        assertTrue(res500.isFailure)
        val msg500 = res500.exceptionOrNull()?.message.orEmpty()
        assertTrue("Expected HTTP 500 in message, got: $msg500", msg500.contains("HTTP 500"))
        assertFalse(mgr.state.value.isActiveInvitationRegisteredWithBackend)
    }

    @Test
    fun qrExpiryTamperedSignatureAndSelfPairingAreRejectedLocally() {
        val hostMgr = GuardianConnectivityManager(tempFolder.newFolder("val_host"), "Host")
        val receiverMgr = GuardianConnectivityManager(tempFolder.newFolder("val_rx"), "Receiver")
        val validUri = hostMgr.state.value.activeInvitationUri

        // Self-pairing blocked
        val selfRes = hostMgr.validateInvitationForBackend(validUri)
        assertTrue(selfRes.isFailure)
        assertTrue(selfRes.exceptionOrNull()?.message?.contains("itself") == true)

        // Tampered signature blocked
        val tamperedUri = validUri.replace("sig=", "sig=0000")
        val sigRes = receiverMgr.validateInvitationForBackend(tamperedUri)
        assertTrue(sigRes.isFailure)
        assertTrue(sigRes.exceptionOrNull() is SecurityException)

        // Expired QR blocked
        val futureClockMs = hostMgr.state.value.activeInvitationExpiresAtMs + 60_000L
        val expRes = receiverMgr.validateInvitationForBackend(validUri, nowEpochMs = futureClockMs)
        assertTrue(expRes.isFailure)
        assertTrue(expRes.exceptionOrNull()?.message?.contains("expired", ignoreCase = true) == true)
    }

    @Test
    fun fullTwoDeviceSupabasePairingAcceptanceAndSenderRefreshSucceed() {
        val hostMgr = GuardianConnectivityManager(tempFolder.newFolder("pair_host"), "HostPhone")
        val receiverMgr = GuardianConnectivityManager(tempFolder.newFolder("pair_rx"), "GuardianPhone")

        val hostTransport = MockHttpTransport()
        val rxTransport = MockHttpTransport()
        val hostClient = SupabaseEmergencyBackendClient(hostMgr, hostTransport) { testConfig }
        val rxClient = SupabaseEmergencyBackendClient(receiverMgr, rxTransport) { testConfig }

        // Step 1: Host registers device & single-use invitation
        hostTransport.enqueueResponse(HttpResponse(200, """{"ok":true,"registered":true}"""))
        hostTransport.enqueueResponse(HttpResponse(200, """{"ok":true,"registered":true}"""))
        val inviteCreate = hostClient.createPairingInvitation(hostMgr.getActiveInvitationModel())
        assertTrue(inviteCreate.isSuccess)
        assertTrue(hostMgr.state.value.isActiveInvitationRegisteredWithBackend)

        // Step 2: Receiver scans & validates QR, then accepts on backend
        val scannedUri = hostMgr.state.value.activeInvitationUri
        val validatedInvite = receiverMgr.validateInvitationForBackend(scannedUri).getOrThrow()

        rxTransport.enqueueResponse(HttpResponse(200, """{"ok":true,"registered":true}"""))
        rxTransport.enqueueResponse(
            HttpResponse(
                200,
                """{"ok":true,"pairingConfirmed":true,"inviteId":"${validatedInvite.inviteId}","hostDeviceId":"${hostMgr.getIdentity().deviceId}","hostDeviceName":"HostPhone","guardianDeviceId":"${receiverMgr.getIdentity().deviceId}"}"""
            )
        )
        val accepted = rxClient.acceptPairingInvitationRemote(validatedInvite)
        assertTrue(accepted.isSuccess)
        assertEquals(1, receiverMgr.getPairedHosts().size)
        assertEquals(hostMgr.getIdentity().deviceId, receiverMgr.getPairedHosts().first().deviceId)

        // Single-use consumption prevents re-scanning same invite
        val replayAttempt = receiverMgr.validateInvitationForBackend(scannedUri)
        assertTrue(replayAttempt.isFailure)

        // Step 3: Sender refreshes pairings and discovers the new Guardian
        hostTransport.enqueueResponse(
            HttpResponse(
                200,
                """{"ok":true,"guardians":[{"deviceId":"${receiverMgr.getIdentity().deviceId}","deviceName":"GuardianPhone"}],"hosts":[]}"""
            )
        )
        val synced = hostClient.fetchConfirmedPairings()
        assertTrue(synced.isSuccess)
        assertEquals(1, hostMgr.getActiveGuardians().size)
        assertEquals(receiverMgr.getIdentity().deviceId, hostMgr.getActiveGuardians().first().deviceId)

        // Step 4: Post-pairing alert & audio relay must use Supabase /alert and /audio-chunk (never legacy /v1/alerts or /v1/audio)
        hostTransport.enqueueResponse(HttpResponse(200, """{"ok":true,"incidentId":"INC-PAIR-1","messageId":"msg-1"}"""))
        hostTransport.enqueueResponse(HttpResponse(200, """{"ok":true,"incidentId":"INC-PAIR-1","chunkSequence":0,"storagePath":"p/c0.wav","messageId":"aud-1"}"""))

        val relayClient = HttpGuardianRelayClient(hostMgr, hostTransport, hostClient)
        val payload = AlertPayload(
            incidentId = "INC-PAIR-1",
            distressType = DistressType.MANUAL_SOS,
            confidence = 1.0f,
            location = LocationData(12.97, 77.59, 5f),
            emergencyMessage = "SOS Test"
        )
        val alertRes = relayClient.sendAlertEnvelope(payload)
        assertEquals(EmergencyDeliveryState.DELIVERED, alertRes.state)

        val audioRes = relayClient.sendAudioChunk(
            incidentId = "INC-PAIR-1",
            chunkIndex = 0,
            isPreTrigger = true,
            isFinal = false,
            fileName = "pre10s.wav",
            wavBytes = ByteArray(128) { 0x1A }
        )
        assertEquals(EmergencyDeliveryState.DELIVERED, audioRes.state)

        assertTrue(hostTransport.recordedRequests.any { it.url.endsWith("/functions/v1/emergency-relay/alert") })
        assertTrue(hostTransport.recordedRequests.any { it.url.endsWith("/functions/v1/emergency-relay/audio-chunk") })
        assertFalse(hostTransport.recordedRequests.any { it.url.contains("/v1/alerts") || it.url.contains("/v1/audio") })
    }

    @Test
    fun legacyRelayRefusesEmptySharedSecretWhenSupabaseIsNotConfigured() {
        val hostMgr = GuardianConnectivityManager(tempFolder.newFolder("legacy_host"), "Host")
        hostMgr.commitBackendConfirmedPairing(
            peerDeviceId = "GG-GUARDIAN1234",
            peerDisplayName = "Guardian",
            roleOfPeer = PairingRole.GUARDIAN,
            relayEndpointUrl = "https://example-project.supabase.co/functions/v1/emergency-relay"
        )
        val transport = MockHttpTransport()
        val relayClient = HttpGuardianRelayClient(hostMgr, transport, supabaseBackendClient = null)

        val res = relayClient.sendAlertEnvelope(
            AlertPayload(
                incidentId = "INC-LEGACY-BLOCK",
                distressType = DistressType.MANUAL_SOS,
                confidence = 1.0f,
                emergencyMessage = "Test"
            )
        )
        assertEquals(EmergencyDeliveryState.FAILED, res.state)
        assertTrue(res.errorMessage?.contains("shared secret", ignoreCase = true) == true)
        assertTrue(transport.recordedRequests.isEmpty())
    }
}
