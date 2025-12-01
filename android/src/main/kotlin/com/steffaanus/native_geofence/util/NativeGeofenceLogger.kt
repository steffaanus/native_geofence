package com.steffaanus.native_geofence.util

import android.util.Log
import com.steffaanus.native_geofence.generated.NativeGeofenceLogApi
import com.steffaanus.native_geofence.generated.NativeLogEntry
import com.steffaanus.native_geofence.generated.NativeLogLevel

/**
 * Centralized logger that forwards warning/error logs to Flutter
 */
class NativeGeofenceLogger(private val tag: String) {
    
    companion object {
        private const val TAG = "NativeGeofenceLogger"
        private var flutterLogApi: NativeGeofenceLogApi? = null
        private val logBuffer = mutableListOf<NativeLogEntry>()
        private const val MAX_BUFFER_SIZE = 50
        private val lock = Any()
        
        /**
         * Set the Flutter API for log forwarding and flush buffered logs
         */
        fun setFlutterLogApi(api: NativeGeofenceLogApi?) {
            synchronized(lock) {
                flutterLogApi = api
                
                // Flush any buffered logs when API becomes available
                if (api != null && logBuffer.isNotEmpty()) {
                    val bufferedLogs = logBuffer.toList()
                    logBuffer.clear()
                    
                    // Send buffered logs asynchronously
                    Thread {
                        bufferedLogs.forEach { entry ->
                            try {
                                api.logReceived(entry) {
                                    // Ignore result - flushing is best-effort
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to flush buffered log", e)
                            }
                        }
                    }.start()
                }
            }
        }
    }
    
    /**
     * Log a debug message (Logcat only, not forwarded to Flutter)
     */
    fun d(message: String) {
        Log.d(tag, message)
    }
    
    /**
     * Log an info message (Logcat only, not forwarded to Flutter)
     */
    fun i(message: String) {
        Log.i(tag, message)
    }
    
    /**
     * Log a warning message (Logcat + forwarded to Flutter)
     */
    fun w(message: String) {
        Log.w(tag, message)
        forwardToFlutter(NativeLogLevel.WARNING, message)
    }
    
    /**
     * Log an error message (Logcat + forwarded to Flutter)
     */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        forwardToFlutter(NativeLogLevel.ERROR, fullMessage)
    }
    
    /**
     * Forward log entry to Flutter if API is available, otherwise buffer it
     */
    private fun forwardToFlutter(level: NativeLogLevel, message: String) {
        val entry = NativeLogEntry(
            level = level,
            message = message,
            category = tag,
            timestampMillis = System.currentTimeMillis(),
            platform = "android"
        )
        
        synchronized(lock) {
            val api = flutterLogApi
            
            if (api == null) {
                // Buffer log for later when engine becomes available
                logBuffer.add(entry)
                
                // Limit buffer size to prevent memory issues
                if (logBuffer.size > MAX_BUFFER_SIZE) {
                    logBuffer.removeAt(0)
                }
                return
            }
            
            try {
                // Send async to avoid blocking - logging should never block critical code
                api.logReceived(entry) {
                    // Ignore result - logging failures should not affect app functionality
                }
            } catch (e: Exception) {
                // Silently fail - don't let logging failures crash the app
                Log.e(tag, "Failed to forward log to Flutter", e)
            }
        }
    }
}