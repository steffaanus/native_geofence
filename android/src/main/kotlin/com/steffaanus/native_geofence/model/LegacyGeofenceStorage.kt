package com.Steffaanus.native_geofence.model

import com.Steffaanus.native_geofence.generated.GeofenceEvent
import kotlinx.serialization.Serializable

/**
 * Represents the old storage format for a geofence, used for backward compatibility.
 */
@Serializable
data class LegacyGeofenceStorage(
    private val id: String,
    private val location: LocationStorage,
    private val radiusMeters: Double,
    private val triggers: List<GeofenceEvent>,
    private val iosSettings: IosGeofenceSettingsStorage,
    private val androidSettings: AndroidGeofenceSettingsStorage,
    private val callbackHandle: Long
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