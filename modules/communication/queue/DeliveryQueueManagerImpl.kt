package com.guardiangrid.modules.communication.queue

import com.guardiangrid.core.interfaces.DeliveryQueueManager
import com.guardiangrid.core.models.AlertPayload
import com.guardiangrid.core.models.DeliveryChannel
import com.guardiangrid.core.models.DeliveryReceipt
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.core.models.QueuedEmergencyEvent
import com.guardiangrid.modules.communication.models.EmergencyDeliveryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Implementation of DeliveryQueueManager managing offline persistent queueing,
 * exponential backoff retries, and delivery state tracking.
 */
class DeliveryQueueManagerImpl(
    private val repository: EmergencyQueueRepository = InMemoryQueueRepository(),
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 60_000L,
    private val dispatcherAction: suspend (AlertPayload, DeliveryChannel) -> DeliveryReceipt = { payload, channel ->
        DeliveryReceipt(
            channel = channel,
            status = DeliveryStatus.DELIVERED,
            timestampEpochMs = System.currentTimeMillis(),
            messageId = "stub-${UUID.randomUUID()}"
        )
    }
) : DeliveryQueueManager {

    private val mutex = Mutex()
    private val _queueFlow = MutableStateFlow<List<QueuedEmergencyEvent>>(emptyList())

    init {
        _queueFlow.value = repository.getAll()
    }

    override suspend fun enqueue(payload: AlertPayload, channel: DeliveryChannel): String = mutex.withLock {
        val queueId = UUID.randomUUID().toString()
        val event = QueuedEmergencyEvent(
            queueId = queueId,
            payload = payload,
            targetChannel = channel,
            status = DeliveryStatus.PENDING,
            retryCount = 0,
            nextRetryEpochMs = System.currentTimeMillis(),
            createdAtEpochMs = System.currentTimeMillis()
        )

        repository.save(event)
        _queueFlow.value = repository.getAll()
        return queueId
    }

    override fun observeQueue(): Flow<List<QueuedEmergencyEvent>> = _queueFlow.asStateFlow()

    override suspend fun retryPendingEvents(): List<DeliveryReceipt> = mutex.withLock {
        val now = System.currentTimeMillis()
        val allEvents = repository.getAll()
        val eligibleEvents = allEvents.filter { event ->
            (event.status == DeliveryStatus.PENDING || event.status == DeliveryStatus.FAILED_RETRYING) &&
                    now >= event.nextRetryEpochMs
        }

        val receipts = mutableListOf<DeliveryReceipt>()

        for (event in eligibleEvents) {
            val receipt = try {
                dispatcherAction(event.payload, event.targetChannel)
            } catch (e: Exception) {
                DeliveryReceipt(
                    channel = event.targetChannel,
                    status = DeliveryStatus.FAILED_RETRYING,
                    errorMessage = e.message ?: "Retry dispatch error"
                )
            }

            receipts.add(receipt)

            if (receipt.status == DeliveryStatus.DELIVERED) {
                // Successfully delivered: update event or mark delivered
                val updated = event.copy(status = DeliveryStatus.DELIVERED)
                repository.save(updated)
            } else {
                val newRetryCount = event.retryCount + 1
                if (newRetryCount >= maxRetries) {
                    val abandoned = event.copy(
                        status = DeliveryStatus.ABANDONED,
                        retryCount = newRetryCount
                    )
                    repository.save(abandoned)
                } else {
                    val nextDelay = calculateBackoffDelayMs(newRetryCount)
                    val retrying = event.copy(
                        status = DeliveryStatus.FAILED_RETRYING,
                        retryCount = newRetryCount,
                        nextRetryEpochMs = now + nextDelay
                    )
                    repository.save(retrying)
                }
            }
        }

        _queueFlow.value = repository.getAll()
        return receipts
    }

    override suspend fun clearQueue() = mutex.withLock {
        repository.clear()
        _queueFlow.value = emptyList()
    }

    override suspend fun deleteQueuedItem(queueId: String) = mutex.withLock {
        repository.delete(queueId)
        _queueFlow.value = repository.getAll()
    }

    /**
     * Calculates exponential backoff delay with exponential factor 2^retryCount.
     */
    fun calculateBackoffDelayMs(retryCount: Int): Long {
        val factor = 1L shl retryCount.coerceIn(0, 10)
        val delay = baseDelayMs * factor
        return delay.coerceAtMost(maxDelayMs)
    }

    /**
     * Updates an event's delivery status directly (e.g., when real-time acknowledgment arrives).
     */
    suspend fun updateStatus(queueId: String, newStatus: DeliveryStatus) = mutex.withLock {
        val existing = repository.getById(queueId)
        if (existing != null) {
            val updated = existing.copy(status = newStatus)
            repository.save(updated)
            _queueFlow.value = repository.getAll()
        }
    }

    /**
     * Clears all queue items.
     */
    suspend fun clear() = mutex.withLock {
        repository.clear()
        _queueFlow.value = emptyList()
    }
}
