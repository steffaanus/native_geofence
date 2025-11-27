import CoreLocation

class ActiveGeofenceWires {
    static func fromGeofence(_ geofence: Geofence) -> ActiveGeofence {
        return ActiveGeofence(
            id: geofence.id,
            location: geofence.location,
            radiusMeters: geofence.radiusMeters,
            triggers: geofence.triggers,
            androidSettings: geofence.androidSettings,
            status: .active
        )
    }

    static func fromRegion(_ region: CLRegion) -> ActiveGeofence? {
        guard let circularRegion = region as? CLCircularRegion else { return nil }
        
        // Normalize coordinates from iOS CLRegion for consistency
        let normalizedLocation = Location(
            latitude: circularRegion.center.latitude,
            longitude: circularRegion.center.longitude
        ).normalized()
        
        return ActiveGeofence(
            id: circularRegion.identifier,
            location: normalizedLocation,
            radiusMeters: circularRegion.radius,
            triggers: [
                circularRegion.notifyOnEntry ? .enter : nil,
                circularRegion.notifyOnExit ? .exit : nil
            ].compactMap { $0 },
            androidSettings: nil,
            status: .active
        )
    }
}
