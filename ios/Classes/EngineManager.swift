import Flutter
import OSLog

class EngineManager {
    static let shared = EngineManager()
    
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "EngineManager")
    private let lock = NSLock()
    
    private var headlessEngine: FlutterEngine?
    private var backgroundApi: NativeGeofenceBackgroundApiImpl?
    private var onEngineStarted: (() -> Void)?

    private init() {}
    
    func startEngine(withPluginRegistrant registrant: @escaping FlutterPluginRegistrantCallback, completion: (() -> Void)?) {
        lock.lock()
        defer { lock.unlock() }

        guard headlessEngine == nil else {
            log.debug("Engine already started.")
            // If an engine start is requested while it's already running,
            // we should still execute the completion handler.
            completion?()
            return
        }
        
        headlessEngine = FlutterEngine(name: Constants.HEADLESS_FLUTTER_ENGINE_NAME, project: nil, allowHeadlessExecution: true)
        log.debug("A new headless Flutter engine has been created.")
        
        guard let callbackDispatcherHandle = NativeGeofencePersistence.getCallbackDispatcherHandle() else {
            log.error("Callback dispatcher not found in UserDefaults.")
            return
        }
        
        guard let callbackInfo = FlutterCallbackCache.lookupCallbackInformation(callbackDispatcherHandle) else {
            log.error("Callback dispatcher not found.")
            return
        }
        
        headlessEngine!.run(withEntrypoint: callbackInfo.callbackName, libraryURI: callbackInfo.callbackLibraryPath)
        
        registrant(headlessEngine!)
        log.debug("Flutter engine started and plugins registered.")
        
        let api = NativeGeofenceBackgroundApiImpl(binaryMessenger: headlessEngine!.binaryMessenger)
        api.onInitialized = {
            self.lock.lock()
            self.backgroundApi = api
            self.log.debug("NativeGeofenceBackgroundApi is ready.")
            // Execute the completion handler now that the background API is fully initialized.
            completion?()
            self.lock.unlock()
        }

        NativeGeofenceBackgroundApiSetup.setUp(binaryMessenger: headlessEngine!.binaryMessenger, api: api)
        log.debug("NativeGeofenceBackgroundApi setup called.")

        // Also register the main NativeGeofenceApi in background context
        let nativeGeofenceMainApi = NativeGeofenceApiImpl(registerPlugins: registrant)
        NativeGeofenceApiSetup.setUp(binaryMessenger: headlessEngine!.binaryMessenger, api: nativeGeofenceMainApi)
        log.debug("NativeGeofenceMainApi also initialized in background context.")
    }
    
    func getBackgroundApi() -> NativeGeofenceBackgroundApiImpl? {
        lock.lock()
        defer { lock.unlock() }
        return backgroundApi
    }
    
    func stopEngine() {
        lock.lock()
        defer { lock.unlock() }
        
        headlessEngine?.destroyContext()
        headlessEngine = nil
        backgroundApi = nil
        log.debug("Headless engine stopped and cleaned up.")
    }
}