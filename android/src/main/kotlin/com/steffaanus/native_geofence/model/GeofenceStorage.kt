package com.Steffaanus.native_geofence.model

import com.Steffaanus.native_geofence.generated.GeofenceEvent
import com.Steffaanus.native_geofence.generated.GeofenceWire
import kotlinx.serialization.Serializable

@Serializable
class GeofenceStorage(
    val id: String,
    val location: LocationStorage,
    val radiusMeters: Double,
    val triggers: List<GeofenceEvent>,
    val iosSettings: IosGeofenceSettingsStorage,
    val androidSettings: AndroidGeofenceSettingsStorage,
    val callbackHandle: Long,
    var status: GeofenceStatus = GeofenceStatus.PENDING,
) {
    companion object {
        fun fromWire(e: GeofenceWire): GeofenceStorage {
            return GeofenceStorage(
                e.id,
                LocationStorage.fromWire(e.location),
                e.radiusMeters,
                e.triggers,
                IosGeofenceSettingsStorage.fromWire(e.iosSettings),
                AndroidGeofenceSettingsStorage.fromWire(e.androidSettings),
                e.callbackHandle,
                GeofenceStatus.PENDING,
            )
        }
    }

    fun toWire(): GeofenceWire {
        return GeofenceWire(
            id,
            location.toWire(),
            radiusMeters,
            triggers,
            iosSettings.toWire(),
            androidSettings.toWire(),
            callbackHandle
        )
    }
}
