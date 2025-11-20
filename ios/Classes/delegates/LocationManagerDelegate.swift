import CoreLocation
import Flutter
import OSLog

// Singleton class
class LocationManagerDelegate: NSObject, CLLocationManagerDelegate {
    // Prevent multiple instances of CLLocationManager to avoid duplicate triggers.
    // Thread-safe singleton pattern
    private static let lock = NSLock()
    private static var _sharedLocationManager: CLLocationManager?
    
    private static var sharedLocationManager: CLLocationManager {
        lock.lock()
        defer { lock.unlock() }
        
        if _sharedLocationManager == nil {
            _sharedLocationManager = CLLocationManager()
        }
        return _sharedLocationManager!
    }
    
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "LocationManagerDelegate")

    let flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?
    let locationManager: CLLocationManager
    
    // Persistence for failed events
    private let pendingEventsKey = "PendingGeofenceEvents"
    private let maxPendingEvents = 50
    private let maxRetries = 3

    init(flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?) {
        self.flutterPluginRegistrantCallback = flutterPluginRegistrantCallback
        // Use thread-safe getter
        locationManager = LocationManagerDelegate.sharedLocationManager

        super.init()
        locationManager.delegate = self

        log.debug("LocationManagerDelegate created with instance ID=\(Int.random(in: 1 ... 1000000)).")
        
        // Try to process any previously failed events
        retryPendingEvents()
    }

    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        log.debug("didDetermineState: \(String(describing: state)) for geofence ID: \(region.identifier)")

        let event: GeofenceEvent

        switch state {
        case .unknown:
            log.warning("Unknown region state for \(region.identifier). CLRegionState: \(String(describing: state))")
            return
        case .inside:
            event = .enter
        case .outside:
            event = .exit
        @unknown default:
            log.error("Onbekende CLRegionState: \(String(describing: state))")
            return
        }

        handleRegionEvent(region: region, event: event)
    }

    // New handlers for didEnterRegion and didExitRegion
    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        log.debug("didEnterRegion: \(region.identifier)")
        handleRegionEvent(region: region, event: .enter)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        log.debug("didExitRegion: \(region.identifier)")
        handleRegionEvent(region: region, event: .exit)
    }

    private func handleRegionEvent(region: CLRegion, event: GeofenceEvent) {
        guard let activeGeofence = ActiveGeofenceWires.fromRegion(region) else {
            log.error("Unknown CLRegion type: \(String(describing: type(of: region)))")
            return
        }

        if !activeGeofence.triggers.contains(event) {
            return
        }


        guard let geofence = NativeGeofencePersistence.getGeofence(id: activeGeofence.id) else {
            log.error("Geofence definition for region \(activeGeofence.id) not found in persistence.")
            return
        }

        let params = GeofenceCallbackParams(geofences: [activeGeofence], event: event, location: nil, callbackHandle: geofence.callbackHandle)

        // If the engine is not running, start it and then send the event.
        // This handles cases where the app was terminated and restarted by the system.
        if EngineManager.shared.getBackgroundApi() == nil {
            log.warning("Background API not available. The engine may have been killed. Attempting to restart...")
            guard let registrant = flutterPluginRegistrantCallback else {
                log.error("Flutter plugin registrant callback is not available. Cannot restart engine. Persisting event.")
                persistFailedEvent(params)
                return
            }
            // Start engine with retry logic
            startEngineAndSendEvent(params: params, activeGeofence: activeGeofence)
        } else {
            // Engine is already running, send the event directly.
            sendGeofenceEvent(params: params, activeGeofence: activeGeofence)
        }
    }

    private func sendGeofenceEvent(params: GeofenceCallbackParams, activeGeofence: ActiveGeofence) {
        guard let backgroundApi = EngineManager.shared.getBackgroundApi() else {
            log.error("Failed to get background API even after engine start. Persisting event for retry.")
            persistFailedEvent(params)
            return
        }

        backgroundApi.geofenceTriggered(params: params) { result in
            switch result {
            case .success:
                self.log.debug("Geofence trigger for \(activeGeofence.id) handled successfully.")
            case .failure(let error):
                self.log.error("Geofence trigger for \(activeGeofence.id) failed: \(error.localizedDescription). Persisting for retry.")
                // Persist event for retry on next app launch
                self.persistFailedEvent(params)
            }
        }
        log.debug("Geofence trigger event sent for \(activeGeofence.id).")
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: any Error) {
        log.error("monitoringDidFailFor: \(region?.identifier ?? "nil") withError: \(error)")
    }
    
    // MARK: - Retry Logic and Persistence
    
    private func startEngineAndSendEvent(
        params: GeofenceCallbackParams,
        activeGeofence: ActiveGeofence,
        retryCount: Int = 0
    ) {
        guard let registrant = flutterPluginRegistrantCallback else {
            log.error("No registrant callback - persisting event for later retry")
            persistFailedEvent(params)
            return
        }
        
        EngineManager.shared.startEngine(withPluginRegistrant: registrant) {
            if EngineManager.shared.getBackgroundApi() != nil {
                self.log.debug("Engine started successfully, sending event")
                self.sendGeofenceEvent(params: params, activeGeofence: activeGeofence)
            } else if retryCount < self.maxRetries {
                let nextRetry = retryCount + 1
                // Exponential backoff: 2s, 4s, 8s
                let delay = pow(2.0, Double(nextRetry))
                self.log.warning("Engine start incomplete. Retry \(nextRetry)/\(self.maxRetries) in \(delay)s")
                
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    self.startEngineAndSendEvent(
                        params: params,
                        activeGeofence: activeGeofence,
                        retryCount: nextRetry
                    )
                }
            } else {
                self.log.error("Engine start failed after \(self.maxRetries) retries. Persisting event for next app launch.")
                self.persistFailedEvent(params)
            }
        }
    }
    
    private func persistFailedEvent(_ params: GeofenceCallbackParams) {
        var existingEvents: [GeofenceCallbackParams] = []
        
        // Load existing pending events
        if let data = UserDefaults.standard.data(forKey: pendingEventsKey),
           let events = try? JSONDecoder().decode([GeofenceCallbackParams].self, from: data) {
            existingEvents = events
        }
        
        existingEvents.append(params)
        
        // Limit to maxPendingEvents to avoid unbounded growth
        if existingEvents.count > maxPendingEvents {
            let dropped = existingEvents.count - maxPendingEvents
            log.warning("Too many pending events (\(existingEvents.count)). Dropping \(dropped) oldest events.")
            existingEvents = Array(existingEvents.suffix(maxPendingEvents))
        }
        
        // Persist to disk
        if let data = try? JSONEncoder().encode(existingEvents) {
            UserDefaults.standard.set(data, forKey: pendingEventsKey)
            UserDefaults.standard.synchronize()
            log.debug("Persisted \(existingEvents.count) pending event(s) to disk")
        } else {
            log.error("Failed to encode pending events for persistence")
        }
    }
    
    private func retryPendingEvents() {
        guard let data = UserDefaults.standard.data(forKey: pendingEventsKey),
              let pendingEvents = try? JSONDecoder().decode([GeofenceCallbackParams].self, from: data),
              !pendingEvents.isEmpty else {
            return
        }
        
        log.info("Found \(pendingEvents.count) pending event(s) from previous failures. Attempting to process...")
        
        // Clear from storage immediately to avoid duplicate processing
        UserDefaults.standard.removeObject(forKey: pendingEventsKey)
        UserDefaults.standard.synchronize()
        
        // Process each pending event
        for params in pendingEvents {
            // Reconstruct ActiveGeofence from persistence
            if let firstGeofence = params.geofences.first,
               let geofenceId = firstGeofence?.id,
               let storedGeofence = NativeGeofencePersistence.getGeofence(id: geofenceId) {
                let activeGeofence = ActiveGeofenceWires.fromGeofence(storedGeofence)
                log.debug("Retrying pending event for geofence \(geofenceId)")
                startEngineAndSendEvent(params: params, activeGeofence: activeGeofence)
            } else {
                log.error("Cannot reconstruct geofence for pending event. Event lost.")
            }
        }
    }
}
