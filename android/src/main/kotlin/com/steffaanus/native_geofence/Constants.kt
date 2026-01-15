package com.steffaanus.native_geofence

class Constants {
    companion object {
        private const val PACKAGE_NAME = "com.steffaanus.native_geofence"

        const val SHARED_PREFERENCES_KEY = "native_geofence_plugin_cache"
        const val PERSISTENT_GEOFENCES_IDS_KEY = "persistent_geofences_ids"
        const val PERSISTENT_GEOFENCE_KEY_PREFIX = "persistent_geofence/"

        const val CALLBACK_HANDLE_KEY = "$PACKAGE_NAME.callback_handle"
        const val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatch_handler"

        const val ACTION_SHUTDOWN = "SHUTDOWN"
        const val ACTION_PROCESS_GEOFENCE = "PROCESS_GEOFENCE"
        const val ACTION_SYNC_GEOFENCES = "SYNC_GEOFENCES"

        const val WORKER_PAYLOAD_KEY = "$PACKAGE_NAME.worker_payload"
        const val GEOFENCE_CALLBACK_WORK_GROUP = "geofence_callback_work_group"
        
        const val EXTRA_GEOFENCE_CALLBACK_PARAMS = "$PACKAGE_NAME.geofence_callback_params"
        const val EXTRA_FORCE_SYNC = "force_sync"

        const val ISOLATE_HOLDER_WAKE_LOCK_TAG = "$PACKAGE_NAME:wake_lock"
        
        const val FOREGROUND_NOTIFICATION_TITLE_KEY = "$PACKAGE_NAME.notification_title"
        const val FOREGROUND_NOTIFICATION_TEXT_KEY = "$PACKAGE_NAME.notification_text"
        const val FOREGROUND_NOTIFICATION_ICON_KEY = "$PACKAGE_NAME.notification_icon"
        
        // Default values for foreground service notification
        const val DEFAULT_NOTIFICATION_TITLE = "Processing geofence event."
        const val DEFAULT_NOTIFICATION_TEXT = "We noticed you are near a key location and are checking if we can help."
        const val DEFAULT_NOTIFICATION_ICON = "ic_launcher"
    }
}
