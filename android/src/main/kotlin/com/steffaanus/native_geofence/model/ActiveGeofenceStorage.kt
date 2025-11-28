package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.ActiveGeofence
import com.steffaanus.native_geofence.generated.GeofenceEvent
import com.steffaanus.native_geofence.generated.GeofenceStatus
import kotlinx.serialization.Serializable

@Serializable
class ActiveGeofenceStorage(
    private val id: String,
    private val location: LocationStorage,
    private val radiusMeters: Double,
    private val triggers: List<GeofenceEvent>,
    private val androidSettings: AndroidGeofenceSettingsStorage?,
    private val status: GeofenceStatus,
    private val createdAtMillis: Long,
    private val statusChangedAtMillis: Long,
) {
    companion object {
        fun fromApi(e: ActiveGeofence): ActiveGeofenceStorage {
            return ActiveGeofenceStorage(
                e.id,
                LocationStorage.fromApi(e.location),
                e.radiusMeters,
                e.triggers,
                e.androidSettings?.let { AndroidGeofenceSettingsStorage.fromApi(it) },
                e.status,
                e.createdAtMillis,
                e.statusChangedAtMillis,
            )
        }
    }

    fun toApi(): ActiveGeofence {
        return ActiveGeofence(
            id,
            location.toApi(),
            radiusMeters,
            triggers.map { it },
            androidSettings?.toApi(),
            status,
            createdAtMillis,
            statusChangedAtMillis,
        )
    }
}
