package com.guardiangrid.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.EmergencyStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android framework-level location manager for GuardianGrid.
 * Provides resilient location acquisition using GPS and Network providers with automatic fallback.
 * Gracefully handles denied permissions, disabled providers, and timeouts.
 */
class GuardianLocationManager(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _lastLocation = MutableStateFlow<LocationData?>(null)
    val lastLocation: StateFlow<LocationData?> = _lastLocation.asStateFlow()

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val locationData = location.toLocationData()
            _lastLocation.value = locationData
            EmergencyStateHolder.updateLocation(locationData)
            Log.d(TAG, "Location updated: lat=${locationData.latitude}, lon=${locationData.longitude}, acc=${locationData.accuracyMeters}m")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            EmergencyStateHolder.updateGpsAvailability(isGpsEnabled())
        }

        override fun onProviderDisabled(provider: String) {
            EmergencyStateHolder.updateGpsAvailability(isGpsEnabled())
        }
    }

    /**
     * Check whether fine or coarse location permissions are granted.
     */
    fun hasLocationPermission(): Boolean {
        val finePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return finePermission || coarsePermission
    }

    /**
     * Check whether GPS provider is enabled by the user in system settings.
     */
    fun isGpsEnabled(): Boolean {
        return try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check GPS provider status: ${e.message}")
            false
        }
    }

    /**
     * Fetch last-known location across GPS and Network providers.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): LocationData? {
        if (!hasLocationPermission() || locationManager == null) {
            Log.w(TAG, "Cannot get last known location: permission denied or manager null")
            return null
        }

        var bestLocation: Location? = null

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) bestLocation = gpsLoc
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null && (bestLocation == null || netLoc.time > bestLocation.time)) {
                    bestLocation = netLoc
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while accessing last known location: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error retrieving last known location: ${e.message}")
        }

        val locationData = bestLocation?.toLocationData()
        if (locationData != null) {
            _lastLocation.value = locationData
            EmergencyStateHolder.updateLocation(locationData)
        }
        return locationData
    }

    /**
     * Start continuous location updates for active protection.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(minTimeMs: Long = 10000L, minDistanceM: Float = 5f) {
        if (isListening || !hasLocationPermission() || locationManager == null) return

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener
                )
            }
            isListening = true
            EmergencyStateHolder.updateGpsAvailability(isGpsEnabled())
            Log.i(TAG, "Started location updates")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates: ${e.message}")
        }
    }

    /**
     * Stop location updates to conserve device battery.
     */
    fun stopLocationUpdates() {
        if (!isListening || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
            isListening = false
            Log.i(TAG, "Stopped location updates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location updates: ${e.message}")
        }
    }

    /**
     * Acquire the freshest available location within a timeout window.
     * If timeout expires or sensors are unavailable, gracefully falls back to last-known location.
     */
    @SuppressLint("MissingPermission")
    suspend fun acquireFreshLocation(timeoutMs: Long = 4000L): LocationData? {
        if (!hasLocationPermission() || locationManager == null) {
            return getLastKnownLocation()
        }

        val cached = getLastKnownLocation()
        // If cached location is fresh (< 30 seconds old), return immediately
        if (cached != null && (System.currentTimeMillis() - cached.timestampEpochMs) < 30000L) {
            return cached
        }

        val freshFix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val singleListener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        try {
                            locationManager.removeUpdates(this)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error removing single location listener: ${e.message}")
                        }
                        val locData = loc.toLocationData()
                        _lastLocation.value = locData
                        EmergencyStateHolder.updateLocation(locData)
                        if (continuation.isActive) {
                            continuation.resume(locData)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                continuation.invokeOnCancellation {
                    try {
                        locationManager.removeUpdates(singleListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error on singleListener cancellation: ${e.message}")
                    }
                }

                try {
                    val requestedGps = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            0L,
                            0f,
                            singleListener
                        )
                        true
                    } else false

                    val requestedNet = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            0L,
                            0f,
                            singleListener
                        )
                        true
                    } else false

                    if (!requestedGps && !requestedNet) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed requesting single location update: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

        return freshFix ?: cached
    }

    companion object {
        private const val TAG = "GuardianLocationMgr"

        private fun Location.toLocationData(): LocationData {
            return LocationData(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                altitudeMeters = altitude,
                speedMps = speed,
                timestampEpochMs = time,
                provider = provider ?: "unknown"
            )
        }
    }
}
