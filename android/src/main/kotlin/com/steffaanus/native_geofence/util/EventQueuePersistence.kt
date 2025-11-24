package com.steffaanus.native_geofence.util

import android.content.Context
import android.util.Log
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.model.GeofenceCallbackParamsStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles persistence of the event queue to disk to prevent event loss
 * during service crashes or system kills.
 */
object EventQueuePersistence {
    private const val TAG = "EventQueuePersistence"
    private const val QUEUE_KEY = "persisted_event_queue"
    
    /**
     * Save the current event queue to SharedPreferences
     */
    fun saveQueue(context: Context, queue: List<GeofenceCallbackParamsStorage>) {
        try {
            val json = Json.encodeToString(queue)
            context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit()
                .putString(QUEUE_KEY, json)
                .apply()
            Log.d(TAG, "Saved ${queue.size} events to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue to disk: ${e.message}", e)
        }
    }
    
    /**
     * Load the event queue from SharedPreferences
     */
    fun loadQueue(context: Context): List<GeofenceCallbackParamsStorage> {
        try {
            val json = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .getString(QUEUE_KEY, null)
            
            if (json == null) {
                Log.d(TAG, "No persisted queue found")
                return emptyList()
            }
            
            val queue = Json.decodeFromString<List<GeofenceCallbackParamsStorage>>(json)
            Log.d(TAG, "Loaded ${queue.size} events from disk")
            return queue
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue from disk: ${e.message}", e)
            // Clear corrupted data
            clearQueue(context)
            return emptyList()
        }
    }
    
    /**
     * Clear the persisted queue from SharedPreferences
     */
    fun clearQueue(context: Context) {
        try {
            context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit()
                .remove(QUEUE_KEY)
                .apply()
            Log.d(TAG, "Cleared persisted queue from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear queue from disk: ${e.message}", e)
        }
    }
}