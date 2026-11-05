package com.pix.helper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Collections

/** Выжимка интересного из отслеживаемых групп. */
object Digest {
    private val running = Collections.synchronizedSet(HashSet<String>())

    /** Выжимка по одной группе с прошлой выжимки (вызывать не из главного потока). */
    fun forGroup(group: String, markRead: Boolean = true): String {
        val last = Store.digestAt(group)
        val since = if (last > 0) last else System.currentTimeMillis() - 24 * 3600_000L
        val msgs = Store.watchSince(group, since + 1)
        if (msgs.isEmpty()) return "«$group»: новых сообщений с прошлой выжимки нет"
        val lines = StringBuilder()
        for (m in msgs.takeLast(300)) {
            lines.append("[${Store.time(m.optLong("t"))}] ${m.optString("s")}: ${m.optString("x").take(200)}\n")
        }
        val owner = Store.ownerName.ifBlank { "хозяин" }
        val aliases = (listOf(Store.ownerName) + Store.ownerAliases.split(",", ";"))
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        val text = Brain.simple(
            "Ты Пикс, помощник на телефоне у человека по имени $owner. Тебе дают сообщения из группового чата «$group». " +
                "Расскажи хозяину коротко, как другу, что там было интересного:\n" +
                "— главные темы и новости;\n— договорённости, планы, даты, ссылки;\n" +
                "— где упоминали хозяина или что у него спрашивали" + (if (aliases.isNotEmpty()) " (его зовут: $aliases)" else "") + ";\n" +
                "— самый смешной или неожиданный момент, если был.\n" +
                "До 8 строк, без markdown, можно эмодзи. Скучное пропускай. Ничего не выдумывай — только то, что есть в сообщениях.",
            "Сообщений: ${msgs.size}\n\n$lines", 900, reply = false
        )
        if (markRead) Store.setDigestAt(group, System.currentTimeMillis())
        return "👥 $group (${msgs.size} сообщ.):\n$text"
    }

    /** Выжимка по всем отслеживаемым группам. */
    fun all(): String {
        val groups = Store.watchedChats()
        if (groups.isEmpty()) return "Пока нет сообщений из отслеживаемых групп. Добавь группу в «Следить за группами» и включи для неё уведомления «без звука»."
        return groups.joinToString("\n\n") {
            try {
                forGroup(it)
            } catch (e: Exception) {
                "«$it»: не получилось — ${e.message}"
            }
        }
    }

    /** Автовыжимка, когда набралось N новых сообщений. */
    fun maybeAuto(ctx: Context, group: String) {
        val every = Store.watchEvery
        if (every <= 0 || group in running) return
        val since = Store.digestAt(group).let { if (it > 0) it else 0L }
        if (Store.watchSince(group, since + 1).size < every) return
        running.add(group)
        try {
            val text = forGroup(group)
            notify(ctx, group, text)
            Store.log("digest", text.take(300))
            Bus.emit(Event("summary", text))
        } catch (e: Exception) {
            Store.log("error", "Выжимка «$group»: ${e.message}")
        } finally {
            running.remove(group)
        }
    }

    fun notify(ctx: Context, group: String, text: String) {
        val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, App.CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("👥 Что было в «$group»")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(8000 + Math.floorMod(group.hashCode(), 1000), n)
    }
}
