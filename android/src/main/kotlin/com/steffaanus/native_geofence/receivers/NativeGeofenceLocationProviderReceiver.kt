package com.steffaanus.native_geofence.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.steffaanus.native_geofence.util.NativeGeofenceLogger
import com.steffaanus.native_geofence.util.ReceiverHelper

/**
 * Receiver for location provider state changes.
 * Triggered when GPS/location services are enabled/disabled.
 * Re-validates and re-registers geofences when location services are restored.
 *
 * This handles the scenario where:
 * - User disables location services (GPS/Network)
 * - System stops monitoring geofences
 * - User re-enables location services
 * - We need to re-register geofences to restore functionality
 */
class NativeGeofenceLocationProviderReceiver : BroadcastReceiver() {
    private val log = NativeGeofenceLogger("NativeGeofenceLocationProviderReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            log.d("Location provider changed - GPS: $isGpsEnabled, Network: $isNetworkEnabled")
            
            if (isGpsEnabled || isNetworkEnabled) {
                log.i("Location services restored. Re-syncing geofences to ensure proper registration.")
                
                // Use ReceiverHelper to create ApiImpl with logging support
                // This enables logs to reach the Flutter app for better debugging
                val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
                
                // Force re-sync to ensure geofences are properly registered
                // This is necessary because the system may have cleared geofences when services were disabled
                apiImpl.syncGeofences(force = true)
            } else {
                log.w("All location providers disabled. Geofences will not trigger until services are restored.")
            }
        }
    }
}