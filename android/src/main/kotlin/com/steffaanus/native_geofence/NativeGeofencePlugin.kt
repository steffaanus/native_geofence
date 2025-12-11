package com.steffaanus.native_geofence

import android.content.Context
import android.util.Log
import com.steffaanus.native_geofence.api.NativeGeofenceApiImpl
import com.steffaanus.native_geofence.generated.NativeGeofenceApi
import com.steffaanus.native_geofence.util.ReceiverHelper
import io.flutter.embedding.engine.plugins.FlutterPlugin

class NativeGeofencePlugin : FlutterPlugin {
    private var context: Context? = null

    companion object {
        @JvmStatic
        private val TAG = "NativeGeofencePlugin"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Register BinaryMessenger for use in BroadcastReceivers
        // This enables logging from receivers to reach the Flutter app
        ReceiverHelper.setBinaryMessenger(binding.binaryMessenger)
        
        NativeGeofenceApi.setUp(
            binding.binaryMessenger,
            NativeGeofenceApiImpl(binding.applicationContext, binding.binaryMessenger)
        )
        Log.d(TAG, "NativeGeofenceApi setup complete with receiver logging support.")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Clear BinaryMessenger reference when engine is destroyed
        ReceiverHelper.setBinaryMessenger(null)
        context = null
        Log.d(TAG, "NativeGeofencePlugin detached.")
    }
}
