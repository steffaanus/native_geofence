import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'package:native_geofence/src/model/native_geofence_exception.dart';
import 'package:native_geofence/src/typedefs.dart';

import '../generated/native_geofence_api.g.dart';

class NativeGeofenceTriggerImpl implements NativeGeofenceTriggerApi {
  /// Cached instance of [NativeGeofenceTriggerImpl]
  static NativeGeofenceTriggerImpl? _instance;

  static void ensureInitialized() {
    _instance ??= NativeGeofenceTriggerImpl._();
  }

  NativeGeofenceTriggerImpl._() {
    NativeGeofenceTriggerApi.setUp(this);
  }

  @override
  Future<void> geofenceTriggered(GeofenceCallbackParams params) async {
    final Stopwatch stopwatch = Stopwatch()..start();
    
    final Function? callback = PluginUtilities.getCallbackFromHandle(
        CallbackHandle.fromRawHandle(params.callbackHandle));
    if (callback == null) {
      throw NativeGeofenceException(NativeGeofenceErrorCode.callbackNotFound);
    }
    if (callback is! GeofenceCallback) {
      throw NativeGeofenceException(
        NativeGeofenceErrorCode.callbackInvalid,
        message: 'Invalid callback type: ${callback.runtimeType.toString()}',
        details: 'Expected: GeofenceCallback',
      );
    }
    
    // Execute callback with 20-second timeout to ensure iOS background task completes
    // This accounts for worst-case Flutter engine startup time (~8s) in terminated state
    // Total: 8s engine + 20s callback = 28s < 30s iOS background task limit
    try {
      await callback(params).timeout(
        const Duration(seconds: 20),
        onTimeout: () {
          debugPrint('⚠️ Geofence callback timeout after 20 seconds');
          throw TimeoutException('Geofence callback took too long (>20s)');
        },
      );
      
      stopwatch.stop();
      final elapsed = stopwatch.elapsedMilliseconds;
      
      if (elapsed > 5000) {
        debugPrint('⚠️ Slow geofence callback: ${elapsed}ms');
      } else {
        debugPrint('✓ Geofence callback completed in ${elapsed}ms');
      }
    } catch (e) {
      stopwatch.stop();
      debugPrint('✗ Geofence callback failed after ${stopwatch.elapsedMilliseconds}ms: $e');
      rethrow;
    }
  }
}
