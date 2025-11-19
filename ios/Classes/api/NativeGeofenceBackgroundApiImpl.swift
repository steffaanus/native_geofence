import CoreLocation
import Flutter
import OSLog

class NativeGeofenceBackgroundApiImpl: NativeGeofenceBackgroundApi {
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "NativeGeofenceBackgroundApiImpl")
    
    private let binaryMessenger: FlutterBinaryMessenger
    
    private var eventQueue: [GeofenceCallbackParams] = .init()
    private var isClosed: Bool = false
    private var nativeGeoFenceTriggerApi: NativeGeofenceTriggerApi? = nil
    
    // Callback to notify when the background API is ready to process events.
    var onInitialized: (() -> Void)?
    
    init(binaryMessenger: FlutterBinaryMessenger) {
        self.binaryMessenger = binaryMessenger
    }
    
    func geofenceTriggered(params: GeofenceCallbackParams, completion: @escaping (Result<Void, Error>) -> Void) {
        objc_sync_enter(self)
        
        eventQueue.append(params)
        
        objc_sync_exit(self)
        
        guard let nativeGeoFenceTriggerApi else {
            log.debug("Waiting for NativeGeofenceTriggerApi to become available...")
            return
        }
        processQueue()
    }
    
    func triggerApiInitialized() throws {
        objc_sync_enter(self)
        
        if (nativeGeoFenceTriggerApi == nil) {
            nativeGeoFenceTriggerApi = NativeGeofenceTriggerApi(binaryMessenger: binaryMessenger)
            log.debug("NativeGeofenceTriggerApi setup complete.")
            // Notify listeners that the API is now initialized.
            onInitialized?()
        }
        
        objc_sync_exit(self)
        
       if eventQueue.isEmpty {
            log.debug("Waiting for geofence event...")
            return
        }
        processQueue()
    }
    
    func demoteToBackground() throws {
        log.info("demoteToBackground called. iOS does not distinguish between foreground and background, nothing to do here.")
    }
    
    private func processQueue() {
        objc_sync_enter(self)
        defer { objc_sync_exit(self) }
        
        if isClosed {
            log.error("NativeGeofenceBackgroundApi already closed, ignoring additional events.")
            return
        }
        
        if !eventQueue.isEmpty {
            let params = eventQueue.removeFirst()
            log.debug("Queue dispatch: sending geofence trigger event for IDs=[\(NativeGeofenceBackgroundApiImpl.geofenceIds(params))].")
            callGeofenceTriggerApi(params: params)
            return
        }
        
        // Now that the event queue is empty we can cleanup and de-allocate this class.
        // The engine is now managed centrally, so we no longer call cleanup from here.
        if eventQueue.isEmpty {
             log.debug("Event queue is empty.")
        }
    }
    
    private func callGeofenceTriggerApi(params: GeofenceCallbackParams) {
        guard let api = nativeGeoFenceTriggerApi else {
            log.error("NativeGeofenceTriggerApi was nil, this should not happen.")
            return
        }
        log.debug("Calling Dart callback to process geofence trigger for IDs=[\(NativeGeofenceBackgroundApiImpl.geofenceIds(params))] event=\(String(describing: params.event)).")
        api.geofenceTriggered(params: params, completion: { result in
            if case .success = result {
                self.log.debug("Geofence trigger event for IDs=[\(NativeGeofenceBackgroundApiImpl.geofenceIds(params))] processed successfully.")
            } else {
                self.log.error("Geofence trigger event for IDs=[\(NativeGeofenceBackgroundApiImpl.geofenceIds(params))] failed.")
            }
            // Now that the callback is complete we can process the next item in the queue, if any.
            self.processQueue()
        })
    }
    
    private static func geofenceIds(_ params: GeofenceCallbackParams) -> String {
        let ids: [String] = params.geofences.compactMap { $0?.id }
        return ids.joined(separator: ",")
    }
}
