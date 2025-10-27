package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.GeofenceEvent
import kotlinx.serialization.Serializable

/**
 * Represents the old storage format for a geofence, used for backward compatibility.
 */
@Serializable
data class LegacyGeofenceStorage(
    val id: String,
    val location: LocationStorage,
    val radiusMeters: Double,
    val triggers: List<GeofenceEvent>,
    val iosSettings: IosGeofenceSettingsStorage,
    val androidSettings: AndroidGeofenceSettingsStorage,
    val callbackHandle: Long
) {
    fun toGeofenceStorage(): GeofenceStorage {
        return GeofenceStorage(
            id = id,
            location = location,
            radiusMeters = radiusMeters,
            triggers = triggers,
            iosSettings = iosSettings,
            androidSettings = androidSettings,
            callbackHandle = callbackHandle,
            status = GeofenceStatus.ACTIVE // Assume old geofences are active
        )
    }
}