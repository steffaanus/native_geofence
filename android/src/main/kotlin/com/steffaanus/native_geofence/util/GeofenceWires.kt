package com.steffaanus.native_geofence.util

import com.steffaanus.native_geofence.generated.GeofenceWire
import com.steffaanus.native_geofence.model.GeofenceStatus
import com.steffaanus.native_geofence.model.GeofenceStorage
import com.steffaanus.native_geofence.model.LegacyGeofenceStorage
import com.google.android.gms.location.Geofence

class GeofenceWires {
    companion object {
        fun toGeofenceStorage(e: GeofenceWire): GeofenceStorage {
            return GeofenceStorage(
                id = e.id,
                status = GeofenceStatus.UNKNOWN,
                callbackHandle = e.callbackHandle,
                location = LocationWires.toStorage(e.location),
                radiusMeters = e.radiusMeters,
                triggers = e.triggers,
                androidSettings = AndroidGeofenceSettingsWires.toStorage(e.androidSettings),
            )
        }
        
        fun toGeofenceStorage(e: LegacyGeofenceStorage): GeofenceStorage {
            return GeofenceStorage(
                id = e.id,
                status = GeofenceStatus.UNKNOWN,
                callbackHandle = e.callbackHandle,
                location = e.location.toLocationStorage(),
                radiusMeters = e.radius,
                triggers = e.trigger,
                androidSettings = AndroidGeofenceSettingsWires.toStorage(e.androidSettings),
            )
        }

        fun toGeofence(e: GeofenceWire): Geofence {
            val geofenceBuilder = Geofence.Builder()
                .setRequestId(e.id)
                .setCircularRegion(
                    e.location.latitude,
                    e.location.longitude,
                    e.radiusMeters.toFloat()
                )
                .setTransitionTypes(GeofenceEvents.createMask(e.triggers))
                .setLoiteringDelay(e.androidSettings.loiteringDelayMillis.toInt())
            if (e.androidSettings.expirationDurationMillis != null) {
                geofenceBuilder.setExpirationDuration(e.androidSettings.expirationDurationMillis)
            }
            if (e.androidSettings.notificationResponsivenessMillis != null) {
                geofenceBuilder.setNotificationResponsiveness(e.androidSettings.notificationResponsivenessMillis.toInt())
            }
            return geofenceBuilder.build()
        }
    }
}
