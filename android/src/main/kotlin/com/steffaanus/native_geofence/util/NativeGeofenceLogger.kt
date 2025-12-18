package com.steffaanus.native_geofence.util

import android.util.Log

/**
 * Centralized logger for native Android code
 * Logs to Android Logcat only
 */
class NativeGeofenceLogger(private val tag: String) {
    
    /**
     * Log a debug message
     */
    fun d(message: String) {
        Log.d(tag, message)
    }
    
    /**
     * Log an info message
     */
    fun i(message: String) {
        Log.i(tag, message)
    }
    
    /**
     * Log a warning message
     */
    fun w(message: String) {
        Log.w(tag, message)
    }
    
    /**
     * Log an error message
     */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
