import 'dart:async';

import 'package:native_geofence/src/generated/platform_bindings.g.dart';

class NativeGeofenceBackgroundManager {
  static NativeGeofenceBackgroundManager? _instance;

  /// The singleton instance of [NativeGeofenceBackgroundManager].
  ///
  /// WARNING: Can only be accessed within Geofence callbacks. Trying to access
  /// this anywhere else will throw an [AssertionError].
  static NativeGeofenceBackgroundManager get instance {
    assert(
        _instance != null,
        'NativeGeofenceBackgroundManager has not been initialized yet; '
        'Are you running within a Geofence callback?');
    return _instance!;
  }

  final NativeGeofenceBackgroundApi _api;

  NativeGeofenceBackgroundManager._(this._api);
}

/// Private method internal to plugin, do not use.
Future<void> createNativeGeofenceBackgroundManagerInstance() async {
  final api = NativeGeofenceBackgroundApi();
  NativeGeofenceBackgroundManager._instance =
      NativeGeofenceBackgroundManager._(api);
  await api.triggerApiInitialized();
}
