package com.pix.helper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // «чёрный ящик»: запоминаем причину падения, чтобы показать её при следующем запуске
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e).take(2500)
                getSharedPreferences("pix", MODE_PRIVATE).edit()
                    .putString("lastCrash", "${java.util.Date()} [${t.name}]\n$trace").commit()
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Пикс на экране", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Напоминания и срочное", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DIGEST, "Выжимки из групп", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        lateinit var instance: App
        const val CH_SERVICE = "pix_service"
        const val CH_REMIND = "pix_remind"
        const val CH_DIGEST = "pix_digest"
    }
}
