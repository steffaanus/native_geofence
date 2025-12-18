package com.steffaanus.native_geofence.api

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.steffaanus.native_geofence.model.GeofenceStorage
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.generated.ActiveGeofence
import com.steffaanus.native_geofence.generated.FlutterError
import com.steffaanus.native_geofence.generated.ForegroundServiceConfiguration
import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.GeofenceStatus
import com.steffaanus.native_geofence.generated.NativeGeofenceApi
import com.steffaanus.native_geofence.generated.NativeGeofenceErrorCode
import com.steffaanus.native_geofence.util.GeofenceEvents
import com.steffaanus.native_geofence.util.NativeGeofenceLogger
import com.steffaanus.native_geofence.receivers.NativeGeofenceBroadcastReceiver
import com.steffaanus.native_geofence.util.NativeGeofencePersistence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.flutter.plugin.common.BinaryMessenger

class NativeGeofenceApiImpl(private val context: Context, private val binaryMessenger: BinaryMessenger? = null) : NativeGeofenceApi {
    private val log = NativeGeofenceLogger("NativeGeofenceApiImpl")

    private var lastSyncTime = 0L
    private val SYNC_DEBOUNCE_MS = 5000L // 5 seconden
    private val geofencingClient = LocationServices.getGeofencingClient(context)

    override fun initialize(
        callbackDispatcherHandle: Long,
        foregroundServiceConfig: ForegroundServiceConfiguration?
    ) {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putLong(Constants.CALLBACK_DISPATCHER_HANDLE_KEY, callbackDispatcherHandle)
        
        // Store notification configuration if provided
        if (foregroundServiceConfig != null) {
            editor.putString(Constants.FOREGROUND_NOTIFICATION_TITLE_KEY, foregroundServiceConfig.notificationTitle)
            editor.putString(Constants.FOREGROUND_NOTIFICATION_TEXT_KEY, foregroundServiceConfig.notificationText)
            editor.putString(Constants.FOREGROUND_NOTIFICATION_ICON_KEY, foregroundServiceConfig.notificationIconName ?: Constants.DEFAULT_NOTIFICATION_ICON)
            log.d("Stored foreground service notification configuration")
        }
        
        editor.apply()
        log.d("Initialized NativeGeofenceApi.")
        syncGeofences(false)
    }

    override fun createGeofence(
        geofence: Geofence,
        callback: (Result<Unit>) -> Unit
    ) {
        // First remove the geofence, if it exists.
        // In the success/failure callback, the geofence is then created.
        geofencingClient.removeGeofences(listOf(geofence.id)).run {
            addOnSuccessListener {
                createGeofenceHelper(geofence, true, callback)
            }
            addOnFailureListener {
                // If the geofence does not exist, this call will fail.
                // In that case, we can ignore the error and just create the geofence.
                createGeofenceHelper(geofence, true, callback)
            }
        }
    }

    internal fun syncGeofences(force: Boolean) {
        // Force sync bypasses ALL debounce checks for critical recovery scenarios
        if (force) {
            log.i("Force sync requested - bypassing debounce for critical recovery")
            performSyncGeofences(true)
            lastSyncTime = System.currentTimeMillis()
            return
        }
        
        // Normal sync with debounce protection
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < SYNC_DEBOUNCE_MS) {
            log.d("Sync skipped - too soon after last sync (${currentTime - lastSyncTime}ms < ${SYNC_DEBOUNCE_MS}ms)")
            return
        }
        lastSyncTime = currentTime
        
        log.i("Starting geofence sync (force=$force)")
        performSyncGeofences(false)
    }
    
    /**
     * Perform the actual sync operation - refactored to allow force bypass
     * @param force If true, re-register ALL geofences. If false, only re-register PENDING/FAILED ones.
     */
    private fun performSyncGeofences(force: Boolean) {
        val startTime = System.currentTimeMillis()
        val allGeofences = NativeGeofencePersistence.getAllGeofences(context)
        
        // Determine which geofences need re-registration
        val toReregister = if (force) {
            allGeofences
        } else {
            allGeofences.filter { it.status != GeofenceStatus.ACTIVE }
        }
        
        if (toReregister.isEmpty()) {
            log.d("No geofences need re-registration")
            return
        }
        
        // Count geofences by status for logging
        val pendingCount = allGeofences.count { it.status == GeofenceStatus.PENDING }
        val failedCount = allGeofences.count { it.status == GeofenceStatus.FAILED }
        val activeCount = allGeofences.count { it.status == GeofenceStatus.ACTIVE }
        
        log.i("Starting batch re-registration of ${toReregister.size} geofences (Total: ${allGeofences.size}, Pending: $pendingCount, Failed: $failedCount, Active: $activeCount)")
        
        // Try batch operation first for best performance
        tryBatchOperation(toReregister, startTime)
    }
    
    /**
     * Attempt batch remove and add for optimal performance.
     * Falls back to individual operations if batch fails.
     */
    @SuppressLint("MissingPermission")
    private fun tryBatchOperation(geofences: List<GeofenceStorage>, startTime: Long) {
        val idsToRemove = geofences.map { it.id }
        
        log.d("Batch removing ${idsToRemove.size} geofences")
        
        // Step 1: Batch remove
        geofencingClient.removeGeofences(idsToRemove).run {
            addOnCompleteListener { removeTask ->
                if (removeTask.isSuccessful) {
                    log.d("Batch remove succeeded for ${idsToRemove.size} geofences")
                } else {
                    log.w("Batch remove failed: ${removeTask.exception?.message}")
                }
                
                // Continue to add regardless of remove result (some may have been removed)
                // Step 2: Batch add
                batchAddGeofences(geofences, startTime)
            }
        }
    }
    
    /**
     * Attempt to add mult geofences in a single batch operation.
     * Falls back to individual operations if batch fails.
     */
    @SuppressLint("MissingPermission")
    private fun batchAddGeofences(geofences: List<GeofenceStorage>, startTime: Long) {
        log.d("Batch adding ${geofences.size} geofences")
        
        // Build geofencing request with all geofences
        val request = GeofencingRequest.Builder().apply {
            geofences.forEach { geofence ->
                val apiGeofence = geofence.toApi()
                setInitialTrigger(GeofenceEvents.createMask(apiGeofence.androidSettings.initialTriggers))
                addGeofence(apiGeofence.toGeofence(context))
            }
        }.build()
        
        geofencingClient.addGeofences(request, getGeofencePendingIndent(context)).run {
            addOnSuccessListener {
                // BEST CASE: All geofences added successfully via batch
                val duration = System.currentTimeMillis() - startTime
                log.i("✅ Batch operation succeeded in ${duration}ms for ${geofences.size} geofences")
                
                // Update all statuses to PENDING
                geofences.forEach { geofence ->
                    geofence.status = GeofenceStatus.PENDING
                    geofence.statusChangedAtMillis = System.currentTimeMillis()
                    NativeGeofencePersistence.saveOrUpdateGeofence(context, geofence)
                }
            }
            
            addOnFailureListener { batchException ->
                // FALLBACK: Batch failed, try individual operations
                val duration = System.currentTimeMillis() - startTime
                log.w("❌ Batch add failed after ${duration}ms: ${batchException.message}")
                log.i("Falling back to individual operations for ${geofences.size} geofences")
                
                fallbackToIndividualOperations(geofences, startTime)
            }
        }
    }
    
    /**
     * Fallback to individual operations when batch fails.
     * Ensures maximum resilience - each geofence is tried individually.
     */
    private fun fallbackToIndividualOperations(geofences: List<GeofenceStorage>, overallStartTime: Long) {
        var successCount = 0
        var failureCount = 0
        val totalCount = geofences.size
        
        geofences.forEach { geofence ->
            // Remove then add each geofence individually
            geofencingClient.removeGeofences(listOf(geofence.id)).run {
                addOnCompleteListener { removeTask ->
                    // Continue to add regardless of remove result
                    createGeofenceHelper(geofence.toApi(), false) { result ->
                        if (result.isSuccess) {
                            successCount++
                            log.d("✅ Individual operation succeeded for ${geofence.id} ($successCount/$totalCount)")
                        } else {
                            failureCount++
                            // Status already set to FAILED by createGeofenceHelper
                            log.e("❌ Individual operation failed for ${geofence.id} ($failureCount/$totalCount)")
                        }
                        
                        // Log summary when all operations complete
                        if (successCount + failureCount == totalCount) {
                            val totalDuration = System.currentTimeMillis() - overallStartTime
                            log.i("Individual operations complete in ${totalDuration}ms: $successCount success, $failureCount failed out of $totalCount")
                            
                            if (failureCount > totalCount / 2) {
                                log.e("⚠️ More than 50% of geofences failed - possible permission or system issue")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getGeofenceIds(): List<String> {
        return NativeGeofencePersistence.getAllGeofenceIds(context)
    }

    override fun getGeofences(): List<ActiveGeofence> {
        val geofences = NativeGeofencePersistence.getAllGeofences(context)
        return geofences.map {
            it.toActiveGeofence()
        }.toList()
    }

    override fun removeGeofenceById(id: String, callback: (Result<Unit>) -> Unit) {
        geofencingClient.removeGeofences(listOf(id)).run {
            addOnSuccessListener {
                NativeGeofencePersistence.removeGeofence(context, id)
                log.d("Removed Geofence ID=$id.")
                callback.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                val existingIds = NativeGeofencePersistence.getAllGeofenceIds(context)
                val errorCode =
                    if (existingIds.contains(id)) NativeGeofenceErrorCode.PLUGIN_INTERNAL else NativeGeofenceErrorCode.GEOFENCE_NOT_FOUND
                log.e("Failure when removing Geofence ID=$id", it)
                callback.invoke(
                    Result.failure(
                        FlutterError(
                            errorCode.raw.toString(),
                            it.toString()
                        )
                    )
                )
            }
        }
    }

    override fun removeAllGeofences(callback: (Result<Unit>) -> Unit) {
        geofencingClient.removeGeofences(getGeofencePendingIndent(context)).run {
            addOnSuccessListener {
                NativeGeofencePersistence.removeAllGeofences(context)
                log.d("Removed all geofences (if any).")
                callback.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                log.e("Failed to remove all geofences", it)
                callback.invoke(
                    Result.failure(
                        FlutterError(
                            NativeGeofenceErrorCode.PLUGIN_INTERNAL.raw.toString(),
                            it.toString()
                        )
                    )
                )
            }
        }
    }

    private fun getGeofencePendingIndent(context: Context): PendingIntent {
        val intent = Intent(context, NativeGeofenceBroadcastReceiver::class.java)
        val requestCode = "NativeGeofence".hashCode()

//        We gaan 1 intent gebruiken voor alle events. De callbackHandle wordt al uit storage gehaald!
//        Extras maken een intent uniek.
//        if (callbackHandle != null) {
//            intent.putExtra(Constants.CALLBACK_HANDLE_KEY, callbackHandle)
//        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
    @SuppressLint("MissingPermission")
    private fun createGeofenceHelper(
        geofence: Geofence,
        isNew: Boolean,
        callback: ((Result<Unit>) -> Unit)?
    ) {
        val geofenceStorage = if(isNew) {
            // If it's a new geofence, store it with PENDING status first.
            val storage = GeofenceStorage.fromApi(geofence)
            storage.status = GeofenceStatus.PENDING
            NativeGeofencePersistence.saveOrUpdateGeofence(context, storage)
            storage
        } else {
            // This is a re-creation (e.g. after reboot), the storage entry already exists.
            GeofenceStorage.fromApi(geofence)
        }

        // We try to create the Geofence without checking for permissions.
        // Only if creation fails we will alert the Flutter plugin of the permission issue.
        geofencingClient.addGeofences(
            GeofencingRequest.Builder().apply {
                setInitialTrigger(GeofenceEvents.createMask(geofence.androidSettings.initialTriggers))
                addGeofence(geofence.toGeofence(context))
            }.build(),
            getGeofencePendingIndent(context)
        ).run {
            addOnSuccessListener {
                // Note: Google's addGeofences API can return success even when the geofence
                // is not actually registered due to system constraints (e.g., location services
                // disabled, battery optimization, etc.). Therefore, we keep the status as PENDING
                // and only update to ACTIVE when we receive an actual geofence event.
                // The status will remain PENDING until the system can successfully register it.
                log.d("API call succeeded for Geofence ID=${geofence.id}, status remains PENDING until first trigger.")
                callback?.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                log.e("Failed to add Geofence ID=${geofence.id}", it)

                // unconditionally set status to FAILED on failure
                val failedGeofence = geofenceStorage
                failedGeofence.status = GeofenceStatus.FAILED
                failedGeofence.statusChangedAtMillis = System.currentTimeMillis()
                NativeGeofencePersistence.saveOrUpdateGeofence(context, failedGeofence)

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    log.e("Lacking permission: ACCESS_FINE_LOCATION")
                    callback?.invoke(
                        Result.failure(
                            FlutterError(
                                NativeGeofenceErrorCode.MISSING_LOCATION_PERMISSION.raw.toString(),
                                "The ACCESS_FINE_LOCATION needs to be granted in order to setup geofences."
                            )
                        )
                    )
                    return@addOnFailureListener
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        log.e("Running on API ${Build.VERSION.SDK_INT} and lacking permission: ACCESS_BACKGROUND_LOCATION")
                        callback?.invoke(
                            Result.failure(
                                FlutterError(
                                    NativeGeofenceErrorCode.MISSING_BACKGROUND_LOCATION_PERMISSION.raw.toString(),
                                    "The ACCESS_BACKGROUND_LOCATION needs to be granted in order to setup geofences.",
                                    "Running on Android API ${Build.VERSION.SDK_INT}."
                                )
                            )
                        )
                        return@addOnFailureListener
                    }
                }

                callback?.invoke(
                    Result.failure(
                        FlutterError(
                            NativeGeofenceErrorCode.PLUGIN_INTERNAL.raw.toString(),
                            it.toString()
                        )
                    )
                )
            }
        }
    }
}

private fun Geofence.toGeofence(context: Context): com.google.android.gms.location.Geofence {

    val broadcastIntent = Intent(context, NativeGeofenceBroadcastReceiver::class.java)
    broadcastIntent.putExtra(Constants.CALLBACK_HANDLE_KEY, callbackHandle)

    return com.google.android.gms.location.Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(
            location.latitude,
            location.longitude,
            radiusMeters.toFloat()
        )
        .setExpirationDuration(androidSettings.expirationDurationMillis ?: -1)
        .setTransitionTypes(GeofenceEvents.createMask(triggers))
        .setLoiteringDelay(androidSettings.loiteringDelayMillis.toInt())
        .setNotificationResponsiveness((androidSettings.notificationResponsivenessMillis ?: 0).toInt())
        .build()
}
