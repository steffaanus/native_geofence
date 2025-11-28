import 'package:pigeon/pigeon.dart';

// After modifying this file run:
// flutter pub run pigeon --input pigeons/native_geofence_api.dart && dart format .

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/generated/native_geofence_api.g.dart',
  dartPackageName: 'native_geofence',
  swiftOut: 'ios/Classes/Generated/NativeGeofenceApi.g.swift',
  kotlinOut:
      'android/src/main/kotlin/com/steffaanus/native_geofence/generated/NativeGeofenceApi.g.kt',
  kotlinOptions:
      KotlinOptions(package: 'com.steffaanus.native_geofence.generated'),
))

/// Geofencing events.
///
/// See the helpful illustration at:
/// https://developer.android.com/develop/sensors-and-location/location/geofencing
enum GeofenceEvent {
  enter,
  exit,

  /// Not supported on iOS.
  dwell,
}

enum GeofenceStatus {
  pending,
  active,
  failed,
}

class Location {
  final double latitude;
  final double longitude;

  Location({required this.latitude, required this.longitude});
}

class IosGeofenceSettings {
  final bool initialTrigger;

  IosGeofenceSettings({
    required this.initialTrigger,
  });
}

class AndroidGeofenceSettings {
  final List<GeofenceEvent> initialTriggers;
  final int? expirationDurationMillis;
  final int loiteringDelayMillis;
  final int? notificationResponsivenessMillis;

  AndroidGeofenceSettings({
    required this.initialTriggers,
    this.expirationDurationMillis,
    required this.loiteringDelayMillis,
    this.notificationResponsivenessMillis,
  });
}

class Geofence {
  final String id;
  final Location location;
  final double radiusMeters;
  final List<GeofenceEvent> triggers;
  final IosGeofenceSettings iosSettings;
  final AndroidGeofenceSettings androidSettings;
  final int callbackHandle;

  Geofence(
      {required this.id,
      required this.location,
      required this.radiusMeters,
      required this.triggers,
      required this.iosSettings,
      required this.androidSettings,
      required this.callbackHandle});
}

class ActiveGeofence {
  final String id;
  final Location location;
  final double radiusMeters;
  final List<GeofenceEvent> triggers;

  final AndroidGeofenceSettings? androidSettings;

  final GeofenceStatus status;

  /// Timestamp (milliseconds since epoch) when this geofence was created.
  final int createdAtMillis;

  /// Timestamp (milliseconds since epoch) of the last status change.
  final int statusChangedAtMillis;

  ActiveGeofence({
    required this.id,
    required this.location,
    required this.radiusMeters,
    required this.triggers,
    required this.androidSettings,
    required this.status,
    required this.createdAtMillis,
    required this.statusChangedAtMillis,
  });
}

class GeofenceCallbackParams {
  final List<ActiveGeofence?> geofences;
  final GeofenceEvent event;
  final Location? location;
  final int callbackHandle;

  GeofenceCallbackParams({
    required this.geofences,
    required this.event,
    required this.location,
    required this.callbackHandle,
  });
}

/// Configuration for the foreground service notification.
///
/// Used to customize the notification shown when the plugin's foreground
/// service is processing geofence events on Android.
class ForegroundServiceConfiguration {
  final String notificationTitle;
  final String notificationText;

  /// The name of the icon resource to use for the notification.
  /// Should match a mipmap or drawable resource in your app (e.g., 'ic_launcher').
  /// If not provided, defaults to 'ic_launcher'.
  final String? notificationIconName;

  ForegroundServiceConfiguration({
    required this.notificationTitle,
    required this.notificationText,
    this.notificationIconName,
  });
}

/// Errors that can occur when interacting with the native geofence API.
enum NativeGeofenceErrorCode {
  unknown,

  /// A plugin internal error. Please report these as bugs on GitHub.
  pluginInternal,

  /// The arguments passed to the method are invalid.
  invalidArguments,

  /// An error occurred while communicating with the native platform.
  channelError,

  /// The required location permission was not granted.
  ///
  /// On Android we need: `ACCESS_FINE_LOCATION`
  /// On iOS we need: `NSLocationWhenInUseUsageDescription`
  ///
  /// Please use an external permission manager such as "permission_handler" to
  /// request the permission from the user.
  missingLocationPermission,

  /// The required background location permission was not granted.
  ///
  /// On Android we need: `ACCESS_BACKGROUND_LOCATION` (for API level 29+)
  /// On iOS we need: `NSLocationAlwaysAndWhenInUseUsageDescription`
  ///
  /// Please use an external permission manager such as "permission_handler" to
  /// request the permission from the user.
  missingBackgroundLocationPermission,

  /// The geofence deletion failed because the geofence was not found.
  /// This is safe to ignore.
  geofenceNotFound,

  /// The specified geofence callback was not found.
  /// This can happen for old geofence callback functions that were
  /// moved/renamed. Please re-create those geofences.
  callbackNotFound,

  /// The specified geofence callback function signature is invalid.
  /// This can happen if the callback function signature has changed or due to
  /// plugin contract changes.
  callbackInvalid,
}

@HostApi()
abstract class NativeGeofenceApi {
  void initialize({
    required int callbackDispatcherHandle,
    ForegroundServiceConfiguration? foregroundServiceConfig,
  });

  @async
  void createGeofence({required Geofence geofence});

  List<String> getGeofenceIds();

  List<ActiveGeofence> getGeofences();

  @async
  void removeGeofenceById({required String id});

  @async
  void removeAllGeofences();
}

@HostApi()
abstract class NativeGeofenceBackgroundApi {
  void triggerApiInitialized();
}

@FlutterApi()
abstract class NativeGeofenceTriggerApi {
  @async
  void geofenceTriggered(GeofenceCallbackParams params);
}
