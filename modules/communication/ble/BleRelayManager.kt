package com.guardiangrid.modules.communication.ble

import com.guardiangrid.core.models.BleEmergencyAlertPacket
import com.guardiangrid.core.models.BleForwardStatus
import com.guardiangrid.core.models.BleRelayAcknowledgement
import com.guardiangrid.core.models.BleRelayQueueItem
import com.guardiangrid.modules.communication.supabase.SupabaseEmergencyBackendClient
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Repository interface for persisting and managing received BLE emergency alerts on the Relay Phone.
 */
interface BleRelayQueueRepository {
    fun enqueue(packet: BleEmergencyAlertPacket): BleRelayQueueItem
    fun getPending(): List<BleRelayQueueItem>
    fun markForwarded(queueId: String, status: BleForwardStatus = BleForwardStatus.ACKNOWLEDGED)
    fun markFailed(queueId: String, error: String)
    fun isAlertKnown(alertId: String): Boolean
    fun getAll(): List<BleRelayQueueItem>
    fun clear()
}

/**
 * File-backed persistent implementation of [BleRelayQueueRepository] for resilient offline relaying.
 */
class FileBleRelayQueueRepository(private val storageDir: File) : BleRelayQueueRepository {
    private val memoryCache = Collections.synchronizedList(mutableListOf<BleRelayQueueItem>())

    init {
        if (!storageDir.exists()) storageDir.mkdirs()
        loadAll()
    }

    @Synchronized
    private fun loadAll() {
        memoryCache.clear()
        storageDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            runCatching {
                val lines = file.readLines()
                if (lines.size >= 4) {
                    val queueId = lines[0].trim()
                    val status = BleForwardStatus.valueOf(lines[1].trim())
                    val retryCount = lines[2].trim().toIntOrNull() ?: 0
                    val packetJson = lines.drop(3).joinToString("\n")
                    val packet = BleEmergencyAlertPacket.fromJson(packetJson)
                    memoryCache.add(
                        BleRelayQueueItem(
                            queueId = queueId,
                            packet = packet,
                            forwardStatus = status,
                            retryCount = retryCount
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    private fun persistItem(item: BleRelayQueueItem) {
        val file = File(storageDir, "${item.queueId}.json")
        val content = listOf(
            item.queueId,
            item.forwardStatus.name,
            item.retryCount.toString(),
            item.packet.toJson()
        ).joinToString("\n")
        file.writeText(content)
    }

    @Synchronized
    override fun enqueue(packet: BleEmergencyAlertPacket): BleRelayQueueItem {
        // Idempotency: Return existing item if already queued
        val existing = memoryCache.firstOrNull { it.packet.alertId == packet.alertId }
        if (existing != null) return existing

        val item = BleRelayQueueItem(
            queueId = "relay-q-${UUID.randomUUID()}",
            packet = packet,
            forwardStatus = BleForwardStatus.PENDING,
            retryCount = 0
        )
        memoryCache.add(item)
        persistItem(item)
        return item
    }

    @Synchronized
    override fun getPending(): List<BleRelayQueueItem> {
        return memoryCache.filter {
            it.forwardStatus == BleForwardStatus.PENDING || it.forwardStatus == BleForwardStatus.FAILED_RETRYING
        }
    }

    @Synchronized
    override fun markForwarded(queueId: String, status: BleForwardStatus) {
        val idx = memoryCache.indexOfFirst { it.queueId == queueId }
        if (idx >= 0) {
            val updated = memoryCache[idx].copy(forwardStatus = status, lastError = null)
            memoryCache[idx] = updated
            persistItem(updated)
        }
    }

    @Synchronized
    override fun markFailed(queueId: String, error: String) {
        val idx = memoryCache.indexOfFirst { it.queueId == queueId }
        if (idx >= 0) {
            val current = memoryCache[idx]
            val updated = current.copy(
                forwardStatus = BleForwardStatus.FAILED_RETRYING,
                retryCount = current.retryCount + 1,
                lastError = error
            )
            memoryCache[idx] = updated
            persistItem(updated)
        }
    }

    @Synchronized
    override fun isAlertKnown(alertId: String): Boolean {
        return memoryCache.any { it.packet.alertId == alertId }
    }

    @Synchronized
    override fun getAll(): List<BleRelayQueueItem> = memoryCache.toList()

    @Synchronized
    override fun clear() {
        memoryCache.clear()
        storageDir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
    }
}

/**
 * Relay Phone Subsystem Coordinator.
 *
 * Responsibilities:
 * 1. Discovers and receives packets from nearby Sender Phones via BLE GATT.
 * 2. Validates packet structure, format, and size (<= 4096 bytes).
 * 3. Retains original signed payload without modification (never alters accountId, alertId, or signature).
 * 4. Forwards exact original packet to backend when online; stores in persistent queue when offline.
 * 5. Strictly prevents unauthorized actions:
 *    - Never looks up contacts
 *    - Never determines primary contact
 *    - Never sends SMS directly
 *    - Never generates replacement signatures
 */
class BleRelayManager(
    val relayDeviceId: String,
    val queueRepository: BleRelayQueueRepository,
    private val backendForwarder: (relayDeviceId: String, packet: BleEmergencyAlertPacket) -> Result<BleRelayAcknowledgement>
) {
    /**
     * Ingests a received BLE emergency-alert packet from the GATT transport.
     * Validates packet structure and preserves the signed payload completely unchanged.
     */
    fun receiveAlertPacket(packet: BleEmergencyAlertPacket): Result<BleRelayQueueItem> {
        val validation = packet.validateBasicStructure()
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Invalid packet structure"))
        }

        // Avoid unnecessary duplicate transmission if already acknowledged
        val existing = queueRepository.getAll().firstOrNull { it.packet.alertId == packet.alertId }
        if (existing != null && existing.forwardStatus == BleForwardStatus.ACKNOWLEDGED) {
            return Result.success(existing)
        }

        val queueItem = queueRepository.enqueue(packet)
        return Result.success(queueItem)
    }

    /**
     * Attempts to forward a queued packet to the Supabase backend.
     */
    fun forwardQueuedItem(item: BleRelayQueueItem): Result<BleRelayAcknowledgement> {
        val result = backendForwarder(relayDeviceId, item.packet)
        if (result.isSuccess) {
            val ack = result.getOrNull() ?: return Result.failure(IllegalStateException("Empty relay acknowledgement"))
            queueRepository.markForwarded(item.queueId, BleForwardStatus.ACKNOWLEDGED)
            return Result.success(ack)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Forwarding failed"
            queueRepository.markFailed(item.queueId, err)
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException(err))
        }
    }

    /**
     * Retries forwarding all pending items in the offline queue.
     */
    fun retryPendingQueue(): List<Result<BleRelayAcknowledgement>> {
        val pending = queueRepository.getPending()
        return pending.map { forwardQueuedItem(it) }
    }
}
