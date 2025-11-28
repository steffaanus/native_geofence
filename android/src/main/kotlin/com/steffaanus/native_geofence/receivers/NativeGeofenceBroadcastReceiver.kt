package com.steffaanus.native_geofence.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.steffaanus.native_geofence.generated.GeofenceStatus
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.NativeGeofenceBackgroundWorker
import com.steffaanus.native_geofence.NativeGeofenceForegroundService
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import com.steffaanus.native_geofence.util.GeofenceEvents
import com.steffaanus.native_geofence.util.NativeGeofencePersistence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NativeGeofenceBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NativeGeofenceBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null.")
            return
        }
        val geofenceCallbackParams = getGeofenceCallbackParams(context, geofencingEvent, intent) ?: return
        val geofenceCallbackParamsStorage = GeofenceCallbackParamsStorage.fromApi(geofenceCallbackParams)
        val jsonData = Json.encodeToString(geofenceCallbackParamsStorage)
        
        // Try ForegroundService first for Android 8+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (tryStartForegroundService(context, jsonData)) {
                return
            }
            // If ForegroundService failed, fall through to WorkManager
        }
        
        // Fallback to WorkManager for Android 7 and below, or if ForegroundService failed
        Log.d(TAG, "Using WorkManager fallback")
        startWorkManager(context, jsonData)
    }
    
    /**
     * Attempt to start the ForegroundService to process the geofence event.
     * Returns true if successful, false if it failed and should fallback to WorkManager.
     */
    private fun tryStartForegroundService(context: Context, jsonData: String): Boolean {
        try {
            val serviceIntent = Intent(context, NativeGeofenceForegroundService::class.java).apply {
                action = Constants.ACTION_PROCESS_GEOFENCE
                putExtra(Constants.EXTRA_GEOFENCE_CALLBACK_PARAMS, jsonData)
            }
            
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "Successfully started ForegroundService for geofence event")
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting ForegroundService: ${e.message}", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException starting ForegroundService: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception starting ForegroundService: ${e.message}", e)
        }
        
        return false
    }
    
    /**
     * Start WorkManager to process the geofence event as a fallback.
     */
    private fun startWorkManager(context: Context, jsonData: String) {
        val workRequest = OneTimeWorkRequestBuilder<NativeGeofenceBackgroundWorker>()
            .setInputData(Data.Builder().putString(Constants.WORKER_PAYLOAD_KEY, jsonData).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val workManager = WorkManager.getInstance(context)
        val work = workManager.beginUniqueWork(
            Constants.GEOFENCE_CALLBACK_WORK_GROUP,
            // Process geofence callbacks sequentially.
            ExistingWorkPolicy.APPEND,
            workRequest
        )
        work.enqueue()
        Log.d(TAG, "WorkManager enqueued for geofence event")
    }

    private fun getGeofenceCallbackParams(
        context: Context,
        geofencingEvent: GeofencingEvent,
        intent: Intent
    ): GeofenceCallbackParams? {
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "GeofencingEvent has error Code=${geofencingEvent.errorCode}.")
            return null
        }

        // Get the transition type.
        val geofenceEvent = GeofenceEvents.fromInt(geofencingEvent.geofenceTransition)
        if (geofenceEvent == null) {
            Log.e(
                TAG,
                "GeofencingEvent has invalid transition ID=${geofencingEvent.geofenceTransition}."
            )
            return null
        }

        // CRITICAL FIX: Process ALL triggering geofences, not just the first one
        val geofences = geofencingEvent.triggeringGeofences?.mapNotNull { triggeredGeofence ->
            NativeGeofencePersistence.getGeofence(context, triggeredGeofence.requestId)
        } ?: emptyList()

        if (geofences.isEmpty()) {
            Log.e(TAG, "No geofences found for triggering geofences.")
            return null
        }

        Log.d(TAG, "Processing ${geofences.size} triggered geofence(s)")

        // Update all geofence statuses to ACTIVE now that we've confirmed they're actually working
        geofences.forEach { geofence ->
            if (geofence.status != GeofenceStatus.ACTIVE) {
                geofence.status = GeofenceStatus.ACTIVE
                geofence.statusChangedAtMillis = System.currentTimeMillis()
                NativeGeofencePersistence.saveOrUpdateGeofence(context, geofence)
                Log.d(TAG, "Updated Geofence ID=${geofence.id} status to ACTIVE after receiving event.")
            }
        }

        val location = geofencingEvent.triggeringLocation
        if (location == null) {
            Log.w(TAG, "No triggering location found.")
        }

        // Use the callback handle from the first geofence (they should all have the same dispatcher)
        val callbackHandle = geofences.first().callbackHandle

        return GeofenceCallbackParams(
            geofences = geofences.map { it.toActiveGeofence() },
            event = geofenceEvent,
            location = location?.let {
                com.steffaanus.native_geofence.generated.Location(
                    it.latitude,
                    it.longitude
                )
            },
            callbackHandle = callbackHandle,
        )
    }
}
