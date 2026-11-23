package com.guardiangrid.modules.communication.queue

import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.models.QueuedEmergencyEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * File-based persistence repository for offline emergency event queueing.
 * Atomically persists events to disk in internal app storage to survive
 * process termination, battery exhaustion, or system reboot.
 */
class FileEmergencyQueueRepository(
    private val storageDir: File
) : EmergencyQueueRepository {

    private val cache = ConcurrentHashMap<String, QueuedEmergencyEvent>()
    private val queueFile: File get() = File(storageDir, "guardiangrid_emergency_queue.dat")

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        loadFromDisk()
    }

    @Synchronized
    override fun save(event: QueuedEmergencyEvent) {
        cache[event.queueId] = event
        flushToDisk()
    }

    override fun getAll(): List<QueuedEmergencyEvent> = cache.values.toList()

    override fun getById(queueId: String): QueuedEmergencyEvent? = cache[queueId]

    @Synchronized
    override fun delete(queueId: String) {
        cache.remove(queueId)
        flushToDisk()
    }

    @Synchronized
    override fun clear() {
        cache.clear()
        if (queueFile.exists()) {
            queueFile.delete()
        }
    }

    @Synchronized
    private fun flushToDisk() {
        try {
            val lines = cache.values.map { serializeEvent(it) }
            val tempFile = File(storageDir, "guardiangrid_emergency_queue.tmp")
            tempFile.writeText(lines.joinToString("\n---EVENT---\n"))
            if (tempFile.exists()) {
                tempFile.renameTo(queueFile)
            }
        } catch (_: Exception) {
            // Log/ignore disk write failure gracefully in constrained environments
        }
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!queueFile.exists()) return
        try {
            val content = queueFile.readText()
            if (content.isBlank()) return
            val blocks = content.split("\n---EVENT---\n")
            for (block in blocks) {
                if (block.isNotBlank()) {
                    val event = deserializeEvent(block)
                    if (event != null) {
                        cache[event.queueId] = event
                    }
                }
            }
        } catch (_: Exception) {
            // Graceful fallback to empty cache if corrupted
        }
    }

    private fun serializeEvent(event: QueuedEmergencyEvent): String {
        val p = event.payload
        val locStr = if (p.location != null) {
            "${p.location.latitude}|${p.location.longitude}|${p.location.accuracyMeters}|${p.location.altitudeMeters}|${p.location.speedMps}|${p.location.timestampEpochMs}|${p.location.provider}"
        } else {
            "NONE"
        }

        val contactsStr = p.recipientContacts.joinToString(";") { c ->
            "${c.id}|${c.name}|${c.phoneNumber}|${c.telegramChatId ?: "NONE"}|${c.isPrimary}"
        }

        return listOf(
            event.queueId,
            event.targetChannel.name,
            event.status.name,
            event.retryCount.toString(),
            event.nextRetryEpochMs.toString(),
            event.createdAtEpochMs.toString(),
            p.incidentId,
            p.timestampEpochMs.toString(),
            p.distressType.name,
            p.confidence.toString(),
            escapeDelimiter(p.emergencyMessage),
            p.audioSegmentPath ?: "NONE",
            locStr,
            contactsStr
        ).joinToString("\t")
    }

    private fun deserializeEvent(serialized: String): QueuedEmergencyEvent? {
        return try {
            val fields = serialized.split("\t")
            if (fields.size < 14) return null

            val queueId = fields[0]
            val channel = DeliveryChannel.valueOf(fields[1])
            val status = DeliveryStatus.valueOf(fields[2])
            val retryCount = fields[3].toInt()
            val nextRetry = fields[4].toLong()
            val createdAt = fields[5].toLong()

            val incidentId = fields[6]
            val timestamp = fields[7].toLong()
            val distressType = DistressType.valueOf(fields[8])
            val confidence = fields[9].toFloat()
            val emergencyMessage = unescapeDelimiter(fields[10])
            val audioPath = if (fields[11] == "NONE") null else fields[11]

            val location = if (fields[12] == "NONE") {
                null
            } else {
                val locParts = fields[12].split("|")
                LocationData(
                    latitude = locParts[0].toDouble(),
                    longitude = locParts[1].toDouble(),
                    accuracyMeters = locParts[2].toFloat(),
                    altitudeMeters = locParts[3].toDouble(),
                    speedMps = locParts[4].toFloat(),
                    timestampEpochMs = locParts[5].toLong(),
                    provider = locParts[6]
                )
            }

            val contacts = if (fields[13].isBlank()) {
                emptyList()
            } else {
                fields[13].split(";").filter { it.isNotBlank() }.map { cStr ->
                    val cParts = cStr.split("|")
                    EmergencyContact(
                        id = cParts[0],
                        name = cParts[1],
                        phoneNumber = cParts[2],
                        telegramChatId = if (cParts[3] == "NONE") null else cParts[3],
                        isPrimary = cParts[4].toBoolean()
                    )
                }
            }

            val payload = AlertPayload(
                incidentId = incidentId,
                timestampEpochMs = timestamp,
                distressType = distressType,
                confidence = confidence,
                location = location,
                emergencyMessage = emergencyMessage,
                audioSegmentPath = audioPath,
                recipientContacts = contacts
            )

            QueuedEmergencyEvent(
                queueId = queueId,
                payload = payload,
                targetChannel = channel,
                status = status,
                retryCount = retryCount,
                nextRetryEpochMs = nextRetry,
                createdAtEpochMs = createdAt
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeDelimiter(text: String): String =
        text.replace("\t", "\\t").replace("\n", "\\n")

    private fun unescapeDelimiter(text: String): String =
        text.replace("\\t", "\t").replace("\\n", "\n")
}
