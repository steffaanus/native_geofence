package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.IosGeofenceSettings
import kotlinx.serialization.Serializable

@Serializable
class IosGeofenceSettingsStorage(
    private val initialTrigger: Boolean
) {
    companion object {
        fun fromApi(e: IosGeofenceSettings): IosGeofenceSettingsStorage {
            return IosGeofenceSettingsStorage(
                e.initialTrigger
            )
        }
    }

    fun toApi(): IosGeofenceSettings {
        return IosGeofenceSettings(
            initialTrigger
        )
    }
}
