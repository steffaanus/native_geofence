import '../generated/native_geofence_api.g.dart' show Location;

/// Extension for Location to provide coordinate normalization
extension LocationNormalization on Location {
  /// Creates a normalized Location with coordinates rounded to 6 decimal places.
  ///
  /// 6 decimals = ~11cm precision, which matches iOS's CLCircularRegion
  /// internal precision and is sufficient for geofencing since GPS accuracy
  /// is typically ±5-10 meters.
  ///
  /// Example:
  /// ```dart
  /// final loc = Location(latitude: 53.164677890945015, longitude: 5.445930351661604);
  /// final normalized = loc.normalized();
  /// // normalized.latitude = 53.164678
  /// // normalized.longitude = 5.445930
  /// ```
  Location normalized() {
    return Location(
      latitude: _normalize(latitude),
      longitude: _normalize(longitude),
    );
  }

  /// Check if two locations are equal within 6 decimal precision
  bool equalsNormalized(Location other) {
    return _normalize(latitude) == _normalize(other.latitude) &&
        _normalize(longitude) == _normalize(other.longitude);
  }

  /// Normalizes coordinate to 6 decimal places
  static double _normalize(double value) {
    return (value * 1000000).roundToDouble() / 1000000;
  }
}

/// Factory extension to create normalized locations directly
extension LocationFactory on Location {
  /// Creates a Location with automatically normalized coordinates
  static Location normalized({
    required double latitude,
    required double longitude,
  }) {
    return Location(
      latitude: LocationNormalization._normalize(latitude),
      longitude: LocationNormalization._normalize(longitude),
    );
  }
}
