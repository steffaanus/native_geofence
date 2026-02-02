import 'dart:async';
import 'dart:developer';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:native_geofence/src/api/native_geofence_trigger_impl.dart';
import 'package:native_geofence/src/native_geofence_background_manager.dart';

@pragma('vm:entry-point')
Future<void> callbackDispatcher() async {
  log('Callback dispatcher called.', name: 'native_geofence');

  // Setup connection between platform and Flutter.
  WidgetsFlutterBinding.ensureInitialized();

  // Create the NativeGeofenceTriggerApi.
  NativeGeofenceTriggerImpl.ensureInitialized();

  // Create the NativeGeofenceBackgroundApi with retry logic.
  // The native side needs time to set up the pigeon handlers after engine.run()
  const maxRetries = 5;
  const initialDelay = Duration(milliseconds: 50);

  for (var attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      await createNativeGeofenceBackgroundManagerInstance();
      log('NativeGeofenceBackgroundManager initialized successfully on attempt $attempt',
          name: 'native_geofence');
      return;
    } on PlatformException catch (e) {
      if (e.code == 'channel-error' && attempt < maxRetries) {
        // Native handlers not ready yet, wait and retry
        final delay = initialDelay * attempt;
        log('Background API not ready on attempt $attempt, retrying in ${delay.inMilliseconds}ms...',
            name: 'native_geofence');
        await Future.delayed(delay);
      } else {
        // Max retries reached or different error - rethrow
        log('Failed to initialize background manager after $attempt attempts: $e',
            name: 'native_geofence');
        rethrow;
      }
    } catch (e) {
      log('Unexpected error initializing background manager: $e',
          name: 'native_geofence');
      rethrow;
    }
  }
}
