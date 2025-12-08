package com.steffaanus.native_geofence.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.steffaanus.native_geofence.generated.GeofenceStatus
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.NativeGeofenceBackgroundWorker
import com.steffaanus.native_geofence.NativeGeofenceForegroundService
import com.steffaanus.native_geofence.api.NativeGeofenceApiImpl
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import com.steffaanus.native_geofence.util.GeofenceEvents
import com.steffaanus.native_geofence.util.NativeGeofenceLogger
import com.steffaanus.native_geofence.util.NativeGeofencePersistence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofenceStatusCodes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NativeGeofenceBroadcastReceiver : BroadcastReceiver() {
    private val log = NativeGeofenceLogger("NativeGeofenceBroadcastReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            log.e("GeofencingEvent is null.")
            return
        }
        
        // Handle geofencing errors - critical for crash recovery
        if (geofencingEvent.hasError()) {
            val errorCode = geofencingEvent.errorCode
            log.e("GeofencingEvent error. Code: $errorCode")
            
            // GEOFENCE_NOT_AVAILABLE (1000) means geofences were removed by the system
            // This typically happens after:
            // - Location process crashes
            // - Android's Network Location Provider (NLP) is disabled
            // - System cleared geofences due to resource constraints
            if (errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                log.w("GEOFENCE_NOT_AVAILABLE error received. Location process may have crashed or NLP was disabled. Re-registering all geofences.")
                NativeGeofenceApiImpl(context).syncGeofences(force = true)
            } else {
                log.e("Unhandled geofence error code: $errorCode")
            }
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
    log.d("Using WorkManager fallback")
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
            log.d("Successfully started ForegroundService for geofence event")
            return true
            
        } catch (e: SecurityException) {
            log.e("SecurityException starting ForegroundService: ${e.message}", e)
        } catch (e: IllegalStateException) {
            log.e("IllegalStateException starting ForegroundService: ${e.message}", e)
        } catch (e: Exception) {
            log.e("Unexpected exception starting ForegroundService: ${e.message}", e)
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
        log.d("WorkManager enqueued for geofence event")
    }

    private fun getGeofenceCallbackParams(
        context: Context,
        geofencingEvent: GeofencingEvent,
        intent: Intent
    ): GeofenceCallbackParams? {
        // Get the transition type.
        val geofenceEvent = GeofenceEvents.fromInt(geofencingEvent.geofenceTransition)
        if (geofenceEvent == null) {
            log.e("GeofencingEvent has invalid transition ID=${geofencingEvent.geofenceTransition}.")
            return null
        }

        // CRITICAL FIX: Process ALL triggering geofences, not just the first one
        val geofences = geofencingEvent.triggeringGeofences?.mapNotNull { triggeredGeofence ->
            NativeGeofencePersistence.getGeofence(context, triggeredGeofence.requestId)
        } ?: emptyList()

        if (geofences.isEmpty()) {
            log.e("No geofences found for triggering geofences.")
            return null
        }

        log.d("Processing ${geofences.size} triggered geofence(s)")

        // Update all geofence statuses to ACTIVE now that we've confirmed they're actually working
        geofences.forEach { geofence ->
            if (geofence.status != GeofenceStatus.ACTIVE) {
                geofence.status = GeofenceStatus.ACTIVE
                geofence.statusChangedAtMillis = System.currentTimeMillis()
                NativeGeofencePersistence.saveOrUpdateGeofence(context, geofence)
                log.d("Updated Geofence ID=${geofence.id} status to ACTIVE after receiving event.")
            }
        }

        val location = geofencingEvent.triggeringLocation
        if (location == null) {
            log.w("No triggering location found.")
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
