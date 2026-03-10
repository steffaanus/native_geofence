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
        
        // Timeout for waiting on pending callbacks before forcing cleanup
        private const val CALLBACK_TIMEOUT_MS = 30_000L // 30 seconds
        
        // Enum for timeout action to avoid nested lock acquisition
        private enum class TimeoutAction {
            NONE,           // No action needed (callback ID changed)
            DESTROY_ENGINE, // Destroy engine and stop service
            FAIL_EVENT      // Fail the current event and continue
        }
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
    
    // Track engine start retry attempts
    private var engineStartRetries = 0
    
    // Service state protection to prevent race conditions
    @Volatile
    private var isServiceStopping = false
    private val serviceLock = Any()
    
    // Main thread handler for posting callbacks and timeouts
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track pending callbacks to prevent JNI crashes
    // This ensures the FlutterEngine is not destroyed while callbacks are in flight
    // Uses unique callback IDs to prevent race conditions between timeout and completion
    // Note: @Volatile not needed as all access is within synchronized(serviceLock) blocks
    private var pendingCallbackId: Long = 0  // 0 means no pending callback
    private var nextCallbackId: Long = 1     // Monotonic counter for callback IDs
    private var callbackTimeoutRunnable: Runnable? = null

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
        
        // Get the binary messenger from the Flutter engine
        val messenger = flutterEngine?.dartExecutor?.binaryMessenger
        if (messenger == null) {
            Log.e(TAG, "Flutter engine not ready for sync - engine or messenger is null")
            stopFlutterEngineAndService()
            return
        }
        
        // Use the service's own NativeGeofenceApiImpl with the service's BinaryMessenger
        // This ensures we have a valid main thread context and prevents JNI crashes
        val apiImpl = com.steffaanus.native_geofence.api.NativeGeofenceApiImpl(
            applicationContext,
            messenger
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
            mainHandler
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
     * Implements callback synchronization to prevent JNI crashes when engine is destroyed
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

        // Track pending callbacks with unique ID to prevent race condition
        // Each callback gets a unique ID so we can correctly match timeout vs completion
        val currentCallbackId: Long
        val timeoutRunnable: Runnable
        synchronized(serviceLock) {
            if (isServiceStopping) {
                Log.w(TAG, "Service is stopping, skipping event processing")
                return
            }
            currentCallbackId = nextCallbackId++
            pendingCallbackId = currentCallbackId
            Log.d(TAG, "Starting callback with ID: $currentCallbackId")

            // Set up timeout to prevent waiting indefinitely for callbacks
            // timeoutRunnable is assigned inside synchronized block to ensure visibility
            // Note: We avoid nested lock acquisition by determining action inside the lock
            // but executing it outside to prevent potential deadlocks
            timeoutRunnable = Runnable {
                // Determine what action to take inside the synchronized block
                val action: TimeoutAction
                synchronized(serviceLock) {
                    // Only process timeout if this is still the current callback
                    if (pendingCallbackId == currentCallbackId) {
                        Log.w(TAG, "Callback timeout after ${CALLBACK_TIMEOUT_MS}ms for ID: $currentCallbackId")
                        pendingCallbackId = 0  // Mark as no pending callback
                        action = if (isServiceStopping) {
                            TimeoutAction.DESTROY_ENGINE
                        } else {
                            // Timeout occurred but service not stopping yet - fail this event
                            TimeoutAction.FAIL_EVENT
                        }
                    } else {
                        Log.d(TAG, "Timeout fired but callback ID changed from $currentCallbackId to $pendingCallbackId, ignoring")
                        action = TimeoutAction.NONE
                    }
                }
                
                // Execute action outside the synchronized block to avoid nested lock acquisition
                when (action) {
                    TimeoutAction.DESTROY_ENGINE -> destroyEngineAndStopService(null)
                    TimeoutAction.FAIL_EVENT -> {
                        Log.e(TAG, "Callback timeout, failing event")
                        onEventProcessingFailed()
                    }
                    TimeoutAction.NONE -> { /* No action needed */ }
                }
            }
            callbackTimeoutRunnable = timeoutRunnable
        }
        mainHandler.postDelayed(timeoutRunnable, CALLBACK_TIMEOUT_MS)

        Log.d(TAG, "Triggering geofence callback in Dart")

        try {
            val nativeGeofenceTriggerApi = NativeGeofenceTriggerApi(engine.dartExecutor.binaryMessenger)
            
            nativeGeofenceTriggerApi.geofenceTriggered(params.toApi()) {
                // Check if this callback is still the current one (not timed out or superseded)
                // Use flags to make control flow clearer and avoid nested lock/action pattern
                val wasCurrentCallback: Boolean
                val shouldDestroyEngine: Boolean
                synchronized(serviceLock) {
                    // Cancel this callback's specific timeout (use local reference to avoid race condition)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    
                    wasCurrentCallback = (pendingCallbackId == currentCallbackId)
                    
                    if (wasCurrentCallback) {
                        pendingCallbackId = 0  // Mark as no pending callback
                        Log.d(TAG, "Callback completed with ID: $currentCallbackId")
                        shouldDestroyEngine = isServiceStopping
                    } else {
                        Log.d(TAG, "Callback ID $currentCallbackId completed but current is $pendingCallbackId - skipping duplicate processing")
                        shouldDestroyEngine = false
                    }
                }
                
                // Execute actions outside synchronized block to avoid nested lock acquisition
                if (wasCurrentCallback) {
                    if (shouldDestroyEngine) {
                        Log.d(TAG, "All callbacks complete, safe to destroy engine")
                        destroyEngineAndStopService(null)
                    } else {
                        try {
                            onEventProcessed()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onEventProcessed: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger geofence callback: ${e.message}", e)
            
            // Clean up timeout and pending callback (use local reference to avoid race condition)
            mainHandler.removeCallbacks(timeoutRunnable)
            synchronized(serviceLock) {
                if (pendingCallbackId == currentCallbackId) {
                    pendingCallbackId = 0
                }
            }
            
            onEventProcessingFailed()
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
            mainHandler.post {
                flutterEngine?.destroy()
                flutterEngine = null
                serviceApiImpl = null
            }
            
            // Schedule retry with exponential backoff
            mainHandler.postDelayed({
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
     * Waits for pending callbacks before destroying engine to prevent JNI crashes
     */
    private fun stopFlutterEngineAndService() {
        val runnableToCancel: Runnable?
        synchronized(serviceLock) {
            if (isServiceStopping) {
                Log.d(TAG, "Service already stopping, ignoring duplicate stop request")
                return
            }
            isServiceStopping = true
            Log.d(TAG, "Service stopping initiated")
            
            // Capture the runnable reference inside the synchronized block for thread safety
            runnableToCancel = callbackTimeoutRunnable
            
            // Don't destroy if a callback is pending - wait for it to complete
            if (pendingCallbackId != 0L) {
                Log.d(TAG, "Waiting for callback ID $pendingCallbackId before destroying engine")
                return
            }
        }
        
        destroyEngineAndStopService(runnableToCancel)
    }
    
    /**
     * Actually destroy the Flutter engine and stop the service
     * Only called when all pending callbacks have completed or timed out
     * @param runnableToCancel The timeout runnable to cancel, captured inside synchronized block
     */
    private fun destroyEngineAndStopService(runnableToCancel: Runnable?) {
        // Persist remaining queue before stopping (if service is killed, events are recovered)
        if (eventQueue.isNotEmpty()) {
            Log.w(TAG, "Stopping service with ${eventQueue.size} events still in queue - persisting to disk")
            persistQueue()
        } else {
            // Clear persisted queue if empty
            EventQueuePersistence.clearQueue(this)
        }
        
        // Cancel any pending timeout callbacks
        // The runnable reference was captured inside synchronized block in stopFlutterEngineAndService()
        runnableToCancel?.let {
            mainHandler.removeCallbacks(it)
        }
        
        mainHandler.post {
            try {
                flutterEngine?.destroy()
                Log.d(TAG, "Flutter engine destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying Flutter engine: ${e.message}", e)
            }
            flutterEngine = null
            serviceApiImpl = null
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
                try {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}", e)
                }
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
            
            // Reset pending callback ID since we're being destroyed anyway
            pendingCallbackId = 0
        }
        
        // Cancel any pending timeout callbacks
        callbackTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        
        // Clean up Flutter engine if still running
        mainHandler.post {
            try {
                flutterEngine?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying Flutter engine in onDestroy: ${e.message}", e)
            }
            flutterEngine = null
            serviceApiImpl = null
        }
        
        // Release wake lock if still held
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                    Log.d(TAG, "Wake lock released in onDestroy")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}", e)
                }
            }
        }
        wakeLock = null
        
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
