package com.steffaanus.native_geofence.api

import android.content.Context
import android.util.Log
import com.steffaanus.native_geofence.NativeGeofenceForegroundService
import com.steffaanus.native_geofence.generated.NativeGeofenceBackgroundApi

/**
 * Implementation of NativeGeofenceBackgroundApi for use within the ForegroundService context.
 * This handles communication between Dart and the ForegroundService.
 */
class NativeGeofenceServiceApiImpl(
    private val context: Context,
    private val service: NativeGeofenceForegroundService
) : NativeGeofenceBackgroundApi {
    companion object {
        @JvmStatic
        private val TAG = "NativeGeofenceServiceApiImpl"
    }

    /**
     * Called by Dart when the trigger API is initialized and ready to receive events
     */
    override fun triggerApiInitialized() {
        Log.d(TAG, "Trigger API initialized, notifying service")
        service.triggerApiReady()
    }

    /**
     * Called by Dart to promote to foreground service.
     * Since we're already running as a foreground service, this is a no-op.
     */
    override fun promoteToForeground() {
        Log.d(TAG, "promoteToForeground called, but already running as foreground service")
        // No-op: already in foreground
    }

    /**
     * Called by Dart to demote to background.
     * This is interpreted as a request to stop the service after processing completes.
     */
    override fun demoteToBackground() {
        Log.d(TAG, "demoteToBackground called - service will stop after current event")
        // The service will automatically stop after processing all events in the queue
        // No explicit action needed here as the service manages its own lifecycle
    }
}