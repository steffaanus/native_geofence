package com.steffaanus.native_geofence

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.steffaanus.native_geofence.api.NativeGeofenceServiceApiImpl
import com.steffaanus.native_geofence.generated.GeofenceCallbackParams
import com.steffaanus.native_geofence.generated.NativeGeofenceBackgroundApi
import com.steffaanus.native_geofence.generated.NativeGeofenceTriggerApi
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import com.steffaanus.native_geofence.util.Notifications
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.view.FlutterCallbackInformation
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

/**
 * ForegroundService that processes geofence events sequentially using a queue.
 * This service starts a Flutter engine to execute Dart callbacks for each geofence event.
 */
class NativeGeofenceForegroundService : Service() {
    companion object {
        @JvmStatic
        private val TAG = "NativeGeofenceForegroundService"

        private const val NOTIFICATION_ID = 938130
        private val WAKE_LOCK_TIMEOUT = 5.minutes
        
        // Maximum number of events to queue to prevent memory issues
        private const val MAX_QUEUE_SIZE = 50
    }

    // Queue for sequential event processing
    private val eventQueue = ConcurrentLinkedQueue<GeofenceCallbackParamsStorage>()
    
    // Flutter engine for executing Dart callbacks
    private var flutterEngine: FlutterEngine? = null
    
    // Track if we're currently processing an event
    private var isProcessing = false
    
    // Track start time for performance logging
    private var startTime: Long = 0
    
    // API implementation for Flutter communication
    private var serviceApiImpl: NativeGeofenceServiceApiImpl? = null
    
    // Wake lock to keep CPU awake during processing
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Flutter loader
    private val flutterLoader = FlutterInjector.instance().flutterLoader()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        
        // Start foreground with notification
        val notification = Notifications.createForegroundServiceNotification(this)
        startForeground(NOTIFICATION_ID, notification)
        
        // Acquire wake lock to prevent CPU sleep during processing
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            wakeLock = newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.ISOLATE_HOLDER_WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
            }
        }
        
        Log.d(TAG, "Foreground service created with notification ID=$NOTIFICATION_ID")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_PROCESS_GEOFENCE -> {
                handleGeofenceEvent(intent)
            }
            Constants.ACTION_SHUTDOWN -> {
                Log.d(TAG, "Shutdown requested")
                stopSelfAndCleanup()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    /**
     * Handle incoming geofence event by adding it to the queue and processing if idle
     */
    private fun handleGeofenceEvent(intent: Intent) {
        val jsonData = intent.getStringExtra(Constants.EXTRA_GEOFENCE_CALLBACK_PARAMS)
        if (jsonData == null) {
            Log.e(TAG, "No geofence callback params in intent")
            return
        }

        try {
            val params = Json.decodeFromString<GeofenceCallbackParamsStorage>(jsonData)
            
            // Check queue size to prevent memory issues
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                Log.w(TAG, "Event queue full (size=$MAX_QUEUE_SIZE), dropping oldest event")
                eventQueue.poll() // Remove oldest event
            }
            
            eventQueue.offer(params)
            Log.d(TAG, "Added event to queue. Queue size: ${eventQueue.size}")
            
            // Start processing if not already processing
            processNextEventIfIdle()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse geofence callback params: ${e.message}", e)
        }
    }

    /**
     * Start processing the next event if we're not currently processing
     */
    private fun processNextEventIfIdle() {
        if (!isProcessing && eventQueue.isNotEmpty()) {
            isProcessing = true
            startTime = System.currentTimeMillis()
            
            if (flutterEngine == null) {
                Log.d(TAG, "Starting Flutter engine for event processing")
                startFlutterEngine()
            } else {
                Log.d(TAG, "Flutter engine already running, processing next event")
                processCurrentEvent()
            }
        }
    }

    /**
     * Initialize and start the Flutter engine
     */
    private fun startFlutterEngine() {
        flutterEngine = FlutterEngine(applicationContext)

        if (!flutterLoader.initialized()) {
            flutterLoader.startInitialization(applicationContext)
        }

        flutterLoader.ensureInitializationCompleteAsync(
            applicationContext,
            null,
            Handler(Looper.getMainLooper())
        ) {
            val callbackHandle = getSharedPreferences(
                Constants.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE
            ).getLong(Constants.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
            
            if (callbackHandle == 0L) {
                Log.e(TAG, "No callback dispatcher registered")
                onEventProcessingFailed()
                return@ensureInitializationCompleteAsync
            }

            flutterEngine?.let { engine ->
                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Failed to find callback dispatcher")
                    onEventProcessingFailed()
                    return@ensureInitializationCompleteAsync
                }

                // Set up API for Flutter communication
                serviceApiImpl = NativeGeofenceServiceApiImpl(applicationContext, this)
                NativeGeofenceBackgroundApi.setUp(
                    engine.dartExecutor.binaryMessenger,
                    serviceApiImpl
                )

                // Execute Dart callback
                engine.dartExecutor.executeDartCallback(
                    DartCallback(
                        applicationContext.assets,
                        flutterLoader.findAppBundlePath(),
                        callbackInfo
                    )
                )
                
                Log.d(TAG, "Flutter engine started successfully")
            }
        }
    }

    /**
     * Called by the API implementation when Flutter is ready to receive events
     */
    fun triggerApiReady() {
        val engine = flutterEngine
        if (engine == null) {
            Log.e(TAG, "FlutterEngine was null when triggerApiReady called")
            onEventProcessingFailed()
            return
        }

        processCurrentEvent()
    }

    /**
     * Process the current event at the head of the queue
     */
    private fun processCurrentEvent() {
        val params = eventQueue.peek()
        if (params == null) {
            Log.w(TAG, "No event to process")
            isProcessing = false
            stopFlutterEngineAndService()
            return
        }

        val engine = flutterEngine
        if (engine == null) {
            Log.e(TAG, "FlutterEngine is null, cannot process event")
            onEventProcessingFailed()
            return
        }

        val nativeGeofenceTriggerApi = NativeGeofenceTriggerApi(engine.dartExecutor.binaryMessenger)
        Log.d(TAG, "Triggering geofence callback in Dart")

        nativeGeofenceTriggerApi.geofenceTriggered(params.toApi()) {
            onEventProcessed()
        }
    }

    /**
     * Called when an event has been successfully processed
     */
    private fun onEventProcessed() {
        val processingDuration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Event processed successfully in ${processingDuration}ms")
        
        // Remove processed event from queue
        eventQueue.poll()
        
        // Check if there are more events to process
        if (eventQueue.isNotEmpty()) {
            Log.d(TAG, "Processing next event. Remaining in queue: ${eventQueue.size}")
            startTime = System.currentTimeMillis()
            processCurrentEvent()
        } else {
            Log.d(TAG, "All events processed, stopping service")
            isProcessing = false
            stopFlutterEngineAndService()
        }
    }

    /**
     * Called when event processing fails
     */
    private fun onEventProcessingFailed() {
        Log.e(TAG, "Event processing failed")
        
        // Remove failed event from queue
        eventQueue.poll()
        
        // Try to process next event if available
        if (eventQueue.isNotEmpty()) {
            Log.d(TAG, "Attempting to process next event after failure")
            startTime = System.currentTimeMillis()
            processCurrentEvent()
        } else {
            isProcessing = false
            stopFlutterEngineAndService()
        }
    }

    /**
     * Stop the Flutter engine and the service
     */
    private fun stopFlutterEngineAndService() {
        Handler(Looper.getMainLooper()).post {
            flutterEngine?.destroy()
            flutterEngine = null
            serviceApiImpl = null
            Log.d(TAG, "Flutter engine destroyed")
        }
        
        stopSelfAndCleanup()
    }

    /**
     * Stop the service and release resources
     */
    private fun stopSelfAndCleanup() {
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
        
        // Stop foreground and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // Stop the service
        stopSelf()
        
        Log.d(TAG, "Foreground service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up Flutter engine if still running
        Handler(Looper.getMainLooper()).post {
            flutterEngine?.destroy()
            flutterEngine = null
        }
        
        // Release wake lock if still held
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        Log.d(TAG, "Service destroyed")
    }
}
