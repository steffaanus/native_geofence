import CoreLocation
import Flutter
import OSLog
import UIKit

public class NativeGeofenceApiImpl: NSObject, NativeGeofenceApi {
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "NativeGeofenceApiImpl")
    
    private let locationManagerDelegate: LocationManagerDelegate
    
    init(registerPlugins: FlutterPluginRegistrantCallback) {
        self.locationManagerDelegate = LocationManagerDelegate(flutterPluginRegistrantCallback: registerPlugins)
    }
    
    func initialize(callbackDispatcherHandle: Int64) throws {
        NativeGeofencePersistence.setCallbackDispatcherHandle(callbackDispatcherHandle)
        
        // Migrate legacy data before doing anything else.
        migrateLegacyData()
        
        // Start the engine and then sync. The completion handler ensures sync happens after the engine is running.
        EngineManager.shared.startEngine(withPluginRegistrant: locationManagerDelegate.flutterPluginRegistrantCallback!) {
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
                let iosSettings = IosGeofenceSettingsWire(initialTrigger: true)
                // For Android settings, we assume the most common defaults.
                let androidSettings = AndroidGeofenceSettingsWire(initialTriggers: [.enter, .exit], loiteringDelayMillis: 0)
                
                let geofence = GeofenceWire(
                    id: circularRegion.identifier,
                    location: LocationWire(latitude: circularRegion.center.latitude, longitude: circularRegion.center.longitude),
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
    
    func createGeofence(geofence: GeofenceWire, completion: @escaping (Result<Void, any Error>) -> Void) {
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
        log.debug("Sync complete. \(toAdd.count) added, \(toRemove.count) removed.")
    }
    
    // Legacy method, now calls syncGeofences
    func reCreateAfterReboot() throws {
        log.info("reCreateAfterReboot() called. Syncing geofences instead.")
        syncGeofences()
    }
    
    func getGeofenceIds() throws -> [String] {
        let geofenceIds = Array(NativeGeofencePersistence.getAllGeofences().keys)
        log.debug("getGeofenceIds() found \(geofenceIds.count) geofence(s).")
        return geofenceIds
    }
    
    func getGeofences() throws -> [ActiveGeofenceWire] {
        let geofences = NativeGeofencePersistence.getAllGeofences().values.map {
            ActiveGeofenceWires.fromGeofenceWire($0)
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
