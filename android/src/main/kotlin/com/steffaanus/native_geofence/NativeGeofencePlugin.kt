package com.steffaanus.native_geofence

import android.content.Context
import android.util.Log
import com.steffaanus.native_geofence.api.NativeGeofenceApiImpl
import com.steffaanus.native_geofence.generated.NativeGeofenceApi
import io.flutter.embedding.engine.plugins.FlutterPlugin

class NativeGeofencePlugin : FlutterPlugin {
    private var context: Context? = null

    companion object {
        @JvmStatic
        private val TAG = "NativeGeofencePlugin"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        NativeGeofenceApi.setUp(
            binding.binaryMessenger,
            NativeGeofenceApiImpl(binding.applicationContext, binding.binaryMessenger)
        )
        Log.d(TAG, "NativeGeofenceApi setup complete.")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
    }
}
