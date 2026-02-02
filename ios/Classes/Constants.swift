class Constants {
    static let PACKAGE_NAME = "com.steffaanus.native_geofence"
    
    static let HEADLESS_FLUTTER_ENGINE_NAME = "NativeGeofenceIsolate"
    
    static let CALLBACK_DISPATCHER_KEY = "callback_dispatcher_handler"
    static let GEOFENCE_CALLBACK_DICT_KEY = "geofence_callback_dict"
    
    // ✅ NEW: Callback timeout configuration
    struct CallbackTimeoutConfig {
        /// Default timeout for Flutter callbacks in seconds
        /// NOTE: Must match Dart side timeout (25s) with small buffer
        static let defaultTimeout: TimeInterval = 28.0
        
        /// Minimum timeout (cannot be set lower than this)
        static let minTimeout: TimeInterval = 10.0
        
        /// Maximum timeout (cannot be set higher than this)
        static let maxTimeout: TimeInterval = 55.0
        
        /// Circuit breaker threshold (number of timeouts before opening)
        static let circuitBreakerThreshold: Int = 10
        
        /// Circuit breaker cooldown period in seconds
        static let circuitBreakerCooldown: TimeInterval = 300.0
    }
}
