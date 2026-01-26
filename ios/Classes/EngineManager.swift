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
    private var pendingCompletions: [() -> Void] = []

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
        
        // Acquire lock BEFORE any checks to ensure atomicity
        lock.lock()
        
        // Check if engine already exists AND backgroundApi is ready
        if headlessEngine != nil && backgroundApi != nil {
            lock.unlock()  // Release lock before calling completion
            log.debug("Engine and background API already ready.")
            completion?()
            return
        }
        
        // Check if engine exists but backgroundApi is still initializing
        if headlessEngine != nil && backgroundApi == nil {
            log.debug("Engine exists but background API not ready yet - queuing completion")
            if let completion = completion {
                pendingCompletions.append(completion)
            }
            lock.unlock()  // Release lock
            return
        }
        
        // Check if engine startup is in progress
        if isStarting {
            log.debug("Engine startup already in progress - queuing completion")
            if let completion = completion {
                pendingCompletions.append(completion)
            }
            lock.unlock()  // Release lock
            return
        }
        
        // CRITICAL: Mark that we're starting the engine WHILE HOLDING LOCK
        // This prevents race conditions where multiple threads could create multiple engines
        isStarting = true
        lock.unlock()  // NOW we can release the lock
        
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
        
        // CRITICAL FIX: Set up APIs BEFORE starting Flutter engine
        // This prevents race condition where Flutter calls triggerApiInitialized() before API is ready
        
        let api = NativeGeofenceBackgroundApiImpl(binaryMessenger: headlessEngine!.binaryMessenger)
        
        // Set onInitialized callback BEFORE calling setUp()
        api.onInitialized = { [weak self] in
            guard let self = self else { return }
            
            self.lock.lock()
            self.backgroundApi = api
            self.isStarting = false
            self.log.debug("NativeGeofenceBackgroundApi is ready.")
            
            // Collect all pending completions
            let allCompletions = self.pendingCompletions + (completion != nil ? [completion!] : [])
            self.pendingCompletions.removeAll()
            
            self.log.debug("Executing \(allCompletions.count) pending completion handler(s)")
            self.lock.unlock()

            // Execute all completion handlers now that the background API is fully initialized
            for completionHandler in allCompletions {
                completionHandler()
            }
        }

        NativeGeofenceBackgroundApiSetup.setUp(binaryMessenger: headlessEngine!.binaryMessenger, api: api)
        log.debug("NativeGeofenceBackgroundApi setup called.")

        // Also register the main NativeGeofenceApi in background context
        let nativeGeofenceMainApi = NativeGeofenceApiImpl(registerPlugins: registrant, binaryMessenger: headlessEngine!.binaryMessenger)
        NativeGeofenceApiSetup.setUp(binaryMessenger: headlessEngine!.binaryMessenger, api: nativeGeofenceMainApi)
        log.debug("NativeGeofenceMainApi also initialized in background context.")
        
        // NOW start Flutter engine after all APIs are set up
        headlessEngine!.run(withEntrypoint: callbackInfo.callbackName, libraryURI: callbackInfo.callbackLibraryPath)
        
        registrant(headlessEngine!)
        log.debug("Flutter engine started and plugins registered.")
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
        pendingCompletions.removeAll()
        log.debug("Headless engine stopped and cleaned up.")
    }
}