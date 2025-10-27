package com.steffaanus.native_geofence.util

import com.steffaanus.native_geofence.generated.GeofenceWire
import com.steffaanus.native_geofence.model.GeofenceStatus
import com.steffaanus.native_geofence.model.GeofenceStorage
import com.steffaanus.native_geofence.model.LegacyGeofenceStorage
import com.steffaanus.native_geofence.model.LocationStorage
import com.steffaanus.native_geofence.model.IosGeofenceSettingsStorage
import com.steffaanus.native_geofence.model.AndroidGeofenceSettingsStorage
import com.google.android.gms.location.Geofence

class GeofenceWires {
    companion object {
        fun toGeofenceStorage(e: GeofenceWire): GeofenceStorage {
            return GeofenceStorage(
                id = e.id,
                status = GeofenceStatus.PENDING,
                callbackHandle = e.callbackHandle,
                location = LocationStorage.fromWire(e.location),
                radiusMeters = e.radiusMeters,
                triggers = e.triggers,
                iosSettings = IosGeofenceSettingsStorage.fromWire(e.iosSettings),
                androidSettings = AndroidGeofenceSettingsStorage.fromWire(e.androidSettings),
            )
        }
        
        fun toGeofenceStorage(e: LegacyGeofenceStorage): GeofenceStorage {
            return GeofenceStorage(
                id = e.id,
                status = GeofenceStatus.PENDING,
                callbackHandle = e.callbackHandle,
                location = e.location,
                radiusMeters = e.radiusMeters,
                triggers = e.triggers,
                iosSettings = e.iosSettings,
                androidSettings = e.androidSettings,
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
