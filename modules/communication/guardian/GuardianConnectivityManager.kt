package com.guardiangrid.modules.communication.guardian

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Snapshot state exposed reactively to GuardianGrid pairing and alert-receiving UI screens.
 */
data class GuardianConnectivityState(
    val identity: DeviceIdentity,
    val relayServerUrl: String = "",
    val activeInvitationId: String = "",
    val activeInvitationToken: String = "",
    val activeInvitationUri: String = "",
    val activeInvitationExpiresAtMs: Long = 0L,
    val isActiveInvitationRegisteredWithBackend: Boolean = false,
    val pairedDevices: List<PairedGuardianDevice> = emptyList(),
    val receivedAlerts: List<ReceivedGuardianAlert> = emptyList(),
    val lastSyncStatus: String = "Ready for pairing"
) {
    val pairedGuardians: List<PairedGuardianDevice>
        get() = pairedDevices.filter { it.role == PairingRole.GUARDIAN && it.isActive }
    val pairedHosts: List<PairedGuardianDevice>
        get() = pairedDevices.filter { it.role == PairingRole.HOST && it.isActive }
}

/**
 * Manages login-free cryptographic device identity, QR/deep-link pairing invitations,
 * persisted paired devices (Host <-> Guardian), relay server configuration, and received
 * Guardian emergency notifications + audio clips.
 */
class GuardianConnectivityManager(
    private val storageDir: File,
    defaultDeviceName: String = "GuardianGrid Device"
) {
    private val identityFile = File(storageDir, "device_identity.dat")
    private val pairingFile = File(storageDir, "paired_devices.dat")
    private val inboxFile = File(storageDir, "guardian_inbox.dat")
    private val configFile = File(storageDir, "relay_config.dat")
    private val consumedInvitesFile = File(storageDir, "consumed_invites.dat")
    val receivedAudioDir = File(storageDir, "received_audio").apply { mkdirs() }

    private val _state: MutableStateFlow<GuardianConnectivityState>
    val state: StateFlow<GuardianConnectivityState> get() = _state.asStateFlow()

    private val consumedInviteIds = linkedSetOf<String>()

    @Volatile
    private var activeInviteSecret: String = ""

    init {
        if (!storageDir.exists()) storageDir.mkdirs()
        val identity = loadOrCreateIdentity(defaultDeviceName)
        val relayUrl = loadRelayUrl()
        val peers = loadPairedDevices()
        val inbox = loadInbox()
        loadConsumedInvites()
        val expiresAt = System.currentTimeMillis() + INVITATION_TTL_MS
        val inviteId = "inv-${GuardianCryptoCodec.randomHex(8)}"
        val inviteToken = GuardianCryptoCodec.randomHex(16)
        activeInviteSecret = inviteToken
        val inviteUri = GuardianCryptoCodec.signInvitationUriPayload(
            identity = identity,
            oneTimeTokenHex = inviteToken,
            relayEndpointUrl = relayUrl.ifBlank { null },
            expiresAtEpochMs = expiresAt,
            inviteId = inviteId
        )
        _state = MutableStateFlow(
            GuardianConnectivityState(
                identity = identity,
                relayServerUrl = relayUrl,
                activeInvitationId = inviteId,
                activeInvitationToken = inviteToken,
                activeInvitationUri = inviteUri,
                activeInvitationExpiresAtMs = expiresAt,
                isActiveInvitationRegisteredWithBackend = false,
                pairedDevices = peers,
                receivedAlerts = inbox
            )
        )
    }

    fun getIdentity(): DeviceIdentity = _state.value.identity

    fun getRelayServerUrl(): String = _state.value.relayServerUrl

    fun getActiveGuardians(): List<PairedGuardianDevice> = _state.value.pairedGuardians

    fun getPairedHosts(): List<PairedGuardianDevice> = _state.value.pairedHosts

    @Synchronized
    fun setDeviceDisplayName(name: String) {
        val cleaned = name.trim().take(48).ifBlank { return }
        val updatedIdentity = _state.value.identity.copy(deviceName = cleaned)
        persistIdentity(updatedIdentity)
        _state.value = _state.value.copy(identity = updatedIdentity)
        regeneratePairingInvitation()
    }

    @Synchronized
    fun setRelayServerUrl(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isNotBlank() && !trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return Result.failure(IllegalArgumentException("Relay URL must start with https:// or http://"))
        }
        configFile.writeText(trimmed)
        _state.value = _state.value.copy(
            relayServerUrl = trimmed,
            lastSyncStatus = if (trimmed.isBlank()) "Relay Server URL cleared" else "Relay endpoint configured"
        )
        regeneratePairingInvitation()
        return Result.success(trimmed)
    }

    @Synchronized
    fun regeneratePairingInvitation(): String {
        val identity = _state.value.identity
        val relayUrl = _state.value.relayServerUrl.ifBlank { null }
        val expiresAt = System.currentTimeMillis() + INVITATION_TTL_MS
        val inviteId = "inv-${GuardianCryptoCodec.randomHex(8)}"
        val secret = GuardianCryptoCodec.randomHex(16)
        activeInviteSecret = secret
        val uri = GuardianCryptoCodec.signInvitationUriPayload(
            identity = identity,
            oneTimeTokenHex = secret,
            relayEndpointUrl = relayUrl,
            expiresAtEpochMs = expiresAt,
            inviteId = inviteId
        )
        _state.value = _state.value.copy(
            activeInvitationId = inviteId,
            activeInvitationToken = secret,
            activeInvitationUri = uri,
            activeInvitationExpiresAtMs = expiresAt,
            isActiveInvitationRegisteredWithBackend = false
        )
        return uri
    }

    @Synchronized
    fun getActiveInvitationModel(): PairingInvitation {
        val st = _state.value
        return PairingInvitation(
            version = 2,
            inviteId = st.activeInvitationId,
            deviceId = st.identity.deviceId,
            deviceName = st.identity.deviceName,
            sharedSecretHex = st.activeInvitationToken,
            relayEndpointUrl = st.relayServerUrl.ifBlank { null },
            expiresAtEpochMs = st.activeInvitationExpiresAtMs,
            signatureHex = ""
        )
    }

    @Synchronized
    fun markActiveInvitationRegistered(registered: Boolean, statusMessage: String? = null) {
        _state.value = _state.value.copy(
            isActiveInvitationRegisteredWithBackend = registered,
            lastSyncStatus = statusMessage ?: _state.value.lastSyncStatus
        )
    }

    /**
     * Validates a scanned/pasted invitation locally WITHOUT marking pairing successful.
     * Checks cryptographic signature, expiration, self-pairing, and single-use consumption history.
     */
    @Synchronized
    fun validateInvitationForBackend(invitationUri: String, nowEpochMs: Long = System.currentTimeMillis()): Result<PairingInvitation> {
        val parsed = GuardianCryptoCodec.parseAndVerifyInvitation(invitationUri, nowEpochMs)
        if (parsed.isFailure) return parsed
        val invite = parsed.getOrThrow()
        if (invite.deviceId == _state.value.identity.deviceId) {
            return Result.failure(IllegalArgumentException("Cannot pair a device with itself"))
        }
        if (invite.inviteId.isNotBlank() && consumedInviteIds.contains(invite.inviteId)) {
            return Result.failure(IllegalStateException("This pairing invitation has already been consumed and cannot be reused."))
        }
        return Result.success(invite)
    }

    /**
     * Commits an authenticated pairing ONLY AFTER backend confirmation has succeeded,
     * and records the invitation ID as consumed to prevent reuse.
     */
    @Synchronized
    fun commitBackendConfirmedPairing(
        peerDeviceId: String,
        peerDisplayName: String,
        roleOfPeer: PairingRole,
        consumedInviteId: String? = null,
        relayEndpointUrl: String? = null
    ): PairedGuardianDevice {
        if (!consumedInviteId.isNullOrBlank()) {
            markInvitationConsumed(consumedInviteId)
        }
        val now = System.currentTimeMillis()
        val peer = PairedGuardianDevice(
            deviceId = peerDeviceId,
            displayName = peerDisplayName.ifBlank { "GuardianGrid Device" },
            role = roleOfPeer,
            sharedSecretHex = "",
            relayEndpointUrl = relayEndpointUrl ?: _state.value.relayServerUrl.ifBlank { null },
            pairedAtEpochMs = now,
            lastSeenEpochMs = now,
            isActive = true
        )
        val updatedList = (_state.value.pairedDevices.filterNot { it.deviceId == peer.deviceId && it.role == peer.role } + peer)
        persistPairedDevices(updatedList)
        _state.value = _state.value.copy(
            pairedDevices = updatedList,
            lastSyncStatus = "Backend confirmed pairing with ${peer.displayName} (${peer.role.name})"
        )
        return peer
    }

    /**
     * Reconciles server-confirmed Host and Guardian pairings from authenticated `GET /pairings`.
     */
    @Synchronized
    fun reconcileBackendPairings(
        confirmedGuardians: List<Pair<String, String>>,
        confirmedHosts: List<Pair<String, String>>,
        relayUrl: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existingByKey = _state.value.pairedDevices.associateBy { "${it.role.name}:${it.deviceId}" }
        val resolved = mutableListOf<PairedGuardianDevice>()

        for ((id, name) in confirmedGuardians) {
            val prev = existingByKey["${PairingRole.GUARDIAN.name}:$id"]
            resolved.add(
                PairedGuardianDevice(
                    deviceId = id,
                    displayName = name.ifBlank { prev?.displayName ?: "Paired Guardian" },
                    role = PairingRole.GUARDIAN,
                    sharedSecretHex = prev?.sharedSecretHex ?: "",
                    relayEndpointUrl = relayUrl ?: prev?.relayEndpointUrl ?: _state.value.relayServerUrl.ifBlank { null },
                    pairedAtEpochMs = prev?.pairedAtEpochMs ?: now,
                    lastSeenEpochMs = now,
                    isActive = true
                )
            )
        }
        for ((id, name) in confirmedHosts) {
            val prev = existingByKey["${PairingRole.HOST.name}:$id"]
            resolved.add(
                PairedGuardianDevice(
                    deviceId = id,
                    displayName = name.ifBlank { prev?.displayName ?: "Protected Host" },
                    role = PairingRole.HOST,
                    sharedSecretHex = prev?.sharedSecretHex ?: "",
                    relayEndpointUrl = relayUrl ?: prev?.relayEndpointUrl ?: _state.value.relayServerUrl.ifBlank { null },
                    pairedAtEpochMs = prev?.pairedAtEpochMs ?: now,
                    lastSeenEpochMs = now,
                    isActive = true
                )
            )
        }

        // Preserve any non-Supabase custom /v1 relay peers if backend list is superset-merged safely
        val customOnly = _state.value.pairedDevices.filter { it.sharedSecretHex.isNotBlank() && resolved.none { r -> r.deviceId == it.deviceId && r.role == it.role } }
        val merged = resolved + customOnly
        persistPairedDevices(merged)
        _state.value = _state.value.copy(
            pairedDevices = merged,
            lastSyncStatus = "Synced ${resolved.size} backend-confirmed pairing(s)"
        )
    }

    /**
     * Parses, checks single-use consumption & expiration, and links an offline/custom-relay invitation URI.
     * Note: Supabase flows use [validateInvitationForBackend] followed by [commitBackendConfirmedPairing].
     */
    @Synchronized
    fun acceptPairingInvitation(
        invitationUri: String,
        roleOfScannedPeer: PairingRole = PairingRole.HOST
    ): Result<PairedGuardianDevice> {
        val parsed = validateInvitationForBackend(invitationUri)
        if (parsed.isFailure) {
            return Result.failure(parsed.exceptionOrNull()!!)
        }
        val invite = parsed.getOrThrow()
        if (invite.inviteId.isNotBlank()) {
            markInvitationConsumed(invite.inviteId)
        }

        val now = System.currentTimeMillis()
        val peer = PairedGuardianDevice(
            deviceId = invite.deviceId,
            displayName = invite.deviceName,
            role = roleOfScannedPeer,
            sharedSecretHex = invite.sharedSecretHex,
            relayEndpointUrl = invite.relayEndpointUrl ?: _state.value.relayServerUrl.ifBlank { null },
            pairedAtEpochMs = now,
            lastSeenEpochMs = now,
            isActive = true
        )

        val updatedList = (_state.value.pairedDevices.filterNot { it.deviceId == peer.deviceId && it.role == peer.role } + peer)
        persistPairedDevices(updatedList)
        if (!invite.relayEndpointUrl.isNullOrBlank() && _state.value.relayServerUrl.isBlank()) {
            configFile.writeText(invite.relayEndpointUrl)
            _state.value = _state.value.copy(relayServerUrl = invite.relayEndpointUrl)
        }
        _state.value = _state.value.copy(
            pairedDevices = updatedList,
            lastSyncStatus = "Paired with ${peer.displayName} (${peer.role.name})"
        )
        return Result.success(peer)
    }

    /**
     * Completes Host-side acceptance when a Guardian scans our active invitation QR/link and shares their Guardian Device ID,
     * OR registers an authorized Guardian responder using the active invitation secret.
     */
    @Synchronized
    fun registerGuardianFromResponse(
        guardianDeviceId: String,
        guardianDisplayName: String,
        sharedSecretHex: String = activeInviteSecret
    ): Result<PairedGuardianDevice> {
        val id = guardianDeviceId.trim()
        if (id.length < 8) {
            return Result.failure(IllegalArgumentException("Guardian Device ID must be at least 8 characters"))
        }
        if (id == _state.value.identity.deviceId) {
            return Result.failure(IllegalArgumentException("Cannot pair a device with itself"))
        }
        val peer = PairedGuardianDevice(
            deviceId = id,
            displayName = guardianDisplayName.trim().ifBlank { "Guardian Responder" },
            role = PairingRole.GUARDIAN,
            sharedSecretHex = sharedSecretHex,
            relayEndpointUrl = _state.value.relayServerUrl.ifBlank { null },
            pairedAtEpochMs = System.currentTimeMillis(),
            lastSeenEpochMs = System.currentTimeMillis(),
            isActive = true
        )
        val updatedList = (_state.value.pairedDevices.filterNot { it.deviceId == peer.deviceId && it.role == PairingRole.GUARDIAN } + peer)
        persistPairedDevices(updatedList)
        _state.value = _state.value.copy(
            pairedDevices = updatedList,
            lastSyncStatus = "Authorized Guardian ${peer.displayName}"
        )
        return Result.success(peer)
    }

    @Synchronized
    fun removePairedDevice(deviceId: String, role: PairingRole) {
        val updated = _state.value.pairedDevices.filterNot { it.deviceId == deviceId && it.role == role }
        persistPairedDevices(updated)
        _state.value = _state.value.copy(
            pairedDevices = updated,
            lastSyncStatus = "Unpaired device ${deviceId.take(8)}"
        )
    }

    @Synchronized
    fun recordReceivedAlert(alert: ReceivedGuardianAlert) {
        val existing = _state.value.receivedAlerts.firstOrNull { it.incidentId == alert.incidentId }
        val mergedClips = if (existing != null) {
            val byId = existing.audioClips.associateBy { it.clipId }.toMutableMap()
            for (clip in alert.audioClips) byId[clip.clipId] = clip
            byId.values.sortedWith(compareByDescending<ReceivedGuardianAudioClip> { it.isPreTrigger }.thenBy { it.chunkIndex })
        } else {
            alert.audioClips
        }
        val mergedAlert = alert.copy(
            isAcknowledged = (existing?.isAcknowledged == true) || alert.isAcknowledged,
            audioClips = mergedClips
        )
        val updated = (listOf(mergedAlert) + _state.value.receivedAlerts.filterNot { it.incidentId == alert.incidentId }).take(40)
        persistInbox(updated)
        _state.value = _state.value.copy(
            receivedAlerts = updated,
            lastSyncStatus = "Received alert ${alert.incidentId}"
        )
    }

    @Synchronized
    fun attachReceivedAudioClip(
        incidentId: String,
        hostDeviceId: String,
        hostDeviceName: String,
        chunkIndex: Int,
        isPreTrigger: Boolean,
        isFinal: Boolean,
        fileName: String,
        wavBytes: ByteArray
    ): ReceivedGuardianAudioClip {
        val outFile = File(receivedAudioDir, "${incidentId}_${chunkIndex}_${fileName}")
        outFile.writeBytes(wavBytes)
        val durationMs = estimateWavDurationMs(wavBytes.size)
        val clip = ReceivedGuardianAudioClip(
            clipId = "$incidentId-$chunkIndex-${fileName.hashCode()}",
            incidentId = incidentId,
            chunkIndex = chunkIndex,
            isPreTrigger = isPreTrigger,
            isFinal = isFinal,
            fileName = fileName,
            localFilePath = outFile.absolutePath,
            durationMs = durationMs,
            byteSize = wavBytes.size.toLong()
        )
        val current = _state.value.receivedAlerts.firstOrNull { it.incidentId == incidentId }
            ?: ReceivedGuardianAlert(
                alertId = "rx-${UUID.randomUUID()}",
                incidentId = incidentId,
                hostDeviceId = hostDeviceId,
                hostDeviceName = hostDeviceName,
                distressType = "EMERGENCY",
                confidence = 1.0f,
                emergencyMessage = "Incoming Emergency Audio Stream"
            )
        recordReceivedAlert(current.copy(audioClips = current.audioClips + clip))
        return clip
    }

    @Synchronized
    fun markReceivedAlertAcknowledged(incidentId: String) {
        val updated = _state.value.receivedAlerts.map {
            if (it.incidentId == incidentId) it.copy(isAcknowledged = true) else it
        }
        persistInbox(updated)
        _state.value = _state.value.copy(receivedAlerts = updated)
    }

    @Synchronized
    fun clearInbox() {
        receivedAudioDir.listFiles()?.forEach { it.delete() }
        if (inboxFile.exists()) inboxFile.delete()
        _state.value = _state.value.copy(receivedAlerts = emptyList())
    }

    private fun estimateWavDurationMs(fileBytes: Int): Long {
        val pcmBytes = (fileBytes - 44).coerceAtLeast(0)
        return (pcmBytes * 1000L) / (16000L * 2L)
    }

    private fun loadOrCreateIdentity(defaultDeviceName: String): DeviceIdentity {
        if (identityFile.exists()) {
            try {
                val parts = identityFile.readText().split("\t")
                if (parts.size >= 5) {
                    return DeviceIdentity(
                        deviceId = parts[0],
                        deviceName = unescape(parts[1]),
                        publicFingerprint = parts[2],
                        hmacSecretHex = parts[3],
                        createdAtEpochMs = parts[4].toLongOrNull() ?: System.currentTimeMillis()
                    )
                }
            } catch (_: Exception) {
            }
        }
        val secret = GuardianCryptoCodec.randomHex(32)
        val fingerprint = GuardianCryptoCodec.sha256Hex("gg-id:$secret:${System.nanoTime()}")
        val deviceId = "GG-${fingerprint.take(12).uppercase()}"
        val created = DeviceIdentity(
            deviceId = deviceId,
            deviceName = defaultDeviceName.trim().ifBlank { "GuardianGrid Device" },
            publicFingerprint = fingerprint,
            hmacSecretHex = secret
        )
        persistIdentity(created)
        return created
    }

    private fun persistIdentity(identity: DeviceIdentity) {
        val line = listOf(
            identity.deviceId,
            escape(identity.deviceName),
            identity.publicFingerprint,
            identity.hmacSecretHex,
            identity.createdAtEpochMs.toString()
        ).joinToString("\t")
        atomicWrite(identityFile, line)
    }

    private fun loadRelayUrl(): String {
        return try {
            if (configFile.exists()) configFile.readText().trim() else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadPairedDevices(): List<PairedGuardianDevice> {
        if (!pairingFile.exists()) return emptyList()
        return try {
            pairingFile.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val f = line.split("\t")
                if (f.size < 8) return@mapNotNull null
                PairedGuardianDevice(
                    deviceId = f[0],
                    displayName = unescape(f[1]),
                    role = PairingRole.valueOf(f[2]),
                    sharedSecretHex = f[3],
                    relayEndpointUrl = f[4].ifBlank { null },
                    pairedAtEpochMs = f[5].toLongOrNull() ?: 0L,
                    lastSeenEpochMs = f[6].toLongOrNull() ?: 0L,
                    isActive = f[7].toBooleanStrictOrNull() ?: true
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistPairedDevices(devices: List<PairedGuardianDevice>) {
        val text = devices.joinToString("\n") { d ->
            listOf(
                d.deviceId,
                escape(d.displayName),
                d.role.name,
                d.sharedSecretHex,
                d.relayEndpointUrl.orEmpty(),
                d.pairedAtEpochMs.toString(),
                d.lastSeenEpochMs.toString(),
                d.isActive.toString()
            ).joinToString("\t")
        }
        atomicWrite(pairingFile, text)
    }

    private fun loadInbox(): List<ReceivedGuardianAlert> {
        if (!inboxFile.exists()) return emptyList()
        return try {
            val content = inboxFile.readText()
            if (content.isBlank()) return emptyList()
            content.split("\n---ALERT---\n").mapNotNull { block ->
                val f = block.split("\t")
                if (f.size < 12) return@mapNotNull null
                val clips = if (f[11].isBlank()) emptyList() else {
                    f[11].split(";").mapNotNull { cStr ->
                        val cf = cStr.split("|")
                        if (cf.size < 9) null else ReceivedGuardianAudioClip(
                            clipId = cf[0],
                            incidentId = f[1],
                            chunkIndex = cf[1].toIntOrNull() ?: 0,
                            isPreTrigger = cf[2].toBoolean(),
                            isFinal = cf[3].toBoolean(),
                            fileName = cf[4],
                            localFilePath = cf[5].ifBlank { null },
                            remoteAudioUrl = cf[6].ifBlank { null },
                            durationMs = cf[7].toLongOrNull() ?: 0L,
                            byteSize = cf[8].toLongOrNull() ?: 0L
                        )
                    }
                }
                ReceivedGuardianAlert(
                    alertId = f[0],
                    incidentId = f[1],
                    hostDeviceId = f[2],
                    hostDeviceName = unescape(f[3]),
                    distressType = f[4],
                    confidence = f[5].toFloatOrNull() ?: 1.0f,
                    emergencyMessage = unescape(f[6]),
                    latitude = f[7].toDoubleOrNull(),
                    longitude = f[8].toDoubleOrNull(),
                    accuracyMeters = f[9].toFloatOrNull(),
                    receivedAtEpochMs = f[10].toLongOrNull() ?: System.currentTimeMillis(),
                    isAcknowledged = (f.getOrNull(12)?.toBooleanStrictOrNull() ?: false),
                    audioClips = clips
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistInbox(alerts: List<ReceivedGuardianAlert>) {
        val text = alerts.joinToString("\n---ALERT---\n") { a ->
            val clipsStr = a.audioClips.joinToString(";") { c ->
                listOf(
                    c.clipId,
                    c.chunkIndex.toString(),
                    c.isPreTrigger.toString(),
                    c.isFinal.toString(),
                    c.fileName,
                    c.localFilePath.orEmpty(),
                    c.remoteAudioUrl.orEmpty(),
                    c.durationMs.toString(),
                    c.byteSize.toString()
                ).joinToString("|")
            }
            listOf(
                a.alertId,
                a.incidentId,
                a.hostDeviceId,
                escape(a.hostDeviceName),
                a.distressType,
                a.confidence.toString(),
                escape(a.emergencyMessage),
                a.latitude?.toString().orEmpty(),
                a.longitude?.toString().orEmpty(),
                a.accuracyMeters?.toString().orEmpty(),
                a.receivedAtEpochMs.toString(),
                clipsStr,
                a.isAcknowledged.toString()
            ).joinToString("\t")
        }
        atomicWrite(inboxFile, text)
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(storageDir, "${target.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(target)
    }

    private fun loadConsumedInvites() {
        if (!consumedInvitesFile.exists()) return
        runCatching {
            consumedInvitesFile.readLines().mapNotNull { it.trim().ifBlank { null } }
        }.getOrNull()?.let { consumedInviteIds.addAll(it) }
    }

    private fun markInvitationConsumed(inviteId: String) {
        if (consumedInviteIds.add(inviteId)) {
            atomicWrite(consumedInvitesFile, consumedInviteIds.joinToString("\n"))
        }
    }

    private fun escape(raw: String): String = raw.replace("\t", " ").replace("\n", "\\n")
    private fun unescape(raw: String): String = raw.replace("\\n", "\n")

    companion object {
        const val INVITATION_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }
}
