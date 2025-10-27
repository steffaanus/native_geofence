package com.Steffaanus.native_geofence.util

import android.location.Location
import com.Steffaanus.native_geofence.generated.LocationWire

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
