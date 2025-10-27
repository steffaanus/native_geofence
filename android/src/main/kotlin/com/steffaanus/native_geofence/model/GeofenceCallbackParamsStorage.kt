package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.GeofenceCallbackParamsWire
import com.steffaanus.native_geofence.generated.GeofenceEvent
import com.steffaanus.native_geofence.generated.ActiveGeofenceWire
import com.steffaanus.native_geofence.generated.LocationWire
import kotlinx.serialization.Serializable

@Serializable
class GeofenceCallbackParamsStorage(
    private val geofences: List<ActiveGeofenceStorage>,
    private val event: GeofenceEvent,
    private val location: LocationStorage? = null,
    private val callbackHandle: Long
) {
    companion object {
        fun fromWire(e: GeofenceCallbackParamsWire): GeofenceCallbackParamsStorage {
            return GeofenceCallbackParamsStorage(
                e.geofences.map { it: ActiveGeofenceWire -> ActiveGeofenceStorage.fromWire(it) }.toList(),
                e.event,
                e.location?.let { it: LocationWire -> LocationStorage.fromWire(it) },
                e.callbackHandle,
            )
        }
    }

    fun toWire(): GeofenceCallbackParamsWire {
        return GeofenceCallbackParamsWire(
            geofences.map { it.toWire() }.toList(),
            event,
            location?.toWire(),
            callbackHandle,
        )
    }
}
