package com.steffaanus.native_geofence.api

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.steffaanus.native_geofence.model.GeofenceStorage
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.generated.ActiveGeofence
import com.steffaanus.native_geofence.generated.FlutterError
import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.GeofenceStatus
import com.steffaanus.native_geofence.generated.NativeGeofenceApi
import com.steffaanus.native_geofence.generated.NativeGeofenceErrorCode
import com.steffaanus.native_geofence.util.GeofenceEvents
import com.steffaanus.native_geofence.receivers.NativeGeofenceBroadcastReceiver
import com.steffaanus.native_geofence.util.NativeGeofencePersistence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class NativeGeofenceApiImpl(private val context: Context) : NativeGeofenceApi {
    companion object {
        @JvmStatic
        private val TAG = "NativeGeofenceApiImpl"
    }

    private var lastSyncTime = 0L
    private val SYNC_DEBOUNCE_MS = 5000L // 5 seconden
    private val geofencingClient = LocationServices.getGeofencingClient(context)

    override fun initialize(callbackDispatcherHandle: Long) {
        context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.CALLBACK_DISPATCHER_HANDLE_KEY, callbackDispatcherHandle)
            .apply()
        Log.d(TAG, "Initialized NativeGeofenceApi.")
        syncGeofences()
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

    internal fun syncGeofences() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < SYNC_DEBOUNCE_MS) {
            Log.d(TAG, "Sync skipped - too soon after last sync")
            return
        }
        lastSyncTime = currentTime

        val geofences = NativeGeofencePersistence.getAllGeofences(context)
        for (geofence in geofences) {
            // First remove the geofence if it exists, then re-create it.
            // This prevents errors when geofences already exist in the system.
            // Re-create ACTIVE geofences and re-try PENDING/FAILED ones.
            geofencingClient.removeGeofences(listOf(geofence.id)).run {
                addOnSuccessListener {
                    createGeofenceHelper(geofence.toApi(), false, null)
                }
                addOnFailureListener {
                    // If the geofence does not exist, this call will fail.
                    // In that case, we can ignore the error and just create the geofence.
                    createGeofenceHelper(geofence.toApi(), false, null)
                }
            }
        }
        Log.d(TAG, "${geofences.size} geofences sync initiated.")
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
                Log.d(TAG, "Removed Geofence ID=$id.")
                callback.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                val existingIds = NativeGeofencePersistence.getAllGeofenceIds(context)
                val errorCode =
                    if (existingIds.contains(id)) NativeGeofenceErrorCode.PLUGIN_INTERNAL else NativeGeofenceErrorCode.GEOFENCE_NOT_FOUND
                Log.e(TAG, "Failure when removing Geofence ID=$id: $it")
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
                Log.d(TAG, "Removed all geofences (if any).")
                callback.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                Log.e(TAG, "Failed to remove all geofences: $it")
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
                Log.d(TAG, "API call succeeded for Geofence ID=${geofence.id}, status remains PENDING until first trigger.")
                callback?.invoke(Result.success(Unit))
            }
            addOnFailureListener {
                Log.e(TAG, "Failed to add Geofence ID=${geofence.id}: $it")

                // unconditionally set status to FAILED on failure
                val failedGeofence = geofenceStorage
                failedGeofence.status = GeofenceStatus.FAILED
                NativeGeofencePersistence.saveOrUpdateGeofence(context, failedGeofence)

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Lacking permission: ACCESS_FINE_LOCATION")
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
                        Log.e(TAG, "Running on API ${Build.VERSION.SDK_INT} and lacking permission: ACCESS_BACKGROUND_LOCATION")
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
        .build()
}
