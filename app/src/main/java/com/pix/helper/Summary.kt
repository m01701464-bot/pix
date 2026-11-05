package com.pix.helper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Вечерняя сводка дня. */
object Summary {
    private const val REQ = 9_999

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, SummaryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        if (!Store.summaryEnabled) return
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, Store.summaryHour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 30_000) cal.add(Calendar.DAY_OF_MONTH, 1)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Собирает сводку за сегодня (вызывать не из главного потока). */
    fun build(): String {
        val since = startOfDay()
        val msgs = Store.inboxSince(since).filter { !it.optString("sender").startsWith("Я") }
        val replies = Store.logSince(since, "reply")
        if (msgs.isEmpty() && replies.isEmpty()) return "Сегодня тебе никто не писал — тишина 🌙"

        val lines = StringBuilder()
        lines.append("Входящие за сегодня:\n")
        for (m in msgs.takeLast(80)) {
            lines.append("[${Store.time(m.optLong("t"))}] ${m.optString("app")} · ${m.optString("chat")} · ")
                .append("${m.optString("sender")}: ${m.optString("text").take(200)}\n")
        }
        if (replies.isNotEmpty()) {
            lines.append("\nЧто Пикс ответил за хозяина:\n")
            for (r in replies.takeLast(40)) lines.append("[${Store.time(r.optLong("t"))}] ${r.optString("text").take(200)}\n")
        }
        return try {
            Brain.simple(
                "Ты Пикс, помощник на телефоне. Составь вечернюю сводку дня для хозяина по-русски: " +
                    "кто писал и о чём (сгруппируй по людям), что важно или ждёт его ответа, на что ты ответил сам. " +
                    "До 8 коротких строк, без markdown, можно эмодзи. Ничего не выдумывай.",
                lines.toString(), 700, reply = true
            ).ifBlank { fallback(msgs.size, replies.size) }
        } catch (e: Exception) {
            fallback(msgs.size, replies.size) + "\n(подробную сводку не удалось составить: ${e.message})"
        }
    }

    private fun fallback(nMsgs: Int, nReplies: Int) =
        "Сегодня: $nMsgs сообщ. от людей, Пикс ответил за тебя $nReplies раз. Подробности — в журнале."
}

class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                if (Store.summaryEnabled) {
                    val text = Summary.build()
                    val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    val n = Notification.Builder(ctx, App.CH_REMIND)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("📋 Сводка дня от Пикса")
                        .setContentText(text)
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build()
                    ctx.getSystemService(NotificationManager::class.java).notify(7_777, n)
                    Store.log("summary", text.take(300))
                    Bus.emit(Event("summary", text))
                }
            } catch (_: Exception) {
            } finally {
                Summary.schedule(ctx)
                result.finish()
            }
        }.start()
    }
}
