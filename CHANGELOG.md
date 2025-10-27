## 2.0.0

*   **Feat (iOS & Android):** Implemented a seamless data migration for the internal storage format. Existing geofences on both platforms will be automatically migrated to the new, more robust storage structure. No breaking changes for existing users.
*   **Feat (iOS):** The `reCreateAfterReboot()` method has been deprecated in favor of an automatic `syncGeofences()` that runs on initialization. Manual calls to `reCreateAfterReboot()` can be safely removed.
*   **Feat:** Implemented a new `syncGeofences()` method on both Android and iOS that runs on every app start, ensuring the plugin's internal state is consistent with the OS. This significantly improves reliability.
*   **Fix (Android):** Implemented a seamless data migration for the internal storage format. Existing geofences will be automatically migrated to the new, more robust storage structure.
*   **Fix (Android & iOS):** Fundamentally refactored the state management to prevent state desynchronization, which was the likely cause of geofence events no longer being triggered over time.
*   **Fix (iOS):** Replaced the inefficient and error-prone background `FlutterEngine` management with a single, reusable engine. This resolves race conditions and significantly improves performance and battery efficiency.

## 1.1.0

* Upgrade pub.dev dependencies
* Upgrade Android Gradle dependencies
* Downgrade Android minSdkVersion to 23 (thanks [AzarouAmine](https://github.com/AzarouAmine)!)
* Allow calling GeofenceManager methods in geofence callbacks (thanks [Mako-L](https://github.com/Mako-L)!)
* Example App: refactor notification logic into a NotificationsRepository (thanks [fadelfffar](https://github.com/fadelfffar)!)

## 1.0.9

* Minor visibility fix: Make `NativeGeofenceException` visible to library users.

## 1.0.8

* Make plugin compatible with Flutter apps using Kotlin 2+.

## 1.0.7

* Fixes a bug with Android 30 and older. [#9](https://github.com/Steffaanus/native_geofence/issues/9)
* Improve documentation.

## 1.0.6

* iOS: Improved background isolate spawning & cleanup routine.
* iOS: Fixes rare bug that may cause the goefence to triggering twice.

## 1.0.5

* Android: Specify Kotlin package when using Pigeon.

## 1.0.4

* Android: Use custom error class name to avoid naming conflicts ("Type FlutterError is defined multiple times") at build time.

## 1.0.3

* iOS: Removes `UIBackgroundModes.location` which was not required. Thanks @cbrauchli.

## 1.0.2

* iOS and Android: Process geofence callbacks sequentially; as opposed to in parallel.
* README changes.

## 1.0.1

* WASM support.
* Better documentation.
* Formatting fixes.

## 1.0.0

* Initial release.
