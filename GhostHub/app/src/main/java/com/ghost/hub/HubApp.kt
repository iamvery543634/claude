package com.ghost.hub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class HubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a newer Ghost app is ready"
            },
        )
    }

    companion object {
        const val CHANNEL = "updates"
    }
}
