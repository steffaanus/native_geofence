package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.GeofenceEvent
import kotlinx.serialization.Serializable
import com.steffaanus.native_geofence.generated.GeofenceStatus as ApiGeofenceStatus

@Serializable
data class LegacyGeofenceStorage(
    val id: String,
    val location: LocationStorage,
    val radiusMeters: Double,
    val triggers: List<GeofenceEvent>,
    val androidSettings: AndroidGeofenceSettingsStorage,
    val iosSettings: IosGeofenceSettingsStorage,
    val callbackHandle: Long,
) {
    fun toGeofence(): Geofence {
        return Geofence(
            id = id,
            location = location.toApi(),
            radiusMeters = radiusMeters,
            triggers = triggers,
            androidSettings = androidSettings.toApi(),
            iosSettings = iosSettings.toApi(),
            callbackHandle = callbackHandle,
        )
    }

    fun toGeofenceStorage(): GeofenceStorage {
        return GeofenceStorage(
            id = id,
            location = location,
            radiusMeters = radiusMeters,
            triggers = triggers,
            androidSettings = androidSettings,
            iosSettings = iosSettings,
            callbackHandle = callbackHandle,
            status = ApiGeofenceStatus.ACTIVE,
        )
    }
}