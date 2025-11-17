import 'package:native_geofence/src/generated/platform_bindings.g.dart';

class NativeGeofenceException implements Exception {
  final NativeGeofenceErrorCode code;
  final String? message;
  final dynamic details;

  const NativeGeofenceException(this.code, {this.message, this.details});

  factory NativeGeofenceException.internal(
          {String? message, dynamic details}) =>
      NativeGeofenceException(
        NativeGeofenceErrorCode.pluginInternal,
        message: message,
        details: details,
      );

  factory NativeGeofenceException.invalidArgument(
          {String? message, dynamic details}) =>
      NativeGeofenceException(
        NativeGeofenceErrorCode.invalidArguments,
        message: message,
        details: details,
      );
}

class NativeGeofenceExceptionMapper {
  static NativeGeofenceException fromError(
      Object error, StackTrace stackTrace) {
    if (error is NativeGeofenceException) {
      return error;
    }
    return NativeGeofenceException(
      NativeGeofenceErrorCode.channelError,
      message: error.toString(),
      details: stackTrace.toString(),
    );
  }

  static T catchError<T>(Object error, StackTrace stackTrace) {
    throw fromError(error, stackTrace);
  }
}
