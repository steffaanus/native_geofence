package com.steffaanus.native_geofence.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.steffaanus.native_geofence.util.NativeGeofenceLogger
import com.steffaanus.native_geofence.util.ReceiverHelper

/**
 * Receiver for boot completed and app update events.
 * Re-registers all geofences after device reboot or app upgrade.
 *
 * This handles scenarios where:
 * - Device reboots (BOOT_COMPLETED)
 * - Quick boot on some manufacturers (QUICKBOOT_POWERON)
 * - App is upgraded (MY_PACKAGE_REPLACED)
 *
 * After these events, geofences need to be re-registered as they are not
 * automatically restored by the system.
 */
class NativeGeofenceRebootBroadcastReceiver : BroadcastReceiver() {
    private val log = NativeGeofenceLogger("NativeGeofenceRebootBroadcastReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "unknown"
        log.i("System event received: $action - Re-registering all geofences")
        
        // Use ReceiverHelper to create ApiImpl with logging support
        // This enables logs to reach the Flutter app for better debugging
        val apiImpl = ReceiverHelper.createApiImplWithLogging(context)
        
        // Force sync to re-register ALL geofences after boot/upgrade
        apiImpl.syncGeofences(force = true)
    }
}
