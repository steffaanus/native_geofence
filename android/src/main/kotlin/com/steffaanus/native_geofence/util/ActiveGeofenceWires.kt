package com.steffaanus.native_geofence.util

import com.steffaanus.native_geofence.generated.ActiveGeofence
import com.steffaanus.native_geofence.generated.AndroidGeofenceSettings
import com.steffaanus.native_geofence.generated.Geofence
import com.steffaanus.native_geofence.generated.GeofenceStatus
import com.steffaanus.native_geofence.generated.Location
import com.google.android.gms.location.Geofence

class ActiveGeofenceWires {
    companion object {
        fun fromGeofence(e: Geofence): ActiveGeofence {
            return ActiveGeofence(
                e.id,
                e.location,
                e.radiusMeters,
                e.triggers,
                e.androidSettings,
                GeofenceStatus.ACTIVE
            )
        }

        fun fromGeofence(e: Geofence, status: GeofenceStatus): ActiveGeofence {
            return ActiveGeofence(
                e.id,
                e.location,
                e.radiusMeters,
                e.triggers,
                e.androidSettings,
                status,
            )
        }
    }
}
