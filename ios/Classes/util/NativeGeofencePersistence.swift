import Foundation
import OSLog

class NativeGeofencePersistence {
    private static let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "NativeGeofencePersistence")
    private static let persistentState: UserDefaults = .standard
    private static let geofencesKey = "native_geofence.geofences"

    static func saveGeofence(_ geofence: GeofenceWire) {
        var geofences = getAllGeofences()
        geofences[geofence.id] = geofence
        saveAllGeofences(geofences)
        log.debug("Saved geofence \(geofence.id) to persistence.")
    }

    static func getGeofence(id: String) -> GeofenceWire? {
        return getAllGeofences()[id]
    }

    static func getAllGeofences() -> [String: GeofenceWire] {
        // Migration logic: If the old callback dictionary exists, clear it and log a warning.
        // A seamless migration is not possible as the old format did not store the full geofence definition.
        if persistentState.dictionary(forKey: Constants.GEOFENCE_CALLBACK_DICT_KEY) != nil {
            log.warning("Old geofence storage format found. This will be cleared. Geofences must be re-added by the app.")
            persistentState.removeObject(forKey: Constants.GEOFENCE_CALLBACK_DICT_KEY)
        }

        guard let data = persistentState.data(forKey: geofencesKey) else {
            return [:]
        }
        do {
            let geofences = try JSONDecoder().decode([String: GeofenceWire].self, from: data)
            return geofences
        } catch {
            log.error("Error decoding geofences: \(error)")
            return [:]
        }
    }

    static func removeGeofence(id: String) {
        var geofences = getAllGeofences()
        if geofences.removeValue(forKey: id) != nil {
            saveAllGeofences(geofences)
            log.debug("Removed geofence \(id) from persistence.")
        }
    }
    
    static func removeAllGeofences() {
        persistentState.removeObject(forKey: geofencesKey)
        log.debug("Removed all geofences from persistence.")
    }

    private static func saveAllGeofences(_ geofences: [String: GeofenceWire]) {
        do {
            let data = try JSONEncoder().encode(geofences)
            persistentState.set(data, forKey: geofencesKey)
        } catch {
            log.error("Error encoding geofences: \(error)")
        }
    }
    
    // MARK: - Callback Handles (Legacy but still needed for now)
    
    static func setCallbackDispatcherHandle(_ handle: Int64) {
        persistentState.set(
            NSNumber(value: handle),
            forKey: Constants.CALLBACK_DISPATCHER_KEY
        )
    }
    
    static func getCallbackDispatcherHandle() -> Int64? {
        guard let handle = persistentState.value(forKey: Constants.CALLBACK_DISPATCHER_KEY) else { return nil }
        return (handle as? NSNumber)?.int64Value
    }
}
