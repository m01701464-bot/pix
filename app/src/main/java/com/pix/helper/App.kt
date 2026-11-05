package com.pix.helper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Пикс на экране", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Напоминания и срочное", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        lateinit var instance: App
        const val CH_SERVICE = "pix_service"
        const val CH_REMIND = "pix_remind"
    }
}
