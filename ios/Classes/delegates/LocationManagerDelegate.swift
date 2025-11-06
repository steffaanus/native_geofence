import CoreLocation
import Flutter
import OSLog

// Singleton class
class LocationManagerDelegate: NSObject, CLLocationManagerDelegate {
    // Prevent multiple instances of CLLocationManager to avoid duplicate triggers.
    private static var sharedLocationManager: CLLocationManager?
    
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "LocationManagerDelegate")
    
    let flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?
    let locationManager: CLLocationManager
    
    init(flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?) {
        self.flutterPluginRegistrantCallback = flutterPluginRegistrantCallback
        locationManager = LocationManagerDelegate.sharedLocationManager ?? CLLocationManager()
        LocationManagerDelegate.sharedLocationManager = locationManager
        
        super.init()
        locationManager.delegate = self
        
        log.debug("LocationManagerDelegate created with instance ID=\(Int.random(in: 1 ... 1000000)).")
    }
    
    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        log.debug("didDetermineState: \(String(describing: state)) for geofence ID: \(region.identifier)")
        
        guard let event: GeofenceEvent = switch state {
        case .unknown: nil
        case .inside: .enter
        case .outside: .exit
        } else {
            log.error("Unknown CLRegionState: \(String(describing: state))")
            return
        }
        
        guard let activeGeofence = ActiveGeofenceWires.fromRegion(region) else {
            log.error("Unknown CLRegion type: \(String(describing: type(of: region)))")
            return
        }
        
        guard let geofence = NativeGeofencePersistence.getGeofence(id: activeGeofence.id) else {
            log.error("Geofence definition for region \(activeGeofence.id) not found in persistence.")
            return
        }

        let params = GeofenceCallbackParamsWire(geofences: [activeGeofence], event: event, location: nil, callbackHandle: geofence.callbackHandle)
        
        // If the engine is not running, start it and then send the event.
        // This handles cases where the app was terminated and restarted by the system.
        if EngineManager.shared.getBackgroundApi() == nil {
            log.warning("Background API not available. The engine may have been killed. Attempting to restart...")
            guard let registrant = flutterPluginRegistrantCallback else {
                log.error("Flutter plugin registrant callback is not available. Cannot restart engine.")
                return
            }
            // Start the engine and pass a completion handler to send the event once it's ready.
            EngineManager.shared.startEngine(withPluginRegistrant: registrant) {
                self.sendGeofenceEvent(params: params, activeGeofence: activeGeofence)
            }
        } else {
            // Engine is already running, send the event directly.
            sendGeofenceEvent(params: params, activeGeofence: activeGeofence)
        }
    }
    
    private func sendGeofenceEvent(params: GeofenceCallbackParamsWire, activeGeofence: ActiveGeofenceWire) {
        guard let backgroundApi = EngineManager.shared.getBackgroundApi() else {
            log.error("Failed to get background API even after engine start. Aborting.")
            return
        }

        backgroundApi.geofenceTriggered(params: params) { result in
            switch result {
            case .success:
                self.log.debug("Geofence trigger for \(activeGeofence.id) handled successfully.")
            case .failure(let error):
                self.log.error("Geofence trigger for \(activeGeofence.id) failed: \(error.localizedDescription)")
            }
        }
        log.debug("Geofence trigger event sent for \(activeGeofence.id).")
    }
    
    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: any Error) {
        log.error("monitoringDidFailFor: \(region?.identifier ?? "nil") withError: \(error)")
    }
}
