import CoreLocation

class ActiveGeofenceWires {
    static func fromGeofence(_ geofence: Geofence) -> ActiveGeofence {
        let currentTimeMillis = Int64(Date().timeIntervalSince1970 * 1000)
        return ActiveGeofence(
            id: geofence.id,
            location: geofence.location,
            radiusMeters: geofence.radiusMeters,
            triggers: geofence.triggers,
            androidSettings: geofence.androidSettings,
            status: .active,
            createdAtMillis: currentTimeMillis,
            statusChangedAtMillis: currentTimeMillis
        )
    }

    static func fromRegion(_ region: CLRegion) -> ActiveGeofence? {
        guard let circularRegion = region as? CLCircularRegion else { return nil }
        
        // Normalize coordinates from iOS CLRegion for consistency
        let normalizedLocation = Location(
            latitude: circularRegion.center.latitude,
            longitude: circularRegion.center.longitude
        ).normalized()
        
        let currentTimeMillis = Int64(Date().timeIntervalSince1970 * 1000)
        return ActiveGeofence(
            id: circularRegion.identifier,
            location: normalizedLocation,
            radiusMeters: circularRegion.radius,
            triggers: [
                circularRegion.notifyOnEntry ? .enter : nil,
                circularRegion.notifyOnExit ? .exit : nil
            ].compactMap { $0 },
            androidSettings: nil,
            status: .active,
            createdAtMillis: currentTimeMillis,
            statusChangedAtMillis: currentTimeMillis
        )
    }
}
