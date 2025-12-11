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
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import com.steffaanus.native_geofence.util.GeofenceEvents
import com.steffaanus.native_geofence.util.NativeGeofenceLogger
import com.steffaanus.native_geofence.util.NativeGeofencePersistence
import com.steffaanus.native_geofence.util.ReceiverHelper
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
            log.e("GeofencingEvent error. Code: $errorCode (${getErrorCodeName(errorCode)})")
            
            when (errorCode) {
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> {
                    // Location process crashed or NLP disabled
                    handleGeofenceNotAvailable(context)
                }
                
                GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> {
                    // System limit reached (100 geofences per app)
                    handleTooManyGeofences(context)
                }
                
                GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> {
                    // Too many apps using geofences
                    handleTooManyPendingIntents(context)
                }
                
                GeofenceStatusCodes.ERROR -> {
                    // Generic error - retry with backoff
                    handleGenericError(context, errorCode)
                }
                
                GeofenceStatusCodes.TIMEOUT -> {
                    // Location service timeout
                    handleTimeout(context)
                }
                
                GeofenceStatusCodes.INTERRUPTED -> {
                    // Operation was interrupted
                    handleInterrupted(context)
                }
                
                else -> {
                    log.e("Unknown geofence error code: $errorCode")
                    handleGenericError(context, errorCode)
                }
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
    
    /**
     * Handle GEOFENCE_NOT_AVAILABLE error (1000)
     * This is the most critical error - location process crashed
     */
    private fun handleGeofenceNotAvailable(context: Context) {
        log.w("GEOFENCE_NOT_AVAILABLE - Location process crashed or NLP disabled")
        
        // Validate location services are available
        if (!ReceiverHelper.isLocationAvailable(context)) {
            log.w("Location services disabled - cannot recover until re-enabled")
            return
        }
        
        // Check retry state
        if (!ReceiverHelper.RetryManager.shouldAttemptRecovery(context, GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)) {
            log.w("Maximum retries exceeded or too soon to retry")
            return
        }
        
        log.i("Attempting geofence recovery after location crash")
        ReceiverHelper.RetryManager.recordRetryAttempt(context, GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)
        
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        apiImpl.syncGeofences(force = true)
        
        // Reset retry state on successful sync
        ReceiverHelper.RetryManager.resetRetryState(context, GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)
    }
    
    /**
     * Handle GEOFENCE_TOO_MANY_GEOFENCES error (1001)
     * System limit of 100 geofences per app reached
     */
    private fun handleTooManyGeofences(context: Context) {
        val allGeofences = NativeGeofencePersistence.getAllGeofences(context)
        
        log.e("TOO_MANY_GEOFENCES - Limit reached. Currently have ${allGeofences.size} geofences")
        log.e("Android allows maximum 100 geofences per app. Sync will not help.")
        log.i("Consider removing old/expired geofences or redesigning to use fewer geofences")
        
        // Don't retry - this requires app-level fix
    }
    
    /**
     * Handle GEOFENCE_TOO_MANY_PENDING_INTENTS error (1002)
     * Multiple apps competing for geofence resources
     */
    private fun handleTooManyPendingIntents(context: Context) {
        log.w("TOO_MANY_PENDING_INTENTS - Multiple apps using geofences")
        
        // Check retry state with exponential backoff
        if (!ReceiverHelper.RetryManager.shouldAttemptRecovery(context, GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS)) {
            log.w("Too soon to retry or max retries exceeded")
            return
        }
        
        log.i("Retrying geofence registration after backoff delay")
        ReceiverHelper.RetryManager.recordRetryAttempt(context, GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS)
        
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        apiImpl.syncGeofences(force = true)
    }
    
    /**
     * Handle generic ERROR (13)
     */
    private fun handleGenericError(context: Context, errorCode: Int) {
        log.w("Generic geofence error: $errorCode")
        
        if (!ReceiverHelper.RetryManager.shouldAttemptRecovery(context, errorCode)) {
            log.w("Skipping retry due to backoff policy")
            return
        }
        
        log.i("Retrying geofence sync after generic error")
        ReceiverHelper.RetryManager.recordRetryAttempt(context, errorCode)
        
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        apiImpl.syncGeofences(force = true)
    }
    
    /**
     * Handle TIMEOUT error (15)
     */
    private fun handleTimeout(context: Context) {
        log.w("Location service timeout - may indicate temporary connectivity issues")
        
        // Timeout is often temporary, use more aggressive retry
        if (!ReceiverHelper.RetryManager.shouldAttemptRecovery(context, GeofenceStatusCodes.TIMEOUT)) {
            return
        }
        
        ReceiverHelper.RetryManager.recordRetryAttempt(context, GeofenceStatusCodes.TIMEOUT)
        
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        apiImpl.syncGeofences(force = true)
    }
    
    /**
     * Handle INTERRUPTED error (14)
     */
    private fun handleInterrupted(context: Context) {
        log.w("Geofence operation interrupted - will retry")
        
        if (!ReceiverHelper.RetryManager.shouldAttemptRecovery(context, GeofenceStatusCodes.INTERRUPTED)) {
            return
        }
        
        ReceiverHelper.RetryManager.recordRetryAttempt(context, GeofenceStatusCodes.INTERRUPTED)
        
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        apiImpl.syncGeofences(force = true)
    }
    
    /**
     * Get human-readable error code name for logging
     */
    private fun getErrorCodeName(errorCode: Int): String {
        return when (errorCode) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> "GEOFENCE_NOT_AVAILABLE"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "TOO_MANY_GEOFENCES"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> "TOO_MANY_PENDING_INTENTS"
            GeofenceStatusCodes.ERROR -> "ERROR"
            GeofenceStatusCodes.TIMEOUT -> "TIMEOUT"
            GeofenceStatusCodes.INTERRUPTED -> "INTERRUPTED"
            else -> "UNKNOWN($errorCode)"
        }
    }
}
