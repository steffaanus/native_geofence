package com.steffaanus.native_geofence.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.steffaanus.native_geofence.Constants

class Notifications {
    companion object {
        fun createBackgroundWorkerNotification(context: Context): Notification {
            // Background Worker notification is only needed for Android 30 and below (30% of users
            // as of Jan 2025), so we are re-using the Foreground Service notification.
            return createForegroundServiceNotification(context)
        }

        fun createForegroundServiceNotification(context: Context): Notification {
            val channelId = "native_geofence_plugin_channel"
            val channel = NotificationChannel(
                channelId,
                "Geofence Events",
                // This has to be at least IMPORTANCE_LOW.
                // Source: https://developer.android.com/develop/background-work/services/foreground-services#start
                NotificationManager.IMPORTANCE_LOW
            )

            @SuppressLint("DiscouragedApi") // Can't use R syntax in Flutter plugin.
            val imageId = context.resources.getIdentifier("ic_launcher", "mipmap", context.packageName)

            // Read notification configuration from SharedPreferences
            val prefs = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            val title = prefs.getString(
                Constants.FOREGROUND_NOTIFICATION_TITLE_KEY,
                Constants.DEFAULT_NOTIFICATION_TITLE
            ) ?: Constants.DEFAULT_NOTIFICATION_TITLE
            
            val text = prefs.getString(
                Constants.FOREGROUND_NOTIFICATION_TEXT_KEY,
                Constants.DEFAULT_NOTIFICATION_TEXT
            ) ?: Constants.DEFAULT_NOTIFICATION_TEXT

            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )
            return NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(imageId)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
