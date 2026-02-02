import CoreLocation
import Flutter
import OSLog

// MARK: - Callback Tracker

/// Tracks the state of an active callback
private struct CallbackTracker {
    let workItem: DispatchWorkItem
    let startTime: Date
    let geofenceIds: String
    let eventType: String
    let completion: ((Result<Void, Error>) -> Void)?
}

// MARK: - NativeGeofenceBackgroundApiImpl

class NativeGeofenceBackgroundApiImpl: NativeGeofenceBackgroundApi {
    private let log = Logger(subsystem: Constants.PACKAGE_NAME, category: "NativeGeofenceBackgroundApiImpl")
    
    private let binaryMessenger: FlutterBinaryMessenger
    
    private var eventQueue: [GeofenceCallbackParams] = .init()
    private let maxQueueSize = 100
    private var isClosed: Bool = false
    private var nativeGeoFenceTriggerApi: NativeGeofenceTriggerApi? = nil
    
    // Flag to ensure sequential queue processing (prevents race condition)
    private var isProcessingQueue: Bool = false
    
    // Callback to notify when the background API is ready to process events.
    var onInitialized: (() -> Void)?
    
    // Timeout mechanism properties
    private let callbackTimeout: TimeInterval = Constants.CallbackTimeoutConfig.defaultTimeout
    private var activeCallbacks: [String: CallbackTracker] = [:]
    private let callbacksLock = NSLock()
    
    // Circuit breaker properties
    private let circuitBreakerThreshold: Int = Constants.CallbackTimeoutConfig.circuitBreakerThreshold
    private let circuitBreakerCooldown: TimeInterval = Constants.CallbackTimeoutConfig.circuitBreakerCooldown
    private var circuitBreakerOpenTime: Date? = nil
    private var consecutiveTimeouts: Int = 0
    private let circuitBreakerLock = NSLock()
    
    init(binaryMessenger: FlutterBinaryMessenger) {
        self.binaryMessenger = binaryMessenger
    }
    
    // MARK: - NativeGeofenceBackgroundApi
    
    func geofenceTriggered(params: GeofenceCallbackParams, completion: @escaping (Result<Void, Error>) -> Void) {
        objc_sync_enter(self)
        if eventQueue.count >= maxQueueSize {
            log.warning("Queue full. Dropping oldest event.")
            eventQueue.removeFirst()
        }

        eventQueue.append(params)
        objc_sync_exit(self)

        guard let nativeGeoFenceTriggerApi else {
            log.debug("Waiting for NativeGeofenceTriggerApi to become available...")
            // Note: We don't call completion here - it will be called when event is actually processed
            return
        }
        // Start queue processing (will only begin if not already processing)
        startQueueProcessing()
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
        // Start queue processing (will only begin if not already processing)
        startQueueProcessing()
    }
    
    // MARK: - Queue Processing
    
    /// Starts queue processing if not already processing
    /// This ensures events are processed sequentially, preventing race conditions
    private func startQueueProcessing() {
        objc_sync_enter(self)
        let shouldStart = !isProcessingQueue && !eventQueue.isEmpty
        if shouldStart {
            isProcessingQueue = true
        }
        objc_sync_exit(self)
        
        if shouldStart {
            log.debug("Starting sequential queue processing")
            processNextEvent()
        }
    }
    
    /// Processes the next event in the queue sequentially
    /// Called recursively after each event completes
    private func processNextEvent() {
        objc_sync_enter(self)
        
        if isClosed {
            log.error("NativeGeofenceBackgroundApi already closed, stopping queue processing.")
            isProcessingQueue = false
            objc_sync_exit(self)
            return
        }
        
        guard !eventQueue.isEmpty else {
            // Queue is empty, stop processing
            isProcessingQueue = false
            objc_sync_exit(self)
            log.debug("Queue processing complete - no more events")
            return
        }
        
        let params = eventQueue.removeFirst()
        objc_sync_exit(self)
        
        log.debug("Queue dispatch: sending geofence trigger event for IDs=[\(NativeGeofenceBackgroundApiImpl.geofenceIds(params))].")
        callGeofenceTriggerApi(params: params, completion: nil)
    }
    
    // Legacy method for backward compatibility - now delegates to startQueueProcessing
    private func processQueue() {
        startQueueProcessing()
    }
    
    // Legacy method for backward compatibility - now delegates to startQueueProcessing
    private func processQueueWithCompletion(_ completion: ((Result<Void, Error>) -> Void)?) {
        // Note: completion parameter is no longer used in sequential processing
        // Events are processed one at a time, and completions are handled via callback tracking
        startQueueProcessing()
    }
    
    // MARK: - Flutter Callback
    
    private func callGeofenceTriggerApi(params: GeofenceCallbackParams, completion: ((Result<Void, Error>) -> Void)? = nil) {
        // Check circuit breaker
        if isCircuitBreakerOpen() {
            log.warning("Circuit breaker is open, skipping callback. Event will be lost.")
            completion?(.failure(NSError(domain: "CircuitBreaker", code: -2, userInfo: [NSLocalizedDescriptionKey: "Circuit breaker open"])))
            // Still process queue to prevent blocking
            processQueue()
            return
        }
        
        guard let api = nativeGeoFenceTriggerApi else {
            log.error("NativeGeofenceTriggerApi was nil, this should not happen.")
            completion?(.failure(NSError(domain: "NativeGeofence", code: -3, userInfo: [NSLocalizedDescriptionKey: "API not available"])))
            return
        }
        
        let callbackId = UUID().uuidString
        let geofenceIds = NativeGeofenceBackgroundApiImpl.geofenceIds(params)
        let eventType = String(describing: params.event)
        
        log.debug("Calling Dart callback to process geofence trigger for IDs=[\(geofenceIds)] event=\(eventType).")
        
        // Create timeout work item
        let timeoutWorkItem = DispatchWorkItem { [weak self] in
            self?.handleCallbackTimeout(
                callbackId: callbackId,
                geofenceIds: geofenceIds,
                eventType: eventType,
                completion: completion
            )
        }
        
        // Register callback
        let tracker = CallbackTracker(
            workItem: timeoutWorkItem,
            startTime: Date(),
            geofenceIds: geofenceIds,
            eventType: eventType,
            completion: completion
        )
        
        callbacksLock.lock()
        activeCallbacks[callbackId] = tracker
        callbacksLock.unlock()
        
        // Schedule timeout
        DispatchQueue.global(qos: .userInitiated).asyncAfter(
            deadline: .now() + callbackTimeout,
            execute: timeoutWorkItem
        )
        
        // Call Flutter API
        api.geofenceTriggered(params: params, completion: { [weak self] (result: Result<Void, PigeonError>) in
            // Convert Result<Void, PigeonError> to Result<Void, Error>
            let mappedResult: Result<Void, Error> = result.mapError { $0 as Error }
            self?.handleCallbackCompletion(
                callbackId: callbackId,
                result: mappedResult,
                geofenceIds: geofenceIds,
                eventType: eventType
            )
        })
    }
    
    // MARK: - Callback Handling
    
    private func handleCallbackTimeout(
        callbackId: String,
        geofenceIds: String,
        eventType: String,
        completion: ((Result<Void, Error>) -> Void)?
    ) {
        callbacksLock.lock()
        let tracker = activeCallbacks.removeValue(forKey: callbackId)
        callbacksLock.unlock()
        
        guard let tracker = tracker else {
            // Callback already completed, ignore
            return
        }
        
        let duration = Date().timeIntervalSince(tracker.startTime)
        
        // Log timeout with full context
        log.error("""
            🚨 Flutter callback timeout!
            - Callback ID: \(callbackId)
            - Geofence IDs: \(geofenceIds)
            - Event Type: \(eventType)
            - Duration: \(String(format: "%.1f", duration))s
            - Timeout Limit: \(self.callbackTimeout)s
            """)
        
        // Record timeout for circuit breaker
        recordTimeout()
        
        // Call completion with failure
        completion?(.failure(NSError(domain: "Timeout", code: -4, userInfo: [NSLocalizedDescriptionKey: "Callback timeout after \(duration)s"])))
        
        // Continue processing next event sequentially
        log.info("Continuing to next event in queue after timeout")
        processNextEvent()
    }
    
    private func handleCallbackCompletion(
        callbackId: String,
        result: Result<Void, Error>,
        geofenceIds: String,
        eventType: String
    ) {
        callbacksLock.lock()
        let tracker = activeCallbacks.removeValue(forKey: callbackId)
        callbacksLock.unlock()
        
        // Cancel timeout if callback completed in time
        tracker?.workItem.cancel()
        
        if let tracker = tracker {
            let duration = Date().timeIntervalSince(tracker.startTime)
            
            switch result {
            case .success:
                log.debug("""
                    ✅ Flutter callback completed successfully
                    - Geofence IDs: \(geofenceIds)
                    - Event Type: \(eventType)
                    - Duration: \(String(format: "%.1f", duration))s
                    """)
                recordSuccess()
            case .failure:
                log.error("""
                    ❌ Flutter callback failed
                    - Geofence IDs: \(geofenceIds)
                    - Event Type: \(eventType)
                    - Duration: \(String(format: "%.1f", duration))s
                    - Error: \(String(describing: result))
                    """)
            }
            
            // Call completion with result
            tracker.completion?(result)
        }
        
        // Continue processing next event sequentially
        processNextEvent()
    }
    
    // MARK: - Circuit Breaker
    
    private func isCircuitBreakerOpen() -> Bool {
        circuitBreakerLock.lock()
        defer { circuitBreakerLock.unlock() }
        
        guard let openTime = circuitBreakerOpenTime else {
            return false
        }
        
        // Check if cooldown period has passed
        if Date().timeIntervalSince(openTime) > circuitBreakerCooldown {
            // Reset circuit breaker
            circuitBreakerOpenTime = nil
            consecutiveTimeouts = 0
            log.info("Circuit breaker closed after cooldown period")
            return false
        }
        
        return true
    }
    
    private func recordTimeout() {
        circuitBreakerLock.lock()
        consecutiveTimeouts += 1
        
        if consecutiveTimeouts >= circuitBreakerThreshold && circuitBreakerOpenTime == nil {
            circuitBreakerOpenTime = Date()
            log.error("""
                🚨 CIRCUIT BREAKER OPENED!
                - Consecutive timeouts: \(self.consecutiveTimeouts)
                - Cooldown period: \(self.circuitBreakerCooldown)s
                - Events will be skipped until cooldown ends
                """)
        }
        circuitBreakerLock.unlock()
    }
    
    private func recordSuccess() {
        circuitBreakerLock.lock()
        if consecutiveTimeouts > 0 {
            consecutiveTimeouts = 0
            log.debug("Consecutive timeout counter reset after success")
        }
        circuitBreakerLock.unlock()
    }
    
    // MARK: - Helpers
    
    private static func geofenceIds(_ params: GeofenceCallbackParams) -> String {
        let ids: [String] = params.geofences.compactMap { $0?.id }
        return ids.joined(separator: ",")
    }
}

// MARK: - Runtime Configuration

extension NativeGeofenceBackgroundApiImpl {
    
    /// Set custom callback timeout
    func setCallbackTimeout(_ timeout: TimeInterval) {
        // This is now read-only from Constants
        // Kept for API compatibility
        log.info("Callback timeout is fixed at \(self.callbackTimeout)s (configured in Constants)")
    }
    
    /// Get current callback timeout
    func getCallbackTimeout() -> TimeInterval {
        return callbackTimeout
    }
    
    /// Manually close circuit breaker
    func closeCircuitBreaker() {
        circuitBreakerLock.lock()
        defer { circuitBreakerLock.unlock() }
        
        if circuitBreakerOpenTime != nil {
            circuitBreakerOpenTime = nil
            consecutiveTimeouts = 0
            log.info("Circuit breaker manually closed")
        }
    }
    
    /// Check if circuit breaker is currently open
    func isCircuitBreakerCurrentlyOpen() -> Bool {
        return isCircuitBreakerOpen()
    }
}
