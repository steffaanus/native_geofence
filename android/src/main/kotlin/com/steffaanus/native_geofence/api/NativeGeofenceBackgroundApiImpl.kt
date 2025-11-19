package com.steffaanus.native_geofence.api

import android.content.Context
import android.content.Intent
import android.util.Log
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.NativeGeofenceForegroundService
import com.steffaanus.native_geofence.NativeGeofenceBackgroundWorker
import com.steffaanus.native_geofence.generated.NativeGeofenceBackgroundApi

class NativeGeofenceBackgroundApiImpl(
    private val context: Context,
    private val worker: NativeGeofenceBackgroundWorker
) : NativeGeofenceBackgroundApi {
    companion object {
        @JvmStatic
        private val TAG = "NativeGeofenceBackgroundApiImpl"
    }

    override fun triggerApiInitialized() {
        worker.triggerApiReady()
    }
}
