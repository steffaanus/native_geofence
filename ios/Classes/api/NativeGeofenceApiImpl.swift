import CoreLocation
import Flutter
import OSLog
import UIKit

public class NativeGeofenceApiImpl: NSObject, NativeGeofenceApi {
    private let log = NativeGeofenceLogger(category: "NativeGeofenceApiImpl")
    
    private let locationManagerDelegate: LocationManagerDelegate
    private let flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?
    private let binaryMessenger: FlutterBinaryMessenger

    init(registerPlugins: FlutterPluginRegistrantCallback, binaryMessenger: FlutterBinaryMessenger) {
        self.flutterPluginRegistrantCallback = registerPlugins
        self.binaryMessenger = binaryMessenger
        // Set the callback on the singleton and use the shared instance
        LocationManagerDelegate.setPluginRegistrantCallback(registerPlugins)
        self.locationManagerDelegate = LocationManagerDelegate.shared
        
        super.init()
        log.debug("NativeGeofenceApiImpl initialized with singleton LocationManagerDelegate")
    }
    
    func initialize(
        callbackDispatcherHandle: Int64,
        foregroundServiceConfig: ForegroundServiceConfiguration?
    ) throws {
        NativeGeofencePersistence.setCallbackDispatcherHandle(callbackDispatcherHandle)
        
        // Setup log forwarding to Flutter
        let logApi = NativeGeofenceLogApi(binaryMessenger: binaryMessenger)
        NativeGeofenceLogger.setFlutterLogApi(logApi)
        
        // Note: foregroundServiceConfig is for Android only and is not used on iOS
        // as iOS does not have an equivalent foreground service notification.
        
        // Migrate legacy data before doing anything else.
        migrateLegacyData()
        
        // Start the engine and then sync. The completion handler ensures sync happens after the engine is running.
        EngineManager.shared.startEngine(withPluginRegistrant: flutterPluginRegistrantCallback!) {
            self.syncGeofences()
        }
    }
    
    private func migrateLegacyData() {
        // We need to access the old dictionary key directly.
        let legacyKey = "geofence.callback.handles"
        let legacyCallbackDict = UserDefaults.standard.dictionary(forKey: legacyKey)
        
        if legacyCallbackDict == nil {
            // No legacy data to migrate.
            return
        }
        
        log.info("Found legacy geofence data. Starting migration...")
        
        // The old format only stored callback handles. We need to combine this with the
        // monitored regions from CLLocationManager to reconstruct the geofences.
        let monitoredRegions = locationManagerDelegate.locationManager.monitoredRegions
        var migratedCount = 0
        
        for region in monitoredRegions {
            guard let circularRegion = region as? CLCircularRegion else { continue }
            
            if let callbackHandle = legacyCallbackDict?[circularRegion.identifier] as? NSNumber {
                let triggers: [GeofenceEvent] = [
                    circularRegion.notifyOnEntry ? .enter : nil,
                    circularRegion.notifyOnExit ? .exit : nil,
                ].compactMap { $0 }
                
                // We assume a default for the missing settings. This is the best we can do.
                let iosSettings = IosGeofenceSettings(initialTrigger: true)
                // For Android settings, we assume the most common defaults.
                let androidSettings = AndroidGeofenceSettings(initialTriggers: [.enter, .exit], loiteringDelayMillis: 0)
                
                let geofence = Geofence(
                    id: circularRegion.identifier,
                    location: Location(latitude: circularRegion.center.latitude, longitude: circularRegion.center.longitude),
                    radiusMeters: circularRegion.radius,
                    triggers: triggers,
                    iosSettings: iosSettings,
                    androidSettings: androidSettings,
                    callbackHandle: callbackHandle.int64Value
                )
                
                NativeGeofencePersistence.saveGeofence(geofence)
                migratedCount+=1
            }
        }
        
        // Clean up the old storage key.
        UserDefaults.standard.removeObject(forKey: legacyKey)
        log.info("Migration complete. Migrated \(migratedCount) geofences. Legacy data has been removed.")
    }
    
    func createGeofence(geofence: Geofence, completion: @escaping (Result<Void, any Error>) -> Void) {
        let currentCount = locationManagerDelegate.locationManager.monitoredRegions.count
        if currentCount >= 20 {
            let error = NSError(
                domain: Constants.PACKAGE_NAME,
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "iOS limits apps to 20 geofences. Currently: \(currentCount)"]
            )
            completion(.failure(error))
            return
        }

        let region = CLCircularRegion(
            center: CLLocationCoordinate2DMake(geofence.location.latitude, geofence.location.longitude),
            radius: geofence.radiusMeters,
            identifier: geofence.id
        )
        region.notifyOnEntry = geofence.triggers.contains(.enter)
        region.notifyOnExit = geofence.triggers.contains(.exit)
        
        NativeGeofencePersistence.saveGeofence(geofence)
        
        locationManagerDelegate.locationManager.startMonitoring(for: region)
        if geofence.iosSettings.initialTrigger {
            locationManagerDelegate.locationManager.requestState(for: region)
        }
        
        log.debug("Created geofence ID=\(geofence.id).")
        
        completion(.success(()))
    }
    
    func syncGeofences() {
        let persistedGeofences = NativeGeofencePersistence.getAllGeofences()
        let monitoredRegions = locationManagerDelegate.locationManager.monitoredRegions
        
        let persistedIds = Set(persistedGeofences.keys)
        let monitoredIds = Set(monitoredRegions.map { $0.identifier })
        
        // NEW: Check for coordinate mismatches in existing regions
        // Storage has normalized coordinates (from Dart)
        // iOS CLRegion has iOS-rounded coordinates
        // We normalize iOS coordinates to compare
        var coordinateMismatches = 0
        for region in monitoredRegions {
            if let circularRegion = region as? CLCircularRegion,
               let storedGeofence = persistedGeofences[region.identifier] {
                
                // Storage location is ALREADY normalized (came from Dart)
                let storedLocation = storedGeofence.location
                
                // iOS region needs normalization for comparison
                let regionLocation = Location(
                    latitude: circularRegion.center.latitude,
                    longitude: circularRegion.center.longitude
                ).normalized()
                
                // Check if coordinates differ
                if !storedLocation.equalsNormalized(regionLocation) {
                    log.warning("Coordinate mismatch for \(region.identifier). Storage: (\(storedLocation.latitude), \(storedLocation.longitude)), iOS: (\(regionLocation.latitude), \(regionLocation.longitude)). Re-syncing...")
                    // Stop old region
                    locationManagerDelegate.locationManager.stopMonitoring(for: region)
                    // Re-create with correct coordinates from storage
                    let newRegion = CLCircularRegion(
                        center: CLLocationCoordinate2DMake(
                            storedLocation.latitude,  // Already normalized
                            storedLocation.longitude  // Already normalized
                        ),
                        radius: storedGeofence.radiusMeters,
                        identifier: storedGeofence.id
                    )
                    newRegion.notifyOnEntry = storedGeofence.triggers.contains(.enter)
                    newRegion.notifyOnExit = storedGeofence.triggers.contains(.exit)
                    locationManagerDelegate.locationManager.startMonitoring(for: newRegion)
                    coordinateMismatches += 1
                    log.debug("Re-synced geofence \(region.identifier) with corrected coordinates")
                }
            }
        }
        
        // Geofences that are in persistence but not monitored by the OS
        let toAdd = persistedIds.subtracting(monitoredIds)
        for id in toAdd {
            if let geofence = persistedGeofences[id] {
                let region = CLCircularRegion(
                    center: CLLocationCoordinate2DMake(geofence.location.latitude, geofence.location.longitude),
                    radius: geofence.radiusMeters,
                    identifier: geofence.id
                )
                region.notifyOnEntry = geofence.triggers.contains(.enter)
                region.notifyOnExit = geofence.triggers.contains(.exit)
                locationManagerDelegate.locationManager.startMonitoring(for: region)
                log.debug("Synced: Re-added missing geofence \(id) to CLLocationManager.")
            }
        }
        
        // Geofences that are monitored by the OS but no longer in persistence
        let toRemove = monitoredIds.subtracting(persistedIds)
        for region in monitoredRegions {
            if toRemove.contains(region.identifier) {
                locationManagerDelegate.locationManager.stopMonitoring(for: region)
                log.debug("Synced: Removed orphaned geofence \(region.identifier) from CLLocationManager.")
            }
        }
        log.debug("Sync complete. \(toAdd.count) added, \(toRemove.count) removed, \(coordinateMismatches) coordinate mismatches fixed.")
    }
    
    func getGeofenceIds() throws -> [String] {
        let geofenceIds = Array(NativeGeofencePersistence.getAllGeofences().keys)
        log.debug("getGeofenceIds() found \(geofenceIds.count) geofence(s).")
        return geofenceIds
    }
    
    func getGeofences() throws -> [ActiveGeofence] {
        let geofences = NativeGeofencePersistence.getAllGeofences().values.map {
            ActiveGeofenceWires.fromGeofence($0)
        }
        log.debug("getGeofences() found \(geofences.count) geofence(s).")
        return geofences
    }
    
    func removeGeofenceById(id: String, completion: @escaping (Result<Void, any Error>) -> Void) {
        var removedCount = 0
        for region in locationManagerDelegate.locationManager.monitoredRegions {
            if region.identifier == id {
                locationManagerDelegate.locationManager.stopMonitoring(for: region)
                removedCount += 1
            }
        }
        NativeGeofencePersistence.removeGeofence(id: id)
        log.debug("Removed \(removedCount) geofence(s) with ID=\(id).")
        completion(.success(()))
    }
    
    func removeAllGeofences(completion: @escaping (Result<Void, any Error>) -> Void) {
        var removedCount = 0
        for region in locationManagerDelegate.locationManager.monitoredRegions {
            locationManagerDelegate.locationManager.stopMonitoring(for: region)
            removedCount += 1
        }
        NativeGeofencePersistence.removeAllGeofences()
        log.debug("Removed \(removedCount) geofence(s).")
        
        if NativeGeofencePersistence.getAllGeofences().isEmpty {
            EngineManager.shared.stopEngine()
        }
        
        completion(.success(()))
    }
}
