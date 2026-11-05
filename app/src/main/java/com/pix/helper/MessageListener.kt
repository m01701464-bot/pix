package com.pix.helper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Читает уведомления мессенджеров. Когда включён режим «Я занят» —
 * пишет ответ через Claude и отправляет его кнопкой «Ответить» из уведомления.
 */
class MessageListener : NotificationListenerService() {

    class Target(val pkg: String, val app: String, val chat: String, val action: Notification.Action, val t: Long)

    private class Parsed(
        val chat: String, val sender: String, val text: String, val group: Boolean,
        val self: Boolean = false, val thread: List<String> = emptyList()
    )

    /** Ждём, ответит ли хозяин сам; если нет — отвечает Пикс. */
    private class Pending(val key: String, val pkg: String, val app: String, var msg: Parsed, var action: Notification.Action, val since: Long) {
        var task: Runnable? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Pending>()

    companion object {
        private const val TAG = "Pix"
        val targets = ConcurrentHashMap<String, Target>()
        private val lastSent = ConcurrentHashMap<String, String>()
        private val lastReplyAt = ConcurrentHashMap<String, Long>()
        /** когда хозяин сам открыл/ответил в чат */
        private val userActedAt = ConcurrentHashMap<String, Long>()
        private val keyConv = ConcurrentHashMap<String, String>()
        private val seen = LinkedHashMap<String, Long>()
        // несколько потоков — один медленный ответ не задерживает остальные
        private val exec = Executors.newFixedThreadPool(3)

        fun sendReply(ctx: Context, action: Notification.Action, text: String): Boolean {
            val inputs = action.remoteInputs ?: return false
            if (inputs.isEmpty()) return false
            val intent = Intent()
            val bundle = Bundle()
            for (ri in inputs) bundle.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            return try {
                action.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            } catch (e: Exception) {
                Log.w(TAG, "send", e)
                false
            }
        }

        fun chatNames(): String {
            val l = targets.values.sortedByDescending { it.t }
            if (l.isEmpty()) return "Сейчас нет уведомлений, на которые можно ответить"
            return l.joinToString("\n") { "${it.app} · ${it.chat} (сообщение в ${Store.time(it.t)})" }
        }

        fun replyByName(name: String, text: String): String {
            val q = name.trim().lowercase()
            if (q.isEmpty() || text.isBlank()) return "Нужно указать чат и текст"
            val sorted = targets.values.sortedByDescending { it.t }
            val match = sorted.firstOrNull { it.chat.lowercase() == q }
                ?: sorted.firstOrNull { it.chat.lowercase().contains(q) || q.contains(it.chat.lowercase()) }
                ?: return "Не нашёл чат «$name» среди уведомлений. Можно ответить только тем, чьё уведомление ещё висит. Доступно:\n" + chatNames()
            val conv = "${match.pkg}|${match.chat}"
            return if (sendReply(App.instance, match.action, text)) {
                lastSent[conv] = text
                Store.addInbox(match.pkg, match.app, match.chat, "Я", text, false, true)
                Store.log("reply", "${match.app} · ${match.chat}: $text")
                "Отправлено: ${match.app} → ${match.chat}"
            } else {
                targets.remove(conv)
                "Не удалось отправить — уведомление уже исчезло"
            }
        }

        fun appLabel(ctx: Context, pkg: String): String = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            Store.KNOWN_APPS[pkg] ?: pkg
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "handle", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (pkg !in Store.apps) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val ex = n.extras ?: return
        val msg = parse(ex) ?: return

        val now = System.currentTimeMillis()
        val app = appLabel(this, pkg)
        val conv = "$pkg|${msg.chat}"

        keyConv[sbn.key] = conv
        if (msg.self) {
            if (lastSent[conv]?.trim() != msg.text.trim()) {
                userActedAt[conv] = now
                // учимся писать как хозяин
                if (Store.learnStyle) Store.addOwnSample(msg.text)
            }
            // хозяин (или Пикс) уже написал в этот чат — ждать больше нечего
            main.post { cancelPending(conv, if (lastSent[conv]?.trim() == msg.text.trim()) null else "ты ответил сам") }
            return
        }

        val sig = "$conv|${msg.sender}|${msg.text}"
        synchronized(seen) {
            val prev = seen[sig]
            if (prev != null && now - prev < 10 * 60_000L) return
            seen[sig] = now
            while (seen.size > 300) {
                val it = seen.keys.iterator()
                it.next()
                it.remove()
            }
        }
        // эхо нашего же ответа
        if (lastSent[conv]?.trim() == msg.text.trim()) return

        val action = findReply(n)
        if (action != null) targets[conv] = Target(pkg, app, msg.chat, action, now)

        val busy = Store.isBusy()
        Store.addInbox(pkg, app, msg.chat, msg.sender, msg.text, msg.group, !busy)
        Bus.emit(Event("inbox", "${msg.chat}: ${msg.text}"))

        if (busy && Store.notifyUrgent && msg.text.contains("срочн", ignoreCase = true)) {
            notifyUrgent(pkg, "${msg.sender} (${app})", msg.text)
        }

        if (action == null) return
        if (msg.group && !Store.replyGroups) return
        if (!Store.allowedChat(msg.chat, msg.sender)) return

        if (busy) {
            // режим «Я занят» — отвечаем сразу
            main.post { cancelPending(conv, null) }
            startReply(pkg, app, conv, msg, action, "busy")
        } else if (Store.silenceReply && Store.silenceSec <= 0) {
            // ожидание 0 секунд — отвечаем сразу
            startReply(pkg, app, conv, msg, action, "instant")
        } else if (Store.silenceReply) {
            // ждём, ответит ли хозяин сам
            val key = sbn.key
            main.post { schedule(conv, key, pkg, app, msg, action) }
        }
    }

    private var wake: android.os.PowerManager.WakeLock? = null

    /** Не даём процессору уснуть, пока ждём таймер или пишем ответ (иначе таймер «замирает»). */
    private fun keepAwake(ms: Long) {
        try {
            val w = wake ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pix:reply").also {
                    it.setReferenceCounted(false)
                    wake = it
                }
            w.acquire(ms)
        } catch (_: Exception) {
        }
    }

    private fun startReply(pkg: String, app: String, conv: String, msg: Parsed, action: Notification.Action, mode: String) {
        val now = System.currentTimeMillis()
        val last = lastReplyAt[conv] ?: 0L
        // в режиме «Я занят» не частим; в режиме тишины пауза в N минут уже сама ограничивает частоту
        if (mode == "busy" && now - last < Store.cooldownSec * 1_000L) return
        lastReplyAt[conv] = now
        keepAwake(120_000L)
        exec.execute { autoReply(pkg, app, conv, msg, action, mode) }
    }

    /** Таймер считается от первого сообщения, на которое хозяин не ответил. */
    private fun schedule(conv: String, key: String, pkg: String, app: String, msg: Parsed, action: Notification.Action) {
        val existing = pending[conv]
        if (existing != null) {
            existing.msg = msg
            existing.action = action
            return
        }
        val p = Pending(key, pkg, app, msg, action, System.currentTimeMillis())
        val task = Runnable { fire(conv) }
        p.task = task
        pending[conv] = p
        keepAwake(Store.silenceSec * 1_000L + 90_000L)
        main.postDelayed(task, Store.silenceSec * 1_000L)
    }

    private fun cancelPending(conv: String, reason: String?) {
        val p = pending.remove(conv) ?: return
        p.task?.let { main.removeCallbacks(it) }
        if (reason != null) Store.log("skip", "${p.msg.chat}: $reason — Пикс не вмешивается")
    }

    private fun fire(conv: String) {
        val p = pending.remove(conv) ?: return
        if (Store.isBusy() || !Store.silenceReply) return
        // если уведомление исчезло — значит хозяин открыл чат
        val stillUnread = try {
            activeNotifications?.any { it.key == p.key } ?: true
        } catch (e: Exception) {
            true
        }
        if (!stillUnread) {
            Store.log("skip", "${p.msg.chat}: ты прочитал сам — Пикс не вмешивается")
            return
        }
        startReply(p.pkg, p.app, conv, p.msg, p.action, "silence")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val key = sbn.key
        keyConv[key]?.let { userActedAt[it] = System.currentTimeMillis() }
        main.post {
            val convs = pending.filterValues { it.key == key }.keys.toList()
            for (c in convs) cancelPending(c, "ты открыл чат")
        }
    }

    @Suppress("DEPRECATION")
    private fun parse(ex: Bundle): Parsed? {
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val convTitle = ex.getCharSequence("android.conversationTitle")?.toString()?.trim()?.ifEmpty { null }
        var text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        var sender = title
        val self = selfName(ex)

        // вся лента сообщений из уведомления (старые + новые), чтобы понимать контекст
        val thread = ArrayList<String>()
        fun collect(a: Array<out android.os.Parcelable>?) {
            if (a == null) return
            for (item in a) {
                val b = item as? Bundle ?: continue
                val t = b.getCharSequence("text")?.toString()
                if (t.isNullOrBlank()) continue
                val snd = b.getCharSequence("sender")?.toString() ?: personName(b.get("sender_person"))
                val who = if (snd == null || (self != null && snd == self)) "Я" else snd
                thread.add("$who: ${t.trim()}")
            }
        }
        collect(ex.getParcelableArray("android.messages.historic"))

        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        collect(arr)
        if (arr != null && arr.isNotEmpty()) {
            val last = arr[arr.size - 1] as? Bundle
            if (last != null) {
                val t = last.getCharSequence("text")?.toString()
                if (!t.isNullOrBlank()) text = t
                val s = last.getCharSequence("sender")?.toString() ?: personName(last.get("sender_person"))
                if (s == null || (self != null && s == self)) {
                    // последнее сообщение в чате — от самого хозяина
                    val chat0 = convTitle ?: title
                    return if (chat0.isBlank()) null else Parsed(chat0, "Я", text.trim(), convTitle != null, self = true, thread = thread)
                }
                sender = s
            }
        }
        if (text.isBlank()) return null
        val chat = convTitle ?: title
        if (chat.isBlank()) return null
        val group = ex.getBoolean("android.isGroupConversation", false) || convTitle != null
        return Parsed(chat, sender.ifBlank { chat }, text.trim(), group, thread = thread)
    }

    @Suppress("DEPRECATION")
    private fun selfName(ex: Bundle): String? {
        ex.getCharSequence("android.selfDisplayName")?.let { return it.toString() }
        return personName(ex.get("android.messagingUser"))
    }

    private fun personName(o: Any?): String? {
        if (o == null || Build.VERSION.SDK_INT < 28) return null
        return (o as? android.app.Person)?.name?.toString()
    }

    private fun findReply(n: Notification): Notification.Action? {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (_: Exception) {
        }
        return all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    private fun autoReply(pkg: String, app: String, conv: String, msg: Parsed, action: Notification.Action, mode: String, attempt: Int = 0, startedAt: Long = System.currentTimeMillis()) {
        if (attempt > 0 && (userActedAt[conv] ?: 0L) > startedAt) {
            Store.log("skip", "${msg.chat}: ты уже ответил сам — повтор отменён")
            lastReplyAt.remove(conv)
            return
        }
        Bus.emit(Event("thinking"))
        try {
            val hist = Store.chatHistory(pkg, msg.chat, 30)
                .joinToString("\n") { "${it.optString("sender")}: ${it.optString("text")}" }
            val notif = msg.thread.takeLast(20).joinToString("\n")
            val prompt = "Мессенджер: $app\nЧат: ${msg.chat}" + (if (msg.group) " (групповой)" else "") +
                "\n\nЧто ты успел увидеть в этом чате (по уведомлениям, старое сверху):\n$hist" +
                (if (notif.isNotBlank()) "\n\nЛента из текущего уведомления (самое свежее внизу):\n$notif" else "") +
                "\n\nНовое сообщение от ${msg.sender}: ${msg.text}" +
                "\n\nПомни: ты видишь только часть диалога — сообщения, которые хозяин писал внутри приложения, сюда не попадают." +
                "\nОтветь по существу. Только текст ответа или SKIP."
            val reply = Brain.simple(autoSystem(mode, msg), prompt, 300, reply = true)
                .trim().trim('"', '«', '»').trim()
            if (reply.isEmpty() || reply.startsWith("SKIP", ignoreCase = true)) {
                Store.log("skip", "${msg.chat}: не стал отвечать на «${msg.text.take(50)}»")
                lastReplyAt.remove(conv)
                return
            }
            val pre = Store.prefix.trim()
            val full = if (pre.isEmpty()) reply else "$pre $reply"
            if (sendReply(this, action, full)) {
                lastSent[conv] = full
                Store.addInbox(pkg, app, msg.chat, "Я (через Пикса)", full, msg.group, true)
                Store.log("reply", "$app · ${msg.chat}: $reply")
                Bus.emit(Event("replied", "${msg.chat}: $reply"))
            } else {
                targets.remove(conv)
                lastReplyAt.remove(conv)
                Store.log("error", "Не смог отправить ответ в «${msg.chat}»")
            }
        } catch (e: Exception) {
            val m = e.message ?: e.toString()
            if (e is Gemini.ApiError && e.retryable && attempt < 3) {
                // сервер занят — попробуем ещё раз чуть позже, не сдаёмся
                Store.log("error", "$m — повторю через минуту (${msg.chat})")
                main.postDelayed({ exec.execute { autoReply(pkg, app, conv, msg, action, mode, attempt + 1, startedAt) } }, 60_000L)
            } else {
                lastReplyAt.remove(conv)
                Store.log("error", m)
                Bus.emit(Event("error", m))
            }
        } finally {
            Bus.emit(Event("done"))
        }
    }

    private fun autoSystem(mode: String, msg: Parsed): String {
        val owner = Store.ownerName.ifBlank { "хозяин телефона" }
        val now = SimpleDateFormat("EEEE, d MMMM, HH:mm", Locale("ru")).format(Date())
        val situation = if (mode == "busy") {
            "Служебно (собеседнику не сообщать): $owner сейчас не у телефона" +
                (if (Store.busyUntil > 0) " примерно до ${Store.time(Store.busyUntil)}" else "") + "."
        } else if (mode == "instant") {
            "Служебно (собеседнику не сообщать): $owner поручил тебе отвечать за него сразу."
        } else {
            "Служебно (собеседнику не сообщать): $owner не ответил ${Store.silenceText()}, поэтому отвечаешь ты."
        }
        return """Ты ведёшь переписку от лица человека по имени $owner — пишешь так, будто это он сам. Сейчас $now.
$situation
Пожелания $owner (важнее общих правил ниже): ${Store.persona}
${personBlock(msg)}${styleBlock(owner)}
Как отвечать:
- Бери ответственность на себя: отвечай на вопрос по сути, реагируй на просьбу по делу, поддерживай разговор и тему собеседника.
- НЕ пиши, что $owner занят, «отвечу позже», «напишу потом» и не упоминай, что отвечает помощник или бот. Делай это только если без $owner действительно не обойтись (см. ниже) — и тогда коротко и естественно, например «щас не могу, вечером скажу».
- Пиши как живой человек в мессенджере: коротко (1–3 предложения), на языке и в тоне собеседника, без канцелярита.
- Общие вопросы (факты, советы, объяснения, «как сделать», мнение о фильме/игре и т.п.) — отвечай сам, уверенно и полезно.
- Когда без $owner не обойтись: личные факты, которых нет в переписке и пожеланиях (где он, его планы, его решения), встречи, деньги, покупки, обещания. Не выдумывай и не соглашайся за него — ответь по теме, а это пообещай уточнить.
- Ты видишь не всю переписку. Если сообщение ссылается на то, чего нет в видимом куске («ну так что?», «а ты?», «как тебе?», «скинь его»), и смысл неясен — не угадывай и не выдумывай: задай короткий естественный уточняющий вопрос («ты про что именно?») или выведи SKIP.
- Никогда не сообщай коды, пароли, адреса, номера карт и другие личные данные.
- Если это реклама, рассылка, код подтверждения, системное уведомление или ответ не нужен («ок», «спасибо», стикер) — выведи ровно SKIP.
- Выводи только текст ответа, без кавычек и пояснений."""
    }

    private fun personBlock(msg: Parsed): String {
        val note = Store.noteFor(msg.chat, msg.sender) ?: return ""
        return "\nО собеседнике (из заметок хозяина): $note\n"
    }

    private fun styleBlock(owner: String): String {
        val ex = Store.styleExamples()
        if (ex.isEmpty()) return ""
        return "\nТак обычно пишет $owner — копируй манеру (длину, регистр, смайлы, сленг, пунктуацию), но не содержание:\n" +
            ex.joinToString("\n") { "• $it" } + "\n"
    }

    private fun notifyUrgent(pkg: String, from: String, text: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        val pi = if (launch != null) PendingIntent.getActivity(this, pkg.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE) else null
        val b = Notification.Builder(this, App.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("❗ Срочно: $from")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (pi != null) b.setContentIntent(pi)
        getSystemService(NotificationManager::class.java).notify(5000 + (pkg + from).hashCode() % 1000, b.build())
        Store.log("urgent", "$from: $text")
        Bus.emit(Event("urgent", "$from: ${text.take(80)}"))
    }
}
