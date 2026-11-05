package com.pix.helper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    val KNOWN_APPS: LinkedHashMap<String, String> = linkedMapOf(
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram (с сайта)",
        "org.thunderdog.challegram" to "Telegram X",
        "org.telegram.messenger.beta" to "Telegram Beta",
        "org.telegram.messenger.lite" to "Telegram Lite",
        "org.telegram.plus" to "Plus Messenger",
        "tw.nekomimi.nekogram" to "Nekogram",
        "xyz.nextalone.nagram" to "Nagram",
        "com.exteragram.messenger" to "exteraGram",
        "com.radolyn.ayugram" to "AyuGram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.vkontakte.android" to "ВКонтакте",
        "ru.oneme.app" to "MAX",
        "com.viber.voip" to "Viber",
        "com.discord" to "Discord",
        "com.instagram.android" to "Instagram",
        "com.google.android.apps.messaging" to "Сообщения Google (SMS)",
        "com.samsung.android.messaging" to "Сообщения Samsung (SMS)",
        "com.facebook.orca" to "Messenger",
        "com.Slack" to "Slack"
    )

    private const val OLD_PERSONA =
        "Отвечай дружелюбно и коротко, как живой человек. Скажи, что я сейчас занят и отвечу позже. " +
            "Если вопрос простой и ответ очевиден из переписки — можно ответить по сути. " +
            "Если что-то срочное — пусть напишут слово «срочно»."

    private const val OLD_PERSONA_2 =
        "Отвечай дружелюбно, на «ты» с друзьями, по сути вопроса — как ответил бы я сам. " +
            "Если без меня не решить — скажи, что уточню и напишу."

    const val DEFAULT_PERSONA =
        "Отвечай сам, как будто это я: по сути вопроса, дружелюбно, на «ты» с друзьями. " +
            "Не говори, что я занят. Только если без меня правда не решить — коротко скажи, что уточню."

    private val p: SharedPreferences
        get() = App.instance.getSharedPreferences("pix", Context.MODE_PRIVATE)

    private fun s(k: String, d: String): String = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    // ---------- Настройки ----------
    /** "gemini" (бесплатно, по умолчанию) или "claude" */
    var provider: String
        get() = s("provider", "gemini")
        set(v) = put("provider", v)

    var geminiKey: String
        get() = s("geminiKey", "")
        set(v) = put("geminiKey", v)

    var gChatModel: String
        get() = s("gChatModel", "gemini-3.8-flash")
        set(v) = put("gChatModel", v)

    var gReplyModel: String
        get() = s("gReplyModel", "gemini-3.5-flash-lite")
        set(v) = put("gReplyModel", v)

    var apiKey: String
        get() = s("apiKey", "")
        set(v) = put("apiKey", v)

    var chatModel: String
        get() = s("chatModel", "claude-sonnet-5-5")
        set(v) = put("chatModel", v)

    var replyModel: String
        get() = s("replyModel", "claude-haiku-5-5")
        set(v) = put("replyModel", v)

    var ownerName: String
        get() = s("ownerName", "")
        set(v) = put("ownerName", v)

    var persona: String
        get() = s("persona", DEFAULT_PERSONA).let { if (it == OLD_PERSONA || it == OLD_PERSONA_2) DEFAULT_PERSONA else it }
        set(v) = put("persona", v)

    var prefix: String
        get() = s("prefix", "🤖")
        set(v) = put("prefix", v)

    /** Пауза между автоответами в один чат (режим «Я занят»), секунды */
    var cooldownSec: Int
        get() = p.getInt("cooldownSec", 30)
        set(v) = p.edit().putInt("cooldownSec", v).apply()

    /** Отвечать самому, если хозяин молчит silenceSec секунд */
    var silenceReply: Boolean
        get() = p.getBoolean("silenceReply", true)
        set(v) = p.edit().putBoolean("silenceReply", v).apply()

    /** Сколько секунд ждать ответа хозяина, прежде чем отвечать самому */
    var silenceSec: Int
        get() = if (p.contains("silenceSec")) p.getInt("silenceSec", 120) else p.getInt("silenceMin", 2) * 60
        set(v) = p.edit().putInt("silenceSec", v).apply()

    fun silenceText(): String {
        val s = silenceSec
        return if (s <= 0) "сразу" else if (s % 60 == 0) "${s / 60} мин." else "$s сек."
    }

    var replyGroups: Boolean
        get() = p.getBoolean("replyGroups", false)
        set(v) = p.edit().putBoolean("replyGroups", v).apply()

    var notifyUrgent: Boolean
        get() = p.getBoolean("notifyUrgent", true)
        set(v) = p.edit().putBoolean("notifyUrgent", v).apply()

    var apps: Set<String>
        get() = s("apps", KNOWN_APPS.keys.joinToString(","))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = put("apps", v.joinToString(","))

    var overlayEnabled: Boolean
        get() = p.getBoolean("overlayEnabled", false)
        set(v) = p.edit().putBoolean("overlayEnabled", v).apply()

    var overlayX: Int
        get() = p.getInt("ox", -1)
        set(v) = p.edit().putInt("ox", v).apply()

    var overlayY: Int
        get() = p.getInt("oy", -1)
        set(v) = p.edit().putInt("oy", v).apply()

    // ---------- Стиль, люди, фильтр ----------
    /** Примеры сообщений хозяина, введённые вручную (по одному в строке) */
    var styleSamples: String
        get() = s("styleSamples", "")
        set(v) = put("styleSamples", v)

    /** Запоминать сообщения, которые хозяин сам пишет через уведомления */
    var learnStyle: Boolean
        get() = p.getBoolean("learnStyle", true)
        set(v) = p.edit().putBoolean("learnStyle", v).apply()

    /** «Имя — кто это и как с ним говорить», одна строка на человека */
    var peopleNotes: String
        get() = s("peopleNotes", "")
        set(v) = put("peopleNotes", v)

    var whitelist: String
        get() = s("whitelist", "")
        set(v) = put("whitelist", v)

    var blacklist: String
        get() = s("blacklist", "")
        set(v) = put("blacklist", v)

    var summaryEnabled: Boolean
        get() = p.getBoolean("summaryEnabled", true)
        set(v) = p.edit().putBoolean("summaryEnabled", v).apply()

    var summaryHour: Int
        get() = p.getInt("summaryHour", 21)
        set(v) = p.edit().putInt("summaryHour", v).apply()

    var sleepAtNight: Boolean
        get() = p.getBoolean("sleepAtNight", true)
        set(v) = p.edit().putBoolean("sleepAtNight", v).apply()

    var shakeReact: Boolean
        get() = p.getBoolean("shakeReact", true)
        set(v) = p.edit().putBoolean("shakeReact", v).apply()

    @Synchronized
    fun addOwnSample(text: String) {
        val t = text.trim()
        if (t.length < 2 || t.length > 300) return
        val a = arr("own")
        for (i in 0 until a.length()) if (a.optString(i) == t) return
        a.put(t)
        saveArr("own", a, 40)
    }

    @Synchronized
    fun ownSamples(n: Int): List<String> {
        val a = arr("own")
        val l = ArrayList<String>()
        for (i in 0 until a.length()) l.add(a.optString(i))
        return l.takeLast(n)
    }

    @Synchronized
    fun clearOwnSamples() = put("own", "[]")

    fun styleExamples(): List<String> =
        (styleSamples.lines().map { it.trim() }.filter { it.isNotEmpty() } + ownSamples(25)).takeLast(30)

    private fun names(src: String): List<String> =
        src.split(",", ";", "\n").map { it.trim().lowercase() }.filter { it.length >= 2 }

    private fun matches(name: String, chat: String, sender: String): Boolean {
        val c = chat.lowercase()
        val s2 = sender.lowercase()
        return c.contains(name) || s2.contains(name)
    }

    /** Можно ли Пиксу отвечать в этот чат (белый / чёрный список). */
    fun allowedChat(chat: String, sender: String): Boolean {
        if (names(blacklist).any { matches(it, chat, sender) }) return false
        val wl = names(whitelist)
        return wl.isEmpty() || wl.any { matches(it, chat, sender) }
    }

    /** Заметка о собеседнике из «Заметок о людях». */
    fun noteFor(chat: String, sender: String): String? {
        val out = ArrayList<String>()
        for (line in peopleNotes.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val idx = listOf(l.indexOf('—'), l.indexOf(" - "), l.indexOf(':')).filter { it > 0 }.minOrNull() ?: continue
            val name = l.substring(0, idx).trim().lowercase()
            if (name.length >= 2 && matches(name, chat, sender)) out.add(l)
        }
        return if (out.isEmpty()) null else out.joinToString("; ")
    }

    @Synchronized
    fun addPersonNote(name: String, note: String) {
        val line = "${name.trim()} — ${note.trim()}"
        peopleNotes = (peopleNotes.trimEnd() + "\n" + line).trim()
    }

    @Synchronized
    fun inboxSince(t: Long): List<JSONObject> = objs(arr("inbox")).filter { it.optLong("t") >= t }

    @Synchronized
    fun logSince(t: Long, type: String): List<JSONObject> =
        objs(arr("log")).filter { it.optLong("t") >= t && it.optString("type") == type }

    // ---------- Режим «Я занят» ----------
    var busy: Boolean
        get() = p.getBoolean("busy", false)
        private set(v) = p.edit().putBoolean("busy", v).apply()

    var busyUntil: Long
        get() = p.getLong("busyUntil", 0L)
        private set(v) = p.edit().putLong("busyUntil", v).apply()

    fun isBusy(): Boolean {
        if (!busy) return false
        val u = busyUntil
        if (u > 0 && System.currentTimeMillis() > u) {
            busy = false
            busyUntil = 0
            Bus.emit(Event("busy"))
            return false
        }
        return true
    }

    /** minutes <= 0 — до ручного выключения */
    fun setBusy(on: Boolean, minutes: Int) {
        busy = on
        busyUntil = if (on && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        log("busy", if (on) "Режим «Я занят» включён" + (if (minutes > 0) " на $minutes мин" else "") else "Режим «Я занят» выключен")
        Bus.emit(Event("busy"))
    }

    fun busyText(): String {
        if (!isBusy()) return "Ты свободен — Пикс не отвечает за тебя"
        val u = busyUntil
        return if (u > 0) "Ты занят до ${time(u)} — Пикс отвечает за тебя" else "Ты занят — Пикс отвечает за тебя"
    }

    // ---------- JSON-списки ----------
    @Synchronized
    private fun arr(k: String): JSONArray = try {
        JSONArray(s(k, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    @Synchronized
    private fun saveArr(k: String, a: JSONArray, max: Int) {
        val out = if (a.length() > max) {
            val n = JSONArray()
            for (i in a.length() - max until a.length()) n.put(a.get(i))
            n
        } else a
        put(k, out.toString())
    }

    private fun objs(a: JSONArray): List<JSONObject> {
        val l = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { l.add(it) }
        return l
    }

    // Входящие
    @Synchronized
    fun addInbox(pkg: String, app: String, chat: String, sender: String, text: String, group: Boolean, seen: Boolean) {
        val a = arr("inbox")
        a.put(
            JSONObject().put("t", System.currentTimeMillis()).put("pkg", pkg).put("app", app)
                .put("chat", chat).put("sender", sender).put("text", text)
                .put("group", group).put("seen", seen)
        )
        saveArr("inbox", a, 800)
    }

    @Synchronized
    fun unseenCount(): Int = objs(arr("inbox")).count { !it.optBoolean("seen", true) }

    @Synchronized
    fun takeInbox(limit: Int, onlyUnseen: Boolean, markSeen: Boolean): List<JSONObject> {
        val a = arr("inbox")
        val all = objs(a)
        val picked = all.filter { !onlyUnseen || !it.optBoolean("seen", true) }.takeLast(limit)
        if (markSeen) {
            for (o in all) o.put("seen", true)
            val n = JSONArray()
            all.forEach { n.put(it) }
            saveArr("inbox", n, 800)
        }
        return picked
    }

    @Synchronized
    fun markAllSeen() {
        takeInbox(0, onlyUnseen = true, markSeen = true)
    }

    @Synchronized
    fun chatHistory(pkg: String, chat: String, n: Int): List<JSONObject> =
        objs(arr("inbox")).filter { it.optString("pkg") == pkg && it.optString("chat") == chat }.takeLast(n)

    // Журнал
    @Synchronized
    fun log(type: String, text: String) {
        val a = arr("log")
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("type", type).put("text", text))
        saveArr("log", a, 150)
        Bus.emit(Event("log"))
    }

    @Synchronized
    fun logLines(n: Int): List<String> = objs(arr("log")).takeLast(n).reversed().map {
        val icon = when (it.optString("type")) {
            "reply" -> "💬"
            "skip" -> "⏭"
            "error" -> "⚠️"
            "remind" -> "⏰"
            "busy" -> "🟢"
            "urgent" -> "❗"
            "task" -> "✅"
            "summary" -> "📋"
            "people" -> "👤"
            else -> "•"
        }
        "${time(it.optLong("t"))}  $icon ${it.optString("text")}"
    }

    // Задачи
    private fun nextId(): Int {
        val id = p.getInt("nextId", 1)
        p.edit().putInt("nextId", id + 1).apply()
        return id
    }

    @Synchronized
    fun addTask(title: String): Int {
        val id = nextId()
        val a = arr("tasks")
        a.put(JSONObject().put("id", id).put("title", title).put("done", false).put("t", System.currentTimeMillis()))
        saveArr("tasks", a, 200)
        log("task", "Новая задача: $title")
        return id
    }

    @Synchronized
    fun tasks(): List<JSONObject> = objs(arr("tasks"))

    @Synchronized
    fun setTaskDone(id: Int, done: Boolean): JSONObject? {
        val l = tasks()
        val t = l.firstOrNull { it.optInt("id") == id } ?: return null
        t.put("done", done)
        val n = JSONArray()
        l.forEach { n.put(it) }
        saveArr("tasks", n, 200)
        return t
    }

    @Synchronized
    fun deleteTask(id: Int): Boolean {
        val l = tasks()
        val keep = l.filter { it.optInt("id") != id }
        if (keep.size == l.size) return false
        val n = JSONArray()
        keep.forEach { n.put(it) }
        saveArr("tasks", n, 200)
        return true
    }

    // Напоминания
    @Synchronized
    fun addReminder(at: Long, text: String): Int {
        val id = nextId()
        val a = arr("reminders")
        a.put(JSONObject().put("id", id).put("at", at).put("text", text))
        saveArr("reminders", a, 100)
        return id
    }

    @Synchronized
    fun reminders(): List<JSONObject> = objs(arr("reminders"))

    @Synchronized
    fun removeReminder(id: Int) {
        val n = JSONArray()
        reminders().filter { it.optInt("id") != id }.forEach { n.put(it) }
        saveArr("reminders", n, 100)
    }

    // Утилиты
    fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(t))
}
