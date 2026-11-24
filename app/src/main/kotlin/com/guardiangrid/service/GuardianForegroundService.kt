package com.guardiangrid.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.MainActivity
import com.guardiangrid.R
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.core.state.MonitoringServiceState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production Android Foreground Service maintaining continuous personal-safety monitoring.
 *
 * Platform & Android Policy Compliance:
 * - Operates with `foregroundServiceType="microphone"` in compliance with Android 14+ (API 34) requirements.
 * - Enforces pre-flight runtime permission verification (`RECORD_AUDIO`) before launching foreground microphone capture.
 * - Provides a persistent low-importance notification channel with an immediate "Stop Monitoring" notification action.
 * - Idempotent lifecycle management: prevents duplicate capture loops or leaked hardware sensor listeners.
 * - Lifecycle separation: protection persists when the user navigates away from the Activity or screen is locked.
 */
class GuardianForegroundService : Service() {

    private val isRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "GuardianForegroundService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_MONITORING
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_STOP_MONITORING -> {
                handleStopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START_MONITORING -> {
                handleStartMonitoring()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action: $action")
                return START_NOT_STICKY
            }
        }
    }

    private fun handleStartMonitoring() {
        if (isRunning.get()) {
            Log.d(TAG, "Monitoring service already active; ignoring duplicate start request.")
            return
        }

        // 1. Enforce runtime microphone permission check before entering foreground state
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start microphone monitoring: android.permission.RECORD_AUDIO permission missing.")
            EmergencyStateHolder.setMonitoringServiceState(
                MonitoringServiceState.PERMISSION_REQUIRED,
                "Microphone permission (RECORD_AUDIO) required to activate protection."
            )
            stopSelf()
            return
        }

        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STARTING)

        // 2. Build persistent status notification
        val notification = buildForegroundNotification()

        // 3. Launch foreground execution with API 34+ microphone type declaration
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            EmergencyStateHolder.setMonitoringServiceState(
                MonitoringServiceState.ERROR,
                "Foreground service start failed: ${e.message}"
            )
            stopSelf()
            return
        }

        // 4. Access shared application container and activate monitoring
        val app = application as? GuardianGridApp
        val container = app?.container
        if (container == null) {
            Log.e(TAG, "GuardianGridContainer not initialized in Application.")
            EmergencyStateHolder.setMonitoringServiceState(
                MonitoringServiceState.ERROR,
                "Application dependency container not available."
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            container.coordinator.startProtection()
            container.locationManager.startLocationUpdates()

            isRunning.set(true)
            EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.ACTIVE)
            Log.i(TAG, "GuardianGrid active protection started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting protection in coordinator: ${e.message}", e)
            EmergencyStateHolder.setMonitoringServiceState(
                MonitoringServiceState.ERROR,
                "Failed to start monitoring: ${e.message}"
            )
            handleStopMonitoring()
        }
    }

    private fun handleStopMonitoring() {
        Log.d(TAG, "Stopping monitoring service.")
        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPING)

        try {
            val app = application as? GuardianGridApp
            val container = app?.container
            container?.coordinator?.stopProtection()
            container?.locationManager?.stopLocationUpdates()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during stopProtection cleanup: ${e.message}")
        }

        isRunning.set(false)
        EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPED)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.getAndSet(false)) {
            Log.w(TAG, "Service destroyed unexpectedly while still running. Cleaning up resources...")
            try {
                val app = application as? GuardianGridApp
                app?.container?.coordinator?.stopProtection()
                app?.container?.locationManager?.stopLocationUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "Error during onDestroy cleanup: ${e.message}")
            }
            EmergencyStateHolder.setMonitoringServiceState(MonitoringServiceState.STOPPED)
        }
        Log.d(TAG, "GuardianForegroundService destroyed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GuardianGrid Active Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent protection status when GuardianGrid is monitoring for distress sounds and anomalies."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GuardianForegroundService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GuardianGrid Active Protection")
            .setContentText("Continuous acoustic & motion safety monitoring is active.")
            .setSmallIcon(R.drawable.ic_stat_guardiangrid)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Monitoring",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        private const val TAG = "GuardianForegroundSvc"
        const val CHANNEL_ID = "guardiangrid_protection_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_MONITORING = "com.guardiangrid.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.guardiangrid.action.STOP_MONITORING"

        /**
         * Starts the foreground monitoring service while the app is in the foreground.
         */
        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Explicitly stops the foreground monitoring service and hardware capture.
         */
        fun stop(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
}
