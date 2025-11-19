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
}