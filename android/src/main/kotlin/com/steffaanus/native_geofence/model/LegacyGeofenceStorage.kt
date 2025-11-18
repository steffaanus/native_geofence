package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.AndroidGeofenceSettings
import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.GeofenceEvent
import com.steffaanus.native_geofence.generated.IosGeofenceSettings
import com.steffaanus.native_geofence.generated.Location
import kotlinx.serialization.Serializable

@Serializable
data class LegacyGeofenceStorage(
    val id: String,
    val location: Location,
    val radiusMeters: Double,
    val triggers: List<GeofenceEvent>,
    val androidSettings: AndroidGeofenceSettings,
    val iosSettings: IosGeofenceSettings,
    val callbackHandle: Long,
) {
    fun toGeofence(): Geofence {
        return Geofence(
            id = id,
            location = location,
            radiusMeters = radiusMeters,
            triggers = triggers,
            androidSettings = androidSettings,
            iosSettings = iosSettings,
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
            status = GeofenceStatus.ACTIVE,
        )
    }
}