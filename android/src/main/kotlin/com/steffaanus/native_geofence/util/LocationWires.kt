package com.steffaanus.native_geofence.util

import android.location.Location
import com.steffaanus.native_geofence.generated.LocationWire

class LocationWires {
    companion object {
        fun fromLocation(e: Location): LocationWire {
            return LocationWire(
                e.latitude,
                e.longitude
            )
        }
    }
}
