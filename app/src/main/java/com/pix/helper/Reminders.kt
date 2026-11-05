package com.pix.helper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object Reminders {
    fun schedule(ctx: Context, id: Int, at: Long, text: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (r in Store.reminders()) {
            val at = r.optLong("at")
            val id = r.optInt("id")
            if (at > now) schedule(ctx, id, at, r.optString("text")) else Store.removeReminder(id)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "Напоминание"
        Store.removeReminder(id)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, App.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⏰ Пикс напоминает")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + id, n)
        Store.log("remind", text)
        Bus.emit(Event("remind", text))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Reminders.rescheduleAll(ctx)
        Summary.schedule(ctx)
        if (Store.overlayEnabled && Settings.canDrawOverlays(ctx)) {
            try {
                OverlayService.start(ctx)
            } catch (_: Exception) {
            }
        }
    }
}
