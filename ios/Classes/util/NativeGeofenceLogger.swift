import CoreLocation
import Foundation
import OSLog

/// Centralized logger for native iOS code
/// Logs to iOS unified logging system (OSLog) only
class NativeGeofenceLogger {
    private let logger: Logger
    private let category: String
    
    init(category: String) {
        self.category = category
        self.logger = Logger(subsystem: Constants.PACKAGE_NAME, category: category)
    }
    
    /// Log a debug message
    func debug(_ message: String) {
        logger.debug("\(message)")
    }
    
    /// Log an info message
    func info(_ message: String) {
        logger.info("\(message)")
    }
    
    /// Log a warning message
    func warning(_ message: String) {
        logger.warning("\(message)")
    }
    
    /// Log an error message
    func error(_ message: String) {
        logger.error("\(message)")
    }
}
