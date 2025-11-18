package com.steffaanus.native_geofence.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.NativeGeofenceBackgroundWorker
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
        val geofenceCallbackParams = getGeofenceCallbackParams(context, geofencingEvent) ?: return
        val jsonData = Json.encodeToString(geofenceCallbackParams)
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
    }

    private fun getGeofenceCallbackParams(
        context: Context,
        geofencingEvent: GeofencingEvent
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

        val geofence =
            geofencingEvent.triggeringGeofences?.firstNotNullOfOrNull {
                NativeGeofencePersistence.getGeofence(
                    context,
                    it.requestId
                )
            }

        if (geofence == null) {
            Log.e(TAG, "No geofence found for triggering geofences.")
            return null
        }

        val location = geofencingEvent.triggeringLocation
        if (location == null) {
            Log.w(TAG, "No triggering location found.")
        }

        val callbackHandle = context.getSharedPreferences(
            Constants.SHARED_PREFERENCES_KEY,
            Context.MODE_PRIVATE
        ).getLong(Constants.CALLBACK_DISPATCHER_HANDLE_KEY, 0L)

        return GeofenceCallbackParams(
            geofences = listOf(geofence.toActiveGeofence()),
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
