package com.guardiangrid.modules.communication.retry

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Scheduler managing Android WorkManager requests for offline delivery retry.
 */
object EmergencyDeliveryWorkScheduler {

    const val UNIQUE_WORK_NAME = "guardiangrid_emergency_delivery_retry"

    /**
     * Enqueues a unique background retry work request to run when network connectivity returns.
     */
    fun scheduleRetry(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val retryRequest = OneTimeWorkRequestBuilder<EmergencyDeliveryWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .addTag("emergency_delivery_retry")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                retryRequest
            )
        } catch (_: Exception) {
            // Gracefully ignore if WorkManager cannot be initialized (e.g. in test runners without WorkManager rule)
        }
    }

    /**
     * Cancels any pending retry work when all alerts are resolved or user cancels.
     */
    fun cancelRetry(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        } catch (_: Exception) {
            // Ignore if WorkManager unavailable
        }
    }
}
