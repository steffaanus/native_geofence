import Flutter
import OSLog

// MARK: - Engine State

/// Represents the current state of the Flutter engine
private enum EngineState {
    case stopped
    case starting(completions: [() -> Void])
    case running(engine: FlutterEngine, api: NativeGeofenceBackgroundApiImpl)
}

// MARK: - EngineManager

/// Manages the lifecycle of the headless Flutter engine for background geofence processing.
/// This class uses a state machine pattern to ensure thread-safe engine management.
class EngineManager {
    static let shared = EngineManager()
    
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "EngineManager")
    private let lock = NSLock()
    
    private var state: EngineState = .stopped
    
    private init() {}
    
    // MARK: - Public API
    
    /// Starts the Flutter engine if not already running.
    /// Thread-safe and handles concurrent calls by queuing completions.
    /// - Parameters:
    ///   - registrant: Callback to register plugins with the engine
    ///   - completion: Called when engine is ready (may be queued if starting)
    func startEngine(withPluginRegistrant registrant: @escaping FlutterPluginRegistrantCallback, 
                     completion: (() -> Void)?) {
        // Ensure main thread for Flutter operations
        guard Thread.isMainThread else {
            log.debug("startEngine called from background thread - dispatching to main")
            DispatchQueue.main.async { [weak self] in
                self?.startEngine(withPluginRegistrant: registrant, completion: completion)
            }
            return
        }
        
        lock.lock()
        
        switch state {
        case .running:
            // Engine already running - execute completion immediately
            lock.unlock()
            log.debug("Engine already running, executing completion immediately")
            completion?()
            
        case .starting(var completions):
            // Engine starting - queue completion
            if let completion = completion {
                completions.append(completion)
            }
            state = .starting(completions: completions)
            lock.unlock()
            log.debug("Engine starting - completion queued (total: \(completions.count))")
            
        case .stopped:
            // Start engine - queue completion and begin startup
            var completions: [() -> Void] = []
            if let completion = completion {
                completions.append(completion)
            }
            state = .starting(completions: completions)
            lock.unlock()
            
            log.debug("Starting new Flutter engine...")
            createEngine(registrant: registrant)
        }
    }
    
    /// Returns the background API if engine is running
    func getBackgroundApi() -> NativeGeofenceBackgroundApiImpl? {
        lock.lock()
        defer { lock.unlock() }
        
        if case .running(_, let api) = state {
            return api
        }
        return nil
    }
    
    /// Stops the engine and cleans up resources
    func stopEngine() {
        lock.lock()
        defer { lock.unlock() }
        
        if case .running(let engine, _) = state {
            engine.destroyContext()
            log.debug("Engine destroyed")
        }
        
        state = .stopped
        log.debug("Engine state reset to stopped")
    }
    
    // MARK: - Private Methods
    
    private func createEngine(registrant: @escaping FlutterPluginRegistrantCallback) {
        let engine = FlutterEngine(
            name: Constants.HEADLESS_FLUTTER_ENGINE_NAME,
            project: nil,
            allowHeadlessExecution: true
        )
        
        log.debug("Flutter engine instance created")
        
        guard let handle = NativeGeofencePersistence.getCallbackDispatcherHandle(),
              let callbackInfo = FlutterCallbackCache.lookupCallbackInformation(handle) else {
            log.error("Callback dispatcher not found - cannot start engine")
            
            lock.lock()
            state = .stopped
            lock.unlock()
            return
        }
        
        engine.run(
            withEntrypoint: callbackInfo.callbackName,
            libraryURI: callbackInfo.callbackLibraryPath
        )
        
        log.debug("Flutter engine run started")
        
        // Wait for engine initialization before setting up APIs
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            self?.finalizeEngineSetup(engine: engine, registrant: registrant)
        }
    }
    
    private func finalizeEngineSetup(engine: FlutterEngine,
                                     registrant: @escaping FlutterPluginRegistrantCallback) {
        let api = NativeGeofenceBackgroundApiImpl(binaryMessenger: engine.binaryMessenger)
        
        api.onInitialized = { [weak self] in
            self?.handleEngineInitialized(engine: engine, api: api)
        }
        
        // Setup APIs
        NativeGeofenceBackgroundApiSetup.setUp(
            binaryMessenger: engine.binaryMessenger,
            api: api
        )
        
        let mainApi = NativeGeofenceApiImpl(
            registerPlugins: registrant,
            binaryMessenger: engine.binaryMessenger
        )
        NativeGeofenceApiSetup.setUp(
            binaryMessenger: engine.binaryMessenger,
            api: mainApi
        )
        
        // Register plugins
        registrant(engine)
        
        log.debug("Engine setup finalized, waiting for API initialization...")
    }
    
    private func handleEngineInitialized(engine: FlutterEngine,
                                         api: NativeGeofenceBackgroundApiImpl) {
        lock.lock()
        
        guard case .starting(let completions) = state else {
            lock.unlock()
            log.warning("Engine initialized but state changed - ignoring")
            return
        }
        
        state = .running(engine: engine, api: api)
        lock.unlock()
        
        log.debug("Engine fully initialized, executing \(completions.count) queued completions")
        
        // Execute all queued completions
        for completion in completions {
            completion()
        }
    }
}
