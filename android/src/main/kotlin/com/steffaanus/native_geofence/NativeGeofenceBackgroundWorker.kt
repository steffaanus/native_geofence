package com.steffaanus.native_geofence

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.steffaanus.native_geofence.api.NativeGeofenceBackgroundApiImpl
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.generated.NativeGeofenceBackgroundApi
import com.steffaanus.native_geofence.generated.NativeGeofenceTriggerApi
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import com.steffaanus.native_geofence.util.EventQueuePersistence
import com.steffaanus.native_geofence.util.Notifications
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.view.FlutterCallbackInformation
import kotlinx.serialization.json.Json

class NativeGeofenceBackgroundWorker(
    private val context: Context,
    private val workerParams: WorkerParameters
) :
    ListenableWorker(context, workerParams) {
    companion object {
        const val TAG = "NativeGeofenceBackgroundWorker"
        // TODO: Consider using random ID.
        private const val NOTIFICATION_ID = 493620
        private val flutterLoader = FlutterInjector.instance().flutterLoader()
        
        // Key for tracking WorkManager processed events to avoid duplicates with persisted queue
        private const val PROCESSED_EVENTS_KEY = "workmanager_processed_events"
        
        // Maximum number of retries for Flutter engine startup failures
        private const val MAX_ENGINE_START_RETRIES = 3
    }

    private var flutterEngine: FlutterEngine? = null

    private var startTime: Long = 0

    private var completer: CallbackToFutureAdapter.Completer<Result>? = null

    private var resolvableFuture =
        CallbackToFutureAdapter.getFuture { completer ->
            this.completer = completer
            null
        }

    private var backgroundApiImpl: NativeGeofenceBackgroundApiImpl? = null
    
    // Track engine start retry attempts
    private var engineStartRetries = 0

    override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // This method does not need to be implemented for Android 31 (S) and above.
            return super.getForegroundInfoAsync()
        }
        val notification = Notifications.createBackgroundWorkerNotification(context)
        return Futures.immediateFuture(ForegroundInfo(NOTIFICATION_ID, notification))
    }

    override fun startWork(): ListenableFuture<Result> {
        startTime = System.currentTimeMillis()
        engineStartRetries = 0 // Reset retry counter
        
        startFlutterEngine()
        return resolvableFuture
    }
    
    /**
     * Start Flutter engine with retry capability
     */
    private fun startFlutterEngine() {
        flutterEngine = FlutterEngine(applicationContext)

        if (!flutterLoader.initialized()) {
            flutterLoader.startInitialization(applicationContext)
        }

        flutterLoader.ensureInitializationCompleteAsync(
            applicationContext,
            null,
            Handler(Looper.getMainLooper()),
        ) {
            val callbackHandle = context.getSharedPreferences(
                Constants.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE
            )
                .getLong(Constants.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
            if (callbackHandle == 0L) {
                Log.e(TAG, "No callback dispatcher registered.")
                onEngineStartupFailed()
                return@ensureInitializationCompleteAsync
            }

            flutterEngine?.let { engine ->
                val callbackInfo =
                    FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Failed to find callback dispatcher.")
                    onEngineStartupFailed()
                    return@ensureInitializationCompleteAsync
                }

                backgroundApiImpl = NativeGeofenceBackgroundApiImpl(context, this)
                NativeGeofenceBackgroundApi.setUp(
                    engine.dartExecutor.binaryMessenger,
                    NativeGeofenceBackgroundApiImpl(context, this)
                )

                engine.dartExecutor.executeDartCallback(
                    DartCallback(
                        context.assets,
                        flutterLoader.findAppBundlePath(),
                        callbackInfo
                    )
                )
                
                Log.d(TAG, "Flutter engine started successfully")
                // Reset retry counter on successful start
                engineStartRetries = 0
            }
        }
    }
    
    /**
     * Called when Flutter engine startup fails
     * Implements retry mechanism with exponential backoff
     */
    private fun onEngineStartupFailed() {
        if (engineStartRetries < MAX_ENGINE_START_RETRIES) {
            engineStartRetries++
            val retryDelayMs = 1000L * engineStartRetries // 1s, 2s, 3s exponential backoff
            
            Log.w(TAG, "Flutter engine startup failed. Retry attempt $engineStartRetries/$MAX_ENGINE_START_RETRIES in ${retryDelayMs}ms")
            
            // Clean up failed engine before retry
            Handler(Looper.getMainLooper()).post {
                flutterEngine?.destroy()
                flutterEngine = null
                backgroundApiImpl = null
            }
            
            // Schedule retry with exponential backoff
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Retrying Flutter engine startup (attempt $engineStartRetries)")
                startFlutterEngine()
            }, retryDelayMs)
            
            return
        }
        
        // All retries exhausted
        Log.e(TAG, "Flutter engine startup failed after $engineStartRetries retries.")
        stopEngine(Result.failure())
    }

    override fun onStopped() {
        stopEngine(null)
    }

    fun triggerApiReady() {
        val lEngine = flutterEngine
        if (lEngine == null) {
            Log.e(TAG, "FlutterEngine was null.")
            stopEngine(Result.failure())
            return
        }

        val nativeGeofenceTriggerApi =
            NativeGeofenceTriggerApi(lEngine.dartExecutor.binaryMessenger)
        Log.d(TAG, "NativeGeofenceTriggerApi setup complete.")

        val params = getGeofenceCallbackParams()
        if (params == null) {
            stopEngine(Result.failure())
            return
        }

        nativeGeofenceTriggerApi.geofenceTriggered(params, ) {
            stopEngine(Result.success())
        }
    }

    private fun stopEngine(result: Result?) {
        val fetchDuration = System.currentTimeMillis() - startTime

        // If work failed and there are persisted events in ForegroundService queue,
        // we should not clear them as they will be picked up on service restart
        // Only clear if work succeeded
        if (result?.javaClass == Result.success().javaClass) {
            Log.d(TAG, "Work completed successfully, no additional cleanup needed")
        } else if (result?.javaClass == Result.failure().javaClass) {
            Log.w(TAG, "Work failed, persisted events may need to be processed")
        }

        // No result indicates we were signalled to stop by WorkManager. The result is already
        // STOPPED, so no need to resolve another one.
        if (result != null) {
            this.completer?.set(result)
        }

        // If stopEngine is called from `onStopped`, it may not be from the main thread.
        Handler(Looper.getMainLooper()).post {
            flutterEngine?.destroy()
            flutterEngine = null
        }

        Log.d(TAG, "Work took ${fetchDuration}ms.")
    }

    private fun getGeofenceCallbackParams(): GeofenceCallbackParams? {
        val jsonData = workerParams.inputData.getString(Constants.WORKER_PAYLOAD_KEY)
        if (jsonData == null) {
            Log.e(TAG, "Worker payload was missing.")
            return null
        }

        try {
            return Json.decodeFromString<GeofenceCallbackParamsStorage>(jsonData).toApi()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to parse worker payload. Data=${jsonData}"
            )
            return null
        }
    }
}
