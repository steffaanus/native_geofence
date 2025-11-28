package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.GeofenceEvent
import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.ActiveGeofence
import kotlinx.serialization.Serializable
import com.steffaanus.native_geofence.generated.GeofenceStatus as ApiGeofenceStatus

@Serializable
class GeofenceStorage(
    val id: String,
    val location: LocationStorage,
    val radiusMeters: Double,
    val triggers: List<GeofenceEvent>,
    val iosSettings: IosGeofenceSettingsStorage,
    val androidSettings: AndroidGeofenceSettingsStorage,
    val callbackHandle: Long,
    var status: ApiGeofenceStatus = ApiGeofenceStatus.PENDING,
    var createdAtMillis: Long = System.currentTimeMillis(),
    var statusChangedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromApi(e: Geofence): GeofenceStorage {
            val currentTime = System.currentTimeMillis()
            return GeofenceStorage(
                e.id,
                LocationStorage.fromApi(e.location),
                e.radiusMeters,
                e.triggers.map { it },
                IosGeofenceSettingsStorage.fromApi(e.iosSettings),
                AndroidGeofenceSettingsStorage.fromApi(e.androidSettings),
                e.callbackHandle,
                ApiGeofenceStatus.PENDING,
                currentTime,
                currentTime,
            )
        }
    }

    fun toApi(): Geofence {
        return Geofence(
            id,
            location.toApi(),
            radiusMeters,
            triggers.map { it },
            iosSettings.toApi(),
            androidSettings.toApi(),
            callbackHandle,
        )
    }

    fun toActiveGeofence(): ActiveGeofence {
        return ActiveGeofence(
            id,
            location.toApi(),
            radiusMeters,
            triggers.toList(),
            androidSettings.toApi(),
            status,
            createdAtMillis,
            statusChangedAtMillis,
        )
    }
}
