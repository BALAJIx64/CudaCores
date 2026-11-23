package com.guardiangrid.modules.communication.queue

import com.guardiangrid.core.models.QueuedEmergencyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of EmergencyQueueRepository for tests and runtime caching.
 */
class InMemoryQueueRepository : EmergencyQueueRepository {

    private val storage = ConcurrentHashMap<String, QueuedEmergencyEvent>()

    override fun save(event: QueuedEmergencyEvent) {
        storage[event.queueId] = event
    }

    override fun getAll(): List<QueuedEmergencyEvent> = storage.values.toList()

    override fun getById(queueId: String): QueuedEmergencyEvent? = storage[queueId]

    override fun delete(queueId: String) {
        storage.remove(queueId)
    }

    override fun clear() {
        storage.clear()
    }
}
