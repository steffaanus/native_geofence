import 'package:flutter_test/flutter_test.dart';
import 'package:native_geofence/native_geofence.dart';

void main() {
  group('Location Normalization', () {
    test('normalizes coordinates to 6 decimals', () {
      final location = Location(
        latitude: 53.164677890945015,
        longitude: 5.445930351661604,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, 53.164678);
      expect(normalized.longitude, 5.445930);
    });

    test('handles already normalized coordinates', () {
      final location = Location(
        latitude: 53.164678,
        longitude: 5.445930,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, 53.164678);
      expect(normalized.longitude, 5.445930);
    });

    test('handles negative coordinates', () {
      final location = Location(
        latitude: -33.8688197,
        longitude: 151.2092955,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, -33.86882);
      expect(normalized.longitude, 151.209296);
    });

    test('handles coordinates near zero', () {
      final location = Location(
        latitude: 0.0000001,
        longitude: -0.0000001,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, 0.0);
      expect(normalized.longitude, 0.0);
    });

    test('handles maximum valid latitude', () {
      final location = Location(
        latitude: 89.999999999,
        longitude: 179.999999999,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, 90.0);
      expect(normalized.longitude, 180.0);
    });

    test('handles minimum valid latitude', () {
      final location = Location(
        latitude: -89.999999999,
        longitude: -179.999999999,
      );

      final normalized = location.normalized();

      expect(normalized.latitude, -90.0);
      expect(normalized.longitude, -180.0);
    });

    test('equalsNormalized returns true for similar coordinates', () {
      final loc1 = Location(
        latitude: 53.1646784999,
        longitude: 5.4459304999,
      );
      final loc2 = Location(
        latitude: 53.1646775001,
        longitude: 5.4459295001,
      );

      // Both round to 53.164678 and 5.44593
      expect(loc1.equalsNormalized(loc2), isTrue);
    });

    test('equalsNormalized returns false for different coordinates', () {
      final loc1 = Location(
        latitude: 53.164678,
        longitude: 5.445930,
      );
      final loc2 = Location(
        latitude: 53.164679,
        longitude: 5.445930,
      );

      expect(loc1.equalsNormalized(loc2), isFalse);
    });

    test('normalization is idempotent', () {
      final location = Location(
        latitude: 53.164677890945015,
        longitude: 5.445930351661604,
      );

      final firstNormalization = location.normalized();
      final secondNormalization = firstNormalization.normalized();

      expect(firstNormalization.latitude, secondNormalization.latitude);
      expect(firstNormalization.longitude, secondNormalization.longitude);
    });

    test('precision is exactly 6 decimals', () {
      final location = Location(
        latitude: 12.3456789,
        longitude: 98.7654321,
      );

      final normalized = location.normalized();

      // Check that we have exactly 6 decimals by string representation
      final latString = normalized.latitude.toStringAsFixed(6);
      final lonString = normalized.longitude.toStringAsFixed(6);

      expect(latString, '12.345679');
      expect(lonString, '98.765432');
    });

    test('LocationFactory.normalized creates normalized location directly', () {
      final normalized = LocationFactory.normalized(
        latitude: 53.164677890945015,
        longitude: 5.445930351661604,
      );

      expect(normalized.latitude, 53.164678);
      expect(normalized.longitude, 5.445930);
    });
  });

  group('Precision Impact', () {
    test('6 decimals provides ~11cm precision', () {
      // At equator, 1 degree latitude ≈ 111km
      // 0.000001 degrees = 111km / 1,000,000 ≈ 0.111m = 11.1cm
      final location1 = Location(latitude: 0.0, longitude: 0.0);
      final location2 = Location(latitude: 0.0000004, longitude: 0.0);

      // Both should normalize to same value (0.0)
      // 0.0000004 rounds down to 0.000000
      expect(location1.equalsNormalized(location2), isTrue);
    });

    test('difference of 0.000001 degrees is detectable', () {
      final location1 = Location(latitude: 0.0, longitude: 0.0);
      final location2 = Location(latitude: 0.000001, longitude: 0.0);

      // This difference should be detectable
      expect(location1.equalsNormalized(location2), isFalse);
    });
  });

  group('ActiveGeofence isEqual', () {
    test('returns true for matching normalized parameters', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: 53.164678,
          longitude: 5.445930,
        ),
        radiusMeters: 100.0,
        triggers: [GeofenceEvent.enter],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      // Test with unnormalized input that normalizes to same values
      expect(
        geofence.isEqual(
          latitude: 53.164677890945015,
          longitude: 5.445930351661604,
          radiusMeters: 100.0,
        ),
        isTrue,
      );
    });

    test('returns false for different latitude', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: 53.164678,
          longitude: 5.445930,
        ),
        radiusMeters: 100.0,
        triggers: [GeofenceEvent.enter],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      expect(
        geofence.isEqual(
          latitude: 53.164679, // Different after normalization
          longitude: 5.445930,
          radiusMeters: 100.0,
        ),
        isFalse,
      );
    });

    test('returns false for different longitude', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: 53.164678,
          longitude: 5.445930,
        ),
        radiusMeters: 100.0,
        triggers: [GeofenceEvent.enter],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      expect(
        geofence.isEqual(
          latitude: 53.164678,
          longitude: 5.445931, // Different after normalization
          radiusMeters: 100.0,
        ),
        isFalse,
      );
    });

    test('returns false for different radius', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: 53.164678,
          longitude: 5.445930,
        ),
        radiusMeters: 100.0,
        triggers: [GeofenceEvent.enter],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      expect(
        geofence.isEqual(
          latitude: 53.164678,
          longitude: 5.445930,
          radiusMeters: 200.0, // Different radius
        ),
        isFalse,
      );
    });

    test('normalizes both geofence and input coordinates', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: 53.164677890945015, // Unnormalized
          longitude: 5.445930351661604, // Unnormalized
        ),
        radiusMeters: 100.0,
        triggers: [GeofenceEvent.enter],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      // Both should normalize to 53.164678 and 5.445930
      expect(
        geofence.isEqual(
          latitude: 53.1646784999,
          longitude: 5.4459304999,
          radiusMeters: 100.0,
        ),
        isTrue,
      );
    });

    test('handles negative coordinates', () {
      final geofence = ActiveGeofence(
        id: 'test-1',
        location: Location(
          latitude: -33.8688197,
          longitude: 151.2092955,
        ),
        radiusMeters: 50.0,
        triggers: [GeofenceEvent.enter, GeofenceEvent.exit],
        androidSettings: null,
        status: GeofenceStatus.active,
      );

      expect(
        geofence.isEqual(
          latitude: -33.868819712345,
          longitude: 151.209295687654,
          radiusMeters: 50.0,
        ),
        isTrue,
      );
    });
  });
}
