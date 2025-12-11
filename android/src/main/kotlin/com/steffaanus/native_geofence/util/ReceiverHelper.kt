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

/**
 * Manages retry attempts with exponential backoff for geofence operations.
 * Prevents excessive retries that drain battery or overwhelm the system.
 */
object RetryManager {
    private const val TAG = "RetryManager"
    private const val PREFS_KEY = "geofence_retry_state"
    
    private const val MAX_RETRY_COUNT = 5
    private const val BASE_DELAY_MS = 2000L // 2 seconds
    private const val MAX_DELAY_MS = 60000L // 60 seconds
    private const val RESET_AFTER_MS = 3600000L // 1 hour
    
    /**
     * Check if we should attempt recovery based on retry state.
     * Uses exponential backoff: 2s, 4s, 8s, 16s, 32s, 60s (max)
     */
    fun shouldAttemptRecovery(context: Context, errorCode: Int): Boolean {
        val state = getRetryState(context, errorCode)
        
        // Reset if it's been more than 1 hour since last attempt
        if (System.currentTimeMillis() - state.lastAttemptTime > RESET_AFTER_MS) {
            resetRetryState(context, errorCode)
            return true
        }
        
        // Check if max retries exceeded
        if (state.attemptCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Max retries ($MAX_RETRY_COUNT) exceeded for error $errorCode")
            return false
        }
        
        // Calculate required delay with exponential backoff
        val requiredDelay = calculateBackoffDelay(state.attemptCount)
        val timeSinceLastAttempt = System.currentTimeMillis() - state.lastAttemptTime
        
        if (timeSinceLastAttempt < requiredDelay) {
            Log.d(TAG, "Too soon to retry (${timeSinceLastAttempt}ms < ${requiredDelay}ms)")
            return false
        }
        
        return true
    }
    
    /**
     * Record a retry attempt.
     */
    fun recordRetryAttempt(context: Context, errorCode: Int) {
        val state = getRetryState(context, errorCode)
        val newAttemptCount = state.attemptCount + 1
        saveRetryState(context, errorCode, newAttemptCount, System.currentTimeMillis())
        
        Log.d(TAG, "Recorded retry attempt $newAttemptCount/$MAX_RETRY_COUNT for error $errorCode")
    }
    
    /**
     * Reset retry state after successful recovery.
     */
    fun resetRetryState(context: Context, errorCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("retry_${errorCode}_count")
            .remove("retry_${errorCode}_time")
            .apply()
        Log.d(TAG, "Reset retry state for error $errorCode")
    }
    
    private fun calculateBackoffDelay(attemptCount: Int): Long {
        if (attemptCount == 0) return 0L
        val delay = BASE_DELAY_MS * (1 shl attemptCount) // 2^attemptCount
        return minOf(delay, MAX_DELAY_MS)
    }
    
    data class RetryState(
        val attemptCount: Int,
        val lastAttemptTime: Long
    )
    
    private fun getRetryState(context: Context, errorCode: Int): RetryState {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        val attemptCount = prefs.getInt("retry_${errorCode}_count", 0)
        val lastAttemptTime = prefs.getLong("retry_${errorCode}_time", 0)
        return RetryState(attemptCount, lastAttemptTime)
    }
    
    private fun saveRetryState(context: Context, errorCode: Int, attemptCount: Int, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("retry_${errorCode}_count", attemptCount)
            .putLong("retry_${errorCode}_time", timestamp)
            .apply()
    }
}