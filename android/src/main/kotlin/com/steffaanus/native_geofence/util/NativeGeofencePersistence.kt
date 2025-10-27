package com.steffaanus.native_geofence.util

import android.content.Context
import android.util.Log
import com.steffaanus.native_geofence.Constants
import com.steffaanus.native_geofence.generated.GeofenceWire
import com.steffaanus.native_geofence.model.GeofenceStorage
import com.steffaanus.native_geofence.model.LegacyGeofenceStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class NativeGeofencePersistence {
    companion object {
        @JvmStatic
        private val TAG = "NativeGeofencePersistence"

        @JvmStatic
        private val sharedPreferencesLock = Object()

        @JvmStatic
        private fun getGeofenceKey(id: String): String {
            return Constants.PERSISTENT_GEOFENCE_KEY_PREFIX + id
        }

        @JvmStatic
        fun saveGeofence(context: Context, geofence: GeofenceWire) {
            saveOrUpdateGeofence(context, GeofenceWires.toGeofenceStorage(geofence))
        }

        fun saveOrUpdateGeofence(context: Context, geofence: GeofenceStorage) {
             synchronized(sharedPreferencesLock) {
                val p = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                val jsonData = Json.encodeToString(geofence)
                var persistentGeofences =
                    p.getStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, null)
                persistentGeofences = if (persistentGeofences == null) {
                    HashSet()
                } else {
                    HashSet(persistentGeofences)
                }
                persistentGeofences.add(geofence.id)
                p.edit()
                    .putStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, persistentGeofences)
                    .putString(getGeofenceKey(geofence.id), jsonData)
                    .apply()
                Log.d(TAG, "Saved Geofence ID=${geofence.id} with status ${geofence.status} to storage.")
            }
        }

        @JvmStatic
        fun getAllGeofenceIds(context: Context): List<String> {
            synchronized(sharedPreferencesLock) {
                val p = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                val persistentGeofences =
                    p.getStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, null)
                        ?: return emptyList()
                Log.d(TAG, "There are ${persistentGeofences.size} Geofences saved.")
                return persistentGeofences.toList()
            }
        }

        @JvmStatic
        private fun getGeofence(context: Context, id: String): GeofenceStorage? {
            val p =
                context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            val jsonData = p.getString(getGeofenceKey(id), null)
            if (jsonData == null) {
                Log.e(TAG, "No data found for Geofence ID=${id} in storage.")
                return null
            }
            return try {
                Json.decodeFromString<GeofenceStorage>(jsonData)
            } catch (e: Exception) {
                // This might be an old geofence format. Try to migrate.
                try {
                    val legacyGeofence = Json.decodeFromString<LegacyGeofenceStorage>(jsonData)
                    val newGeofence = GeofenceWires.toGeofenceStorage(legacyGeofence)
                    // Save it in the new format for next time.
                    saveOrUpdateGeofence(context, newGeofence)
                    Log.i(TAG,"Successfully migrated geofence ${id} from legacy format.")
                    newGeofence
                } catch (e2: Exception) {
                     Log.e(
                        TAG,
                        "Failed to parse Geofence ID=${id} from storage. Data=${jsonData}. Error: $e"
                    )
                    null
                }
            }
        }

        fun getAllGeofences(context: Context): List<GeofenceStorage> {
            synchronized(sharedPreferencesLock) {
                val p = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                val persistentGeofences =
                    p.getStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, null)
                        ?: return emptyList()

                val result = mutableListOf<GeofenceStorage>()
                for (id in persistentGeofences) {
                    getGeofence(context, id)?.let { result.add(it) }
                }
                Log.d(TAG, "Retrieved ${result.size} Geofences from storage.")
                return result
            }
        }

        @JvmStatic
        fun removeGeofence(context: Context, geofenceId: String) {
            synchronized(sharedPreferencesLock) {
                val p = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                var persistentGeofences =
                    p.getStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, null)
                persistentGeofences = if (persistentGeofences == null) {
                    HashSet<String>()
                } else {
                    HashSet<String>(persistentGeofences)
                }
                persistentGeofences.remove(geofenceId)
                context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, persistentGeofences)
                    .remove(getGeofenceKey(geofenceId))
                    .apply()
                Log.d(TAG, "Removed Geofence ID=${geofenceId} from storage.")
            }
        }

        @JvmStatic
        fun removeAllGeofences(context: Context) {
            synchronized(sharedPreferencesLock) {
                val p = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                var persistentGeofences =
                    p.getStringSet(Constants.PERSISTENT_GEOFENCES_IDS_KEY, null)
                persistentGeofences = if (persistentGeofences == null) {
                    HashSet<String>()
                } else {
                    HashSet<String>(persistentGeofences)
                }
                val editor = context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                    .edit()
                    .remove(Constants.PERSISTENT_GEOFENCES_IDS_KEY)
                for (id in persistentGeofences) {
                    editor.remove(getGeofenceKey(id))
                }
                editor.apply()
                Log.d(TAG, "Removed ${persistentGeofences.size} Geofences from storage.")
            }
        }
    }
}
