import 'package:flutter/services.dart';

import 'package:native_geofence/src/generated/platform_bindings.g.dart';
import 'package:native_geofence/src/model/model.dart';
import 'package:native_geofence/src/model/native_geofence_exception.dart';

extension GeofenceEventMapper on GeofenceEvent {
  GeofenceEventWire toWire() {
    return GeofenceEventWire.values[index];
  }
}

extension GeofenceEventWireMapper on GeofenceEventWire {
  GeofenceEvent fromWire() {
    return GeofenceEvent.values[index];
  }
}

extension LocationMapper on Location {
  LocationWire toWire() {
    return LocationWire(latitude: latitude, longitude: longitude);
  }
}

extension LocationWireMapper on LocationWire {
  Location fromWire() {
    return Location(latitude: latitude, longitude: longitude);
  }
}

extension IosGeofenceSettingsMapper on IosGeofenceSettings {
  IosGeofenceSettingsWire toWire() {
    return IosGeofenceSettingsWire(initialTrigger: initialTrigger);
  }
}

extension IosGeofenceSettingsWireMapper on IosGeofenceSettingsWire {
  IosGeofenceSettings fromWire() {
    return IosGeofenceSettings(initialTrigger: initialTrigger);
  }
}

extension AndroidGeofenceSettingsMapper on AndroidGeofenceSettings {
  AndroidGeofenceSettingsWire toWire() {
    return AndroidGeofenceSettingsWire(
      initialTriggers: initialTriggers.map((e) => e.toWire()).toList(),
      expirationDurationMillis: expiration?.inMilliseconds,
      loiteringDelayMillis: loiteringDelay.inMilliseconds,
      notificationResponsivenessMillis:
          notificationResponsiveness?.inMilliseconds,
    );
  }
}

extension AndroidGeofenceSettingsWireMapper on AndroidGeofenceSettingsWire {
  AndroidGeofenceSettings fromWire() {
    return AndroidGeofenceSettings(
      initialTriggers: initialTriggers.map((e) => e.fromWire()).toSet(),
      expiration: expirationDurationMillis != null
          ? Duration(milliseconds: expirationDurationMillis!)
          : null,
      loiteringDelay: Duration(milliseconds: loiteringDelayMillis),
      notificationResponsiveness: notificationResponsivenessMillis != null
          ? Duration(milliseconds: notificationResponsivenessMillis!)
          : null,
    );
  }
}

extension GeofenceMapper on Geofence {
  GeofenceWire toWire(int callbackHandle) {
    return GeofenceWire(
      id: id,
      location: location.toWire(),
      radiusMeters: radiusMeters,
      triggers: triggers.map((e) => e.toWire()).toList(),
      iosSettings: iosSettings.toWire(),
      androidSettings: androidSettings.toWire(),
      callbackHandle: callbackHandle,
    );
  }
}

extension ActiveGeofenceWireMapper on ActiveGeofenceWire {
  ActiveGeofence fromWire() {
    return ActiveGeofence(
      id: id,
      location: location.fromWire(),
      radiusMeters: radiusMeters,
      triggers: triggers.map((e) => e.fromWire()).toSet(),
      androidSettings: androidSettings?.fromWire(),
      status: status.fromWire(),
    );
  }
}

extension GeofenceStatusMapper on GeofenceStatus {
  GeofenceStatusWire toWire() {
    return GeofenceStatusWire.values[index];
  }
}

extension GeofenceStatusWireMapper on GeofenceStatusWire {
  GeofenceStatus fromWire() {
    return GeofenceStatus.values[index];
  }
}

extension GeofenceCallbackParamsWireMapper on GeofenceCallbackParamsWire {
  GeofenceCallbackParams fromWire() {
    return GeofenceCallbackParams(
      geofences: geofences.map((e) => e!.fromWire()).toList(),
      event: event.fromWire(),
      location: location?.fromWire(),
    );
  }
}

extension NativeGeofenceExceptionMapper on NativeGeofenceException {
  static NativeGeofenceException fromPlatformException(PlatformException ex) {
    return NativeGeofenceException(
      code: ex.code == 'channel-error'
          ? NativeGeofenceErrorCode.channelError
          : NativeGeofenceErrorCode.values.firstWhere(
              (e) => e.name == ex.code,
              orElse: () => NativeGeofenceErrorCode.unknown,
            ),
      message: ex.message,
      details: ex.details,
      stacktrace: ex.stacktrace,
    );
  }

  static NativeGeofenceException fromException(Exception ex,
      [StackTrace? stacktrace]) {
    return NativeGeofenceException(
      code: NativeGeofenceErrorCode.unknown,
      message: ex.toString(),
      stacktrace: stacktrace?.toString() ?? StackTrace.current.toString(),
    );
  }

  static NativeGeofenceException fromError(dynamic error,
      [StackTrace? stacktrace]) {
    if (error is NativeGeofenceException) {
      return error;
    }
    if (error is PlatformException) {
      return fromPlatformException(error);
    }
    if (error is Exception) {
      return fromException(error, stacktrace);
    }
    return NativeGeofenceException(
      code: NativeGeofenceErrorCode.unknown,
      message: error.toString(),
      stacktrace: stacktrace?.toString() ?? StackTrace.current.toString(),
    );
  }

  static T catchError<T>(dynamic error, StackTrace stacktrace) {
    throw fromError(error, stacktrace);
  }
}
