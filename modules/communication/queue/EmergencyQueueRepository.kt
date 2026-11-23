package com.guardiangrid.modules.communication.queue

import com.guardiangrid.core.models.QueuedEmergencyEvent

/**
 * Storage contract for persisting queued emergency events across application lifecycles.
 */
interface EmergencyQueueRepository {

    /**
     * Persists or updates a queued event.
     */
    fun save(event: QueuedEmergencyEvent)

    /**
     * Retrieves all currently queued events.
     */
    fun getAll(): List<QueuedEmergencyEvent>

    /**
     * Retrieves a single queued event by ID.
     */
    fun getById(queueId: String): QueuedEmergencyEvent?

    /**
     * Removes an event from the persistent queue (e.g. once delivered or abandoned).
     */
    fun delete(queueId: String)

    /**
     * Clears all queued items.
     */
    fun clear()
}
