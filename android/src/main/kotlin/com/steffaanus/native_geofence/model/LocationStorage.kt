package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.Location
import kotlinx.serialization.Serializable

@Serializable
class LocationStorage(
    private val latitude: Double,
    private val longitude: Double
) {
    companion object {
        fun fromApi(e: Location): LocationStorage {
            return LocationStorage(e.latitude, e.longitude)
        }
    }

    fun toApi(): Location {
        return Location(
            latitude,
            longitude,
        )
    }
}
