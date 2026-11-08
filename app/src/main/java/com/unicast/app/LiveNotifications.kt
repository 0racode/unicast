package com.unicast.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * "Unicast is live" push notifications. Every phone with the app subscribes to one Firebase
 * topic called "live", and the server sends a single message to that topic when you go live.
 * Everything here quietly does nothing if the Firebase config was not added at build time.
 */
object LiveNotifications {
    const val CHANNEL = "live"
    const val EPISODES_CHANNEL = "episodes"
    private const val TOPIC = "live"
    private const val EPISODES_TOPIC = "episodes"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "Live shows", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Tells you when Unicast goes live"
        manager.createNotificationChannel(channel)
        val episodes = NotificationChannel(EPISODES_CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT)
        episodes.description = "Tells you when a new episode is uploaded"
        manager.createNotificationChannel(episodes)
    }

    /** True when the app was built with a Firebase config. */
    fun available(context: Context): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    fun setSubscribed(context: Context, on: Boolean) {
        if (!available(context)) return
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (on) messaging.subscribeToTopic(TOPIC) else messaging.unsubscribeFromTopic(TOPIC)
        } catch (e: Exception) {
            // Google Play services missing or busy. Try again next time the app opens.
        }
    }

    fun setEpisodesSubscribed(context: Context, on: Boolean) {
        if (!available(context)) return
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (on) messaging.subscribeToTopic(EPISODES_TOPIC) else messaging.unsubscribeFromTopic(EPISODES_TOPIC)
        } catch (e: Exception) {
            // Try again next time the app opens.
        }
    }
}
