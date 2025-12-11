package com.steffaanus.native_geofence.util

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.steffaanus.native_geofence.api.NativeGeofenceApiImpl
import io.flutter.plugin.common.BinaryMessenger
import java.lang.ref.WeakReference

/**
 * Helper class for BroadcastReceivers to access Flutter logging infrastructure.
 * 
 * This class maintains a weak reference to the BinaryMessenger so that receivers
 * can forward logs to Flutter. The weak reference prevents memory leaks if the
 * Flutter engine is destroyed.
 */
object ReceiverHelper {
    private const val TAG = "ReceiverHelper"
    
    // Use WeakReference to prevent memory leaks
    private var binaryMessengerRef: WeakReference<BinaryMessenger>? = null
    
    /**
     * Store the BinaryMessenger when the plugin is initialized.
     * This should be called from NativeGeofencePlugin.onAttachedToEngine.
     */
    fun setBinaryMessenger(messenger: BinaryMessenger?) {
        binaryMessengerRef = if (messenger != null) {
            WeakReference(messenger)
        } else {
            null
        }
        Log.d(TAG, "BinaryMessenger ${if (messenger != null) "set" else "cleared"} for receivers")
    }
    
    /**
     * Get the BinaryMessenger if available.
     * Returns null if the Flutter engine is not running or has been garbage collected.
     */
    fun getBinaryMessenger(): BinaryMessenger? {
        return binaryMessengerRef?.get()
    }
    
    /**
     * Create an NativeGeofenceApiImpl with logging support if possible.
     * Falls back to non-logging version if BinaryMessenger is not available.
     */
    fun createApiImplWithLogging(context: Context): NativeGeofenceApiImpl {
        val binaryMessenger = getBinaryMessenger()
        
        return if (binaryMessenger != null) {
            Log.d(TAG, "Creating ApiImpl with Flutter logging support")
            NativeGeofenceApiImpl(context, binaryMessenger)
        } else {
            Log.w(TAG, "BinaryMessenger not available - logs won't reach Flutter app")
            NativeGeofenceApiImpl(context, null)
        }
    }
    
    /**
     * Check if location services are currently enabled.
     * This should be called before attempting geofence sync operations.
     */
    fun isLocationAvailable(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.e(TAG, "LocationManager service not available")
            return false
        }
        
        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking GPS provider: ${e.message}")
            false
        }
        
        val networkEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Network provider: ${e.message}")
            false
        }
        
        return gpsEnabled || networkEnabled
    }
}