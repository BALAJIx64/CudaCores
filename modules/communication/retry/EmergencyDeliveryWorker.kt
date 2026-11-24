package com.guardiangrid.modules.communication.retry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.DeliveryStatus
import com.guardiangrid.modules.communication.EmergencyCommunicatorImpl
import java.util.concurrent.atomic.AtomicInteger

/**
 * WorkManager CoroutineWorker orchestrating automatic offline emergency alert retries.
 *
 * Guarantees:
 * - Triggers automatically when network connectivity is restored via [Constraints].
 * - Executes exponential backoff retries managed by Android system scheduler.
 * - Retains persistent state across application restarts and process termination.
 * - Prevents duplicate transmissions via IdempotencyManager.
 * - Never marks delivery as successful until confirmed by the recipient platform.
 */
class EmergencyDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "EmergencyDeliveryWorker"
        val executionCount = AtomicInteger(0)
    }

    override suspend fun doWork(): Result {
        executionCount.incrementAndGet()

        val app = applicationContext as? GuardianGridApp
        val container = app?.container
        if (container == null) {
            // Container not initialized yet; retry when ready
            return Result.retry()
        }

        val comm = container.communicator as? EmergencyCommunicatorImpl
            ?: return Result.retry()

        return try {
            val receipts = comm.queueManager.retryPendingEvents()

            // Check remaining queue state
            val pendingEvents = comm.queueRepository.getAll().filter {
                it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.FAILED_RETRYING
            }

            if (pendingEvents.isNotEmpty()) {
                // Still have pending items needing future retry
                Result.retry()
            } else {
                // All queued alerts are either DELIVERED or ABANDONED
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
