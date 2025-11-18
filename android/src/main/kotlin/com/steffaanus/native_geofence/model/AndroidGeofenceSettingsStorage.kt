package com.steffaanus.native_geofence.model

import com.steffaanus.native_geofence.generated.AndroidGeofenceSettings
import com.steffaanus.native_geofence.generated.GeofenceEvent
import kotlinx.serialization.Serializable

@Serializable
class AndroidGeofenceSettingsStorage(
    private val initialTriggers: List<GeofenceEvent>,
    private val expirationDurationMillis: Long? = null,
    private val loiteringDelayMillis: Long,
    private val notificationResponsivenessMillis: Long? = null
) {
    companion object {
        fun fromApi(e: AndroidGeofenceSettings): AndroidGeofenceSettingsStorage {
            return AndroidGeofenceSettingsStorage(
                e.initialTriggers,
                e.expirationDurationMillis,
                e.loiteringDelayMillis,
                e.notificationResponsivenessMillis
            )
        }
    }

    fun toApi(): AndroidGeofenceSettings {
        return AndroidGeofenceSettings(
            initialTriggers,
            expirationDurationMillis,
            loiteringDelayMillis,
            notificationResponsivenessMillis
        )
    }
}
