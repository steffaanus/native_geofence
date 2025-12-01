import Flutter
import OSLog

class EngineManager {
    static let shared = EngineManager()
    
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "EngineManager")
    private let lock = NSLock()
    
    private var headlessEngine: FlutterEngine?
    private var backgroundApi: NativeGeofenceBackgroundApiImpl?
    private var onEngineStarted: (() -> Void)?
    private var isStarting = false

    private init() {}
    
    func startEngine(withPluginRegistrant registrant: @escaping FlutterPluginRegistrantCallback, completion: (() -> Void)?) {
        // Always ensure we're on main thread for Flutter operations
        guard Thread.isMainThread else {
            log.debug("startEngine called from background thread - dispatching to main thread")
            DispatchQueue.main.async { [weak self] in
                self?.startEngine(withPluginRegistrant: registrant, completion: completion)
            }
            return
        }
        
        lock.lock()
        defer {
            lock.unlock()
        }

        // Check if engine already exists
        if let existingEngine = headlessEngine {
            log.debug("Engine already started.")
            completion?()
            return
        }
        
        // Check if engine startup is in progress
        if isStarting {
            log.debug("Engine startup already in progress - waiting")
            // Wait for current startup to complete, then retry
            lock.unlock() // Release lock before async call
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.startEngine(withPluginRegistrant: registrant, completion: completion)
            }
            lock.lock() // Re-acquire for defer block
            return
        }
        
        // Mark that we're starting the engine
        isStarting = true
        log.debug("Starting new Flutter engine...")
        
        headlessEngine = FlutterEngine(name: Constants.HEADLESS_FLUTTER_ENGINE_NAME, project: nil, allowHeadlessExecution: true)
        log.debug("A new headless Flutter engine has been created.")
        
        guard let callbackDispatcherHandle = NativeGeofencePersistence.getCallbackDispatcherHandle() else {
            log.error("Callback dispatcher not found in UserDefaults.")
            isStarting = false
            return
        }
        
        guard let callbackInfo = FlutterCallbackCache.lookupCallbackInformation(callbackDispatcherHandle) else {
            log.error("Callback dispatcher not found.")
            isStarting = false
            return
        }
        
        headlessEngine!.run(withEntrypoint: callbackInfo.callbackName, libraryURI: callbackInfo.callbackLibraryPath)
        
        registrant(headlessEngine!)
        log.debug("Flutter engine started and plugins registered.")
        
        let api = NativeGeofenceBackgroundApiImpl(binaryMessenger: headlessEngine!.binaryMessenger)
        api.onInitialized = { [weak self] in
            guard let self = self else { return }
            
            self.lock.lock()
            self.backgroundApi = api
            self.isStarting = false
            self.log.debug("NativeGeofenceBackgroundApi is ready.")
            let localCompletion = completion
            self.lock.unlock()

            // Execute the completion handler now that the background API is fully initialized.
            localCompletion?()
        }

        NativeGeofenceBackgroundApiSetup.setUp(binaryMessenger: headlessEngine!.binaryMessenger, api: api)
        log.debug("NativeGeofenceBackgroundApi setup called.")

        // Also register the main NativeGeofenceApi in background context
        let nativeGeofenceMainApi = NativeGeofenceApiImpl(registerPlugins: registrant, binaryMessenger: headlessEngine!.binaryMessenger)
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
        isStarting = false
        log.debug("Headless engine stopped and cleaned up.")
    }
}