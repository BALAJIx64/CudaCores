package com.guardiangrid.core.state

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyIncident
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persisted representation of an incident record for historical review and audit trails.
 */
data class DurableIncident(
    val incidentId: String,
    val startTimeEpochMs: Long,
    val lastUpdatedEpochMs: Long,
    val resolvedEpochMs: Long? = null,
    val level: EmergencyLevel,
    val triggerType: DistressType,
    val confidence: Float,
    val location: LocationData? = null,
    val isSilent: Boolean = false,
    val notes: String = "",
    val verificationOutcome: String = "PENDING_VERIFICATION",
    val audioEvidencePath: String? = null,
    val deliveryStatus: String = "PENDING",
    val deliveryAttempts: Int = 0,
    val isResolved: Boolean = false
) {
    /**
     * Invariant: Never report audio evidence as available unless the referenced file physically exists on disk.
     */
    val hasValidEvidenceFile: Boolean
        get() = !audioEvidencePath.isNullOrBlank() && File(audioEvidencePath).exists() && File(audioEvidencePath).length() > 0
}

/**
 * Interface for durable incident management and deduplication.
 */
interface IncidentRepository {
    fun saveIncident(incident: DurableIncident): Boolean
    fun getIncident(incidentId: String): DurableIncident?
    fun getAllIncidents(): List<DurableIncident>
    fun deleteIncident(incidentId: String): Boolean
    fun clearAllIncidents(): Boolean
    fun findActiveOrRecentIncident(windowMs: Long = 5000L): DurableIncident?
    fun createOrDeduplicateIncident(
        signal: DistressSignal,
        location: LocationData?,
        isSilent: Boolean,
        notes: String
    ): DurableIncident
}

/**
 * File-based incident repository maintaining persistent emergency logs.
 */
class FileIncidentRepository(
    private val storageDir: File
) : IncidentRepository {

    private val incidentsFile: File get() = File(storageDir, "guardian_incidents.dat")
    private val memoryCache = ConcurrentHashMap<String, DurableIncident>()

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        loadFromDisk()
    }

    @Synchronized
    override fun saveIncident(incident: DurableIncident): Boolean {
        // Enforce physical evidence check: strip path if file does not exist
        val sanitized = if (!incident.audioEvidencePath.isNullOrBlank() && !File(incident.audioEvidencePath).exists()) {
            incident.copy(audioEvidencePath = null)
        } else {
            incident
        }

        memoryCache[sanitized.incidentId] = sanitized
        return flushToDisk()
    }

    override fun getIncident(incidentId: String): DurableIncident? {
        val incident = memoryCache[incidentId] ?: return null
        return if (!incident.audioEvidencePath.isNullOrBlank() && !File(incident.audioEvidencePath).exists()) {
            incident.copy(audioEvidencePath = null)
        } else {
            incident
        }
    }

    override fun getAllIncidents(): List<DurableIncident> {
        return memoryCache.values
            .map { inc ->
                if (!inc.audioEvidencePath.isNullOrBlank() && !File(inc.audioEvidencePath).exists()) {
                    inc.copy(audioEvidencePath = null)
                } else {
                    inc
                }
            }
            .sortedByDescending { it.startTimeEpochMs }
    }

    @Synchronized
    override fun deleteIncident(incidentId: String): Boolean {
        val removed = memoryCache.remove(incidentId) != null
        if (removed) {
            flushToDisk()
        }
        return removed
    }

    @Synchronized
    override fun clearAllIncidents(): Boolean {
        memoryCache.clear()
        if (incidentsFile.exists()) {
            incidentsFile.delete()
        }
        return true
    }

    override fun findActiveOrRecentIncident(windowMs: Long): DurableIncident? {
        val now = System.currentTimeMillis()
        return memoryCache.values
            .filter { !it.isResolved && it.level != EmergencyLevel.CANCELLED && it.level != EmergencyLevel.RESOLVED }
            .filter { (now - it.lastUpdatedEpochMs) <= windowMs }
            .maxByOrNull { it.lastUpdatedEpochMs }
    }

    @Synchronized
    override fun createOrDeduplicateIncident(
        signal: DistressSignal,
        location: LocationData?,
        isSilent: Boolean,
        notes: String
    ): DurableIncident {
        val existing = findActiveOrRecentIncident(5000L)
        val now = System.currentTimeMillis()

        if (existing != null) {
            // Deduplicate: merge higher confidence and update timestamp
            val updated = existing.copy(
                lastUpdatedEpochMs = now,
                confidence = maxOf(existing.confidence, signal.confidence),
                location = location ?: existing.location,
                notes = if (notes.isNotBlank()) "$notes | Multi-signal updated" else existing.notes
            )
            saveIncident(updated)
            return updated
        }

        val newIncident = DurableIncident(
            incidentId = "INC-$now",
            startTimeEpochMs = now,
            lastUpdatedEpochMs = now,
            level = EmergencyLevel.VERIFYING,
            triggerType = signal.type,
            confidence = signal.confidence,
            location = location,
            isSilent = isSilent,
            notes = notes,
            verificationOutcome = "PENDING_VERIFICATION",
            deliveryStatus = "PENDING"
        )
        saveIncident(newIncident)
        return newIncident
    }

    @Synchronized
    private fun flushToDisk(): Boolean {
        return try {
            val lines = memoryCache.values.map { serialize(it) }
            val tempFile = File(storageDir, "guardian_incidents.tmp")
            tempFile.writeText(lines.joinToString("\n===INCIDENT===\n"), Charsets.UTF_8)
            if (tempFile.exists()) {
                if (incidentsFile.exists()) {
                    incidentsFile.delete()
                }
                tempFile.renameTo(incidentsFile)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadFromDisk() {
        if (!incidentsFile.exists() || incidentsFile.length() == 0L) return
        try {
            val text = incidentsFile.readText(Charsets.UTF_8)
            val blocks = text.split("\n===INCIDENT===\n")
            for (block in blocks) {
                if (block.isBlank()) continue
                val incident = deserialize(block)
                if (incident != null) {
                    memoryCache[incident.incidentId] = incident
                }
            }
        } catch (_: Exception) {
            // Keep memory cache intact
        }
    }

    private fun serialize(inc: DurableIncident): String {
        val sb = StringBuilder()
        sb.append("id=${inc.incidentId}\n")
        sb.append("start=${inc.startTimeEpochMs}\n")
        sb.append("updated=${inc.lastUpdatedEpochMs}\n")
        sb.append("resolved=${inc.resolvedEpochMs ?: ""}\n")
        sb.append("level=${inc.level.name}\n")
        sb.append("trigger=${inc.triggerType.name}\n")
        sb.append("confidence=${inc.confidence}\n")
        sb.append("silent=${inc.isSilent}\n")
        sb.append("notes=${escape(inc.notes)}\n")
        sb.append("outcome=${inc.verificationOutcome}\n")
        sb.append("evidence=${inc.audioEvidencePath ?: ""}\n")
        sb.append("deliveryStatus=${inc.deliveryStatus}\n")
        sb.append("deliveryAttempts=${inc.deliveryAttempts}\n")
        sb.append("isResolved=${inc.isResolved}\n")
        if (inc.location != null) {
            sb.append("location=${inc.location.latitude},${inc.location.longitude},${inc.location.accuracyMeters},${inc.location.timestampEpochMs}\n")
        }
        return sb.toString()
    }

    private fun deserialize(block: String): DurableIncident? {
        val map = mutableMapOf<String, String>()
        for (line in block.lines()) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        val id = map["id"] ?: return null
        val start = map["start"]?.toLongOrNull() ?: return null
        val updated = map["updated"]?.toLongOrNull() ?: start
        val resolved = map["resolved"]?.toLongOrNull()
        val level = try { EmergencyLevel.valueOf(map["level"] ?: "VERIFYING") } catch (_: Exception) { EmergencyLevel.VERIFYING }
        val trigger = try {
            val rawTrigger = map["trigger"] ?: "MANUAL_SOS"
            if (rawTrigger == "KEYWORD") DistressType.SCREAM else DistressType.valueOf(rawTrigger)
        } catch (_: Exception) { DistressType.MANUAL_SOS }
        val confidence = map["confidence"]?.toFloatOrNull() ?: 1.0f
        val silent = map["silent"]?.toBoolean() ?: false
        val notes = unescape(map["notes"] ?: "")
        val outcome = map["outcome"] ?: "PENDING_VERIFICATION"
        val evidenceRaw = map["evidence"]?.ifBlank { null }
        val evidence = if (evidenceRaw != null && File(evidenceRaw).exists()) evidenceRaw else null
        val deliveryStatus = map["deliveryStatus"] ?: "PENDING"
        val deliveryAttempts = map["deliveryAttempts"]?.toIntOrNull() ?: 0
        val isResolved = map["isResolved"]?.toBoolean() ?: false

        var location: LocationData? = null
        val locStr = map["location"]
        if (!locStr.isNullOrBlank()) {
            val tokens = locStr.split(",")
            if (tokens.size >= 4) {
                val lat = tokens[0].toDoubleOrNull() ?: 0.0
                val lon = tokens[1].toDoubleOrNull() ?: 0.0
                val acc = tokens[2].toFloatOrNull() ?: 0f
                val time = tokens[3].toLongOrNull() ?: start
                location = LocationData(latitude = lat, longitude = lon, accuracyMeters = acc, timestampEpochMs = time)
            }
        }

        return DurableIncident(
            incidentId = id,
            startTimeEpochMs = start,
            lastUpdatedEpochMs = updated,
            resolvedEpochMs = resolved,
            level = level,
            triggerType = trigger,
            confidence = confidence,
            location = location,
            isSilent = silent,
            notes = notes,
            verificationOutcome = outcome,
            audioEvidencePath = evidence,
            deliveryStatus = deliveryStatus,
            deliveryAttempts = deliveryAttempts,
            isResolved = isResolved
        )
    }

    private fun escape(s: String): String = s.replace("\n", "\\n")
    private fun unescape(s: String): String = s.replace("\\n", "\n")
}
