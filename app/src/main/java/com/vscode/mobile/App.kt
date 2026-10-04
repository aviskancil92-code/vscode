package com.vscode.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            "vscode_server",
            "Server Linux",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "Status code-server yang berjalan di latar belakang"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
