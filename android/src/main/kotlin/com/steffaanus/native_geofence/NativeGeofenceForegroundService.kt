package com.steffaanus.native_geofence

import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
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
import com.steffaanus.native_geofence.util.EventQueuePersistence
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
        // Increased from 50 to 200 to better handle burst scenarios
        private const val MAX_QUEUE_SIZE = 200
        
        // Maximum number of retries for Flutter engine startup failures
        private const val MAX_ENGINE_START_RETRIES = 3
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
    
    // FIX 3: Track engine start retry attempts
    private var engineStartRetries = 0
    
    // FIX 4: Service state protection to prevent race conditions
    @Volatile
    private var isServiceStopping = false
    private val serviceLock = Any()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        
        // Start foreground with notification
        val notification = Notifications.createForegroundServiceNotification(this)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        
        // Acquire wake lock to prevent CPU sleep during processing
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            wakeLock = newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.ISOLATE_HOLDER_WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
            }
        }
        
        val persistedEvents = EventQueuePersistence.loadQueue(this)
        if (persistedEvents.isNotEmpty()) {
            Log.d(TAG, "Restored ${persistedEvents.size} events from disk")
            eventQueue.addAll(persistedEvents)
            EventQueuePersistence.clearQueue(this) // Clear from disk now that they're in memory
        }
        
        Log.d(TAG, "Foreground service created with notification ID=$NOTIFICATION_ID")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_PROCESS_GEOFENCE -> {
                handleGeofenceEvent(intent)
            }
            Constants.ACTION_SYNC_GEOFENCES -> {
                handleSyncGeofences(intent)
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
        // Check if service is stopping to prevent race conditions
        synchronized(serviceLock) {
            if (isServiceStopping) {
                Log.w(TAG, "Service is stopping, rejecting new event. Event will be persisted and processed on next start.")
                // Event will be picked up from persisted queue on next service start
                return
            }
        }
        
        val jsonData = intent.getStringExtra(Constants.EXTRA_GEOFENCE_CALLBACK_PARAMS)
        if (jsonData == null) {
            Log.e(TAG, "No geofence callback params in intent")
            return
        }

        try {
            val params = Json.decodeFromString<GeofenceCallbackParamsStorage>(jsonData)
            
            // Check queue size to prevent memory issues
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                Log.e(TAG, "EVENT QUEUE FULL (size=$MAX_QUEUE_SIZE) - DROPPING OLDEST EVENT!")
                eventQueue.poll() // Remove oldest event
            }
            
            eventQueue.offer(params)
            Log.d(TAG, "Added event to queue. Queue size: ${eventQueue.size}")

            // Persist queue to disk after processing event
            persistQueue()
            
            // Start processing if not already processing
            processNextEventIfIdle()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse geofence callback params: ${e.message}", e)
        }
    }

    /**
     * Handle geofence sync operation using the service's own FlutterEngine and BinaryMessenger.
     * This prevents JNI crashes that occur when using an invalid BinaryMessenger from BroadcastReceiver context.
     */
    private fun handleSyncGeofences(intent: Intent) {
        val force = intent.getBooleanExtra(Constants.EXTRA_FORCE_SYNC, false)
        Log.d(TAG, "Geofence sync requested (force=$force)")
        
        // Use the service's own NativeGeofenceApiImpl with the service's BinaryMessenger
        // This ensures we have a valid main thread context and prevents JNI crashes
        val apiImpl = com.steffaanus.native_geofence.api.NativeGeofenceApiImpl(
            applicationContext,
            flutterEngine?.dartExecutor?.binaryMessenger
        )
        
        apiImpl.syncGeofences(force)
        
        // Stop service after sync is complete
        Log.d(TAG, "Geofence sync completed, stopping service")
        stopSelf()
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
        // Reset retry counter on new engine start attempt
        engineStartRetries = 0
        
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
                onEngineStartupFailed()
                return@ensureInitializationCompleteAsync
            }

            flutterEngine?.let { engine ->
                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Failed to find callback dispatcher")
                    onEngineStartupFailed()
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
                // Reset retry counter on successful start
                engineStartRetries = 0
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
        
        // Persist queue to disk after processing event
        persistQueue()
        
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
     * Called when Flutter engine startup fails
     * Implements retry mechanism with exponential backoff
     */
    private fun onEngineStartupFailed() {
        if (flutterEngine == null && engineStartRetries < MAX_ENGINE_START_RETRIES) {
            engineStartRetries++
            val retryDelayMs = 1000L * engineStartRetries // 1s, 2s, 3s exponential backoff
            
            Log.w(TAG, "Flutter engine startup failed. Retry attempt $engineStartRetries/$MAX_ENGINE_START_RETRIES in ${retryDelayMs}ms")
            
            // Clean up failed engine before retry
            Handler(Looper.getMainLooper()).post {
                flutterEngine?.destroy()
                flutterEngine = null
                serviceApiImpl = null
            }
            
            // Schedule retry with exponential backoff
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Retrying Flutter engine startup (attempt $engineStartRetries)")
                startFlutterEngine()
            }, retryDelayMs)
            
            return
        }
        
        // All retries exhausted or engine exists but still failing
        Log.e(TAG, "Flutter engine startup failed after $engineStartRetries retries. Dropping event.")
        onEventProcessingFailed()
    }
    
    /**
     * Called when event processing fails (after all retries exhausted)
     */
    private fun onEventProcessingFailed() {
        Log.e(TAG, "Event processing failed - dropping event")
        
        // Remove failed event from queue
        val droppedEvent = eventQueue.poll()
        if (droppedEvent != null) {
            Log.w(TAG, "Dropped event due to processing failure")
        }
        
        // Reset retry counter for next event
        engineStartRetries = 0
        
        // Persist queue after dropping failed event
        persistQueue()
        
        // Try to process next event if available
        if (eventQueue.isNotEmpty()) {
            Log.d(TAG, "Attempting to process next event after failure")
            startTime = System.currentTimeMillis()
            
            // If engine is null, restart it for the next event
            if (flutterEngine == null) {
                startFlutterEngine()
            } else {
                processCurrentEvent()
            }
        } else {
            isProcessing = false
            stopFlutterEngineAndService()
        }
    }

    /**
     * Stop the Flutter engine and the service
     * Protected against duplicate calls and race conditions
     */
    private fun stopFlutterEngineAndService() {
        synchronized(serviceLock) {
            if (isServiceStopping) {
                Log.d(TAG, "Service already stopping, ignoring duplicate stop request")
                return
            }
            isServiceStopping = true
            Log.d(TAG, "Service stopping initiated")
        }
        
        // Persist remaining queue before stopping (if service is killed, events are recovered)
        if (eventQueue.isNotEmpty()) {
            Log.w(TAG, "Stopping service with ${eventQueue.size} events still in queue - persisting to disk")
            persistQueue()
        } else {
            // Clear persisted queue if empty
            EventQueuePersistence.clearQueue(this)
        }
        
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
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground: ${e.message}")
        }
        
        // Stop the service
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping self: ${e.message}")
        }
        
        Log.d(TAG, "Foreground service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Mark service as stopping if not already marked
        synchronized(serviceLock) {
            if (!isServiceStopping) {
                Log.w(TAG, "onDestroy called but service wasn't marked as stopping - persisting queue")
                isServiceStopping = true
                
                // Persist any remaining events
                if (eventQueue.isNotEmpty()) {
                    persistQueue()
                }
            }
        }
        
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
    
    /**
     * Persist the current event queue to disk
     */
    private fun persistQueue() {
        try {
            EventQueuePersistence.saveQueue(this, eventQueue.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist queue: ${e.message}", e)
        }
    }
}
