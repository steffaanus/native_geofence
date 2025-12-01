import CoreLocation
import Flutter
import Foundation
import OSLog

/// Centralized logger that forwards warning/error logs to Flutter
class NativeGeofenceLogger {
    private let logger: Logger
    private let category: String
    private static var flutterLogApi: NativeGeofenceLogApi?
    private static var logBuffer: [NativeLogEntry] = []
    private static let maxBufferSize = 50
    private static let lock = NSLock()
    
    init(category: String) {
        self.category = category
        self.logger = Logger(subsystem: Constants.PACKAGE_NAME, category: category)
    }
    
    /// Set the Flutter API for log forwarding and flush buffered logs
    static func setFlutterLogApi(_ api: NativeGeofenceLogApi?) {
        lock.lock()
        defer { lock.unlock() }
        
        flutterLogApi = api
        
        // Flush any buffered logs when API becomes available
        if let api = api, !logBuffer.isEmpty {
            let bufferedLogs = logBuffer
            logBuffer.removeAll()
            
            // Send buffered logs asynchronously
            DispatchQueue.global(qos: .utility).async {
                for entry in bufferedLogs {
                    api.logReceived(entry: entry) { _ in
                        // Ignore result - flushing is best-effort
                    }
                }
            }
        }
    }
    
    /// Log a debug message (OS only, not forwarded to Flutter)
    func debug(_ message: String) {
        logger.debug("\(message)")
    }
    
    /// Log an info message (OS only, not forwarded to Flutter)
    func info(_ message: String) {
        logger.info("\(message)")
    }
    
    /// Log a warning message (OS + forwarded to Flutter)
    func warning(_ message: String) {
        logger.warning("\(message)")
        forwardToFlutter(level: .warning, message: message)
    }
    
    /// Log an error message (OS + forwarded to Flutter)
    func error(_ message: String) {
        logger.error("\(message)")
        forwardToFlutter(level: .error, message: message)
    }
    
    /// Forward log entry to Flutter if API is available, otherwise buffer it
    private func forwardToFlutter(level: NativeLogLevel, message: String) {
        let entry = NativeLogEntry(
            level: level,
            message: message,
            category: category,
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1000),
            platform: "ios"
        )
        
        Self.lock.lock()
        defer { Self.lock.unlock() }
        
        guard let api = Self.flutterLogApi else {
            // Buffer log for later when engine becomes available
            Self.logBuffer.append(entry)
            
            // Limit buffer size to prevent memory issues
            if Self.logBuffer.count > Self.maxBufferSize {
                Self.logBuffer.removeFirst()
            }
            return
        }
        
        // Send async to avoid blocking - logging should never block critical code
        api.logReceived(entry: entry) { _ in
            // Ignore result - logging failures should not affect app functionality
        }
    }
}