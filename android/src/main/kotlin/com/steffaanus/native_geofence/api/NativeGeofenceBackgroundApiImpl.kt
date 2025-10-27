package com.Steffaanus.native_geofence.api

import android.content.Context
import android.content.Intent
import android.util.Log
import com.Steffaanus.native_geofence.Constants
import com.Steffaanus.native_geofence.NativeGeofenceForegroundService
import com.Steffaanus.native_geofence.NativeGeofenceBackgroundWorker
import com.Steffaanus.native_geofence.generated.NativeGeofenceBackgroundApi

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

    override fun promoteToForeground() {
        context.startForegroundService(Intent(context, NativeGeofenceForegroundService::class.java))
        Log.d(TAG, "Promoted background service to foreground service.")
    }

    override fun demoteToBackground() {
        val intent = Intent(context, NativeGeofenceForegroundService::class.java)
        intent.setAction(Constants.ACTION_SHUTDOWN)
        context.startForegroundService(intent)
        Log.d(TAG, "Demoted foreground service back to background service.")
    }
}
