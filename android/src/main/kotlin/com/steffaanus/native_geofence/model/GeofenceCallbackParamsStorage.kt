package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.ActiveGeofence
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.generated.GeofenceEvent
import com.steffaanus.native_geofence.generated.Location
import kotlinx.serialization.Serializable

@Serializable
class GeofenceCallbackParamsStorage(
    private val geofences: List<ActiveGeofenceStorage>,
    private val event: GeofenceEvent,
    private val location: LocationStorage? = null,
    private val callbackHandle: Long
) {
    companion object {
        fun fromApi(e: GeofenceCallbackParams): GeofenceCallbackParamsStorage {
            return GeofenceCallbackParamsStorage(
                e.geofences.mapNotNull { it?.let { it1 -> ActiveGeofenceStorage.fromApi(it1) } }
                    .toList(),
                e.event,
                e.location?.let { it: Location -> LocationStorage.fromApi(it) },
                e.callbackHandle,
            )
        }
    }

    fun toApi(): GeofenceCallbackParams {
        return GeofenceCallbackParams(
            geofences.map { it.toApi() }.toList(),
            event,
            location?.toApi(),
            callbackHandle,
        )
    }
}
