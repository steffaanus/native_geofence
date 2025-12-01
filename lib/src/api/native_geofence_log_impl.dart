import 'dart:async';
import 'package:native_geofence/src/generated/native_geofence_api.g.dart';

/// Implementation of FlutterApi for receiving native log entries
class NativeGeofenceLogImpl extends NativeGeofenceLogApi {
  static final _instance = NativeGeofenceLogImpl._();

  /// Stream controller for log entries
  final _logController = StreamController<NativeLogEntry>.broadcast();

  NativeGeofenceLogImpl._();

  /// Singleton instance
  static NativeGeofenceLogImpl get instance => _instance;

  /// Stream of log entries from native platforms
  Stream<NativeLogEntry> get logStream => _logController.stream;

  @override
  Future<void> logReceived(NativeLogEntry entry) async {
    if (!_logController.isClosed) {
      _logController.add(entry);
    }
  }

  /// Close the stream controller
  void dispose() {
    _logController.close();
  }
}
