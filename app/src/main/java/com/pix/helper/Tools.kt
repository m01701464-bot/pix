package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Инструменты, которыми Пикс выполняет поручения. */
object Tools {
    private fun sp(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun ip(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun bp(d: String) = JSONObject().put("type", "boolean").put("description", d)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
        )

    fun defs(): JSONArray = JSONArray()
        .put(
            tool(
                "set_reminder",
                "Поставить напоминание хозяину (уведомление на телефоне). Укажи minutes (через сколько минут) ИЛИ time в формате HH:mm и при необходимости date в формате YYYY-MM-DD.",
                JSONObject().put("text", sp("Что напомнить")).put("minutes", ip("Через сколько минут"))
                    .put("time", sp("Время HH:mm")).put("date", sp("Дата YYYY-MM-DD")),
                listOf("text")
            )
        )
        .put(tool("list_reminders", "Показать запланированные напоминания.", JSONObject()))
        .put(tool("add_task", "Добавить задачу в список дел хозяина.", JSONObject().put("title", sp("Текст задачи")), listOf("title")))
        .put(tool("list_tasks", "Показать список задач.", JSONObject()))
        .put(
            tool(
                "complete_task", "Отметить задачу выполненной (или удалить).",
                JSONObject().put("id", ip("Номер задачи")).put("delete", bp("true — удалить вместо отметки")),
                listOf("id")
            )
        )
        .put(
            tool(
                "get_inbox",
                "Получить входящие сообщения из мессенджеров, которые пришли в уведомлениях. По умолчанию только непрочитанные (пришедшие в режиме «Я занят»); после чтения они помечаются прочитанными. Если непрочитанных нет, а хозяин спрашивает, что ему писали, — вызови с include_read=true.",
                JSONObject().put("limit", ip("Сколько последних, по умолчанию 30"))
                    .put("include_read", bp("true — включить уже прочитанные"))
            )
        )
        .put(tool("list_chats", "Список чатов, в которые сейчас можно ответить (есть активное уведомление с кнопкой «Ответить»).", JSONObject()))
        .put(
            tool(
                "reply_to_chat",
                "Отправить сообщение в чат от имени хозяина через кнопку «Ответить» в уведомлении. Только по явной просьбе хозяина.",
                JSONObject().put("chat", sp("Имя чата или собеседника")).put("text", sp("Текст сообщения")),
                listOf("chat", "text")
            )
        )
        .put(
            tool(
                "set_busy",
                "Включить или выключить режим «Я занят» (Пикс сам отвечает на входящие).",
                JSONObject().put("on", bp("Включить?")).put("minutes", ip("На сколько минут; 0 — до ручного выключения")),
                listOf("on")
            )
        )

    fun run(name: String, input: JSONObject): String = when (name) {
        "set_reminder" -> setReminder(input)
        "list_reminders" -> listReminders()
        "add_task" -> addTask(input)
        "list_tasks" -> listTasks()
        "complete_task" -> completeTask(input)
        "get_inbox" -> getInbox(input)
        "list_chats" -> MessageListener.chatNames()
        "reply_to_chat" -> MessageListener.replyByName(input.optString("chat"), input.optString("text"))
        "set_busy" -> setBusy(input)
        else -> "Неизвестный инструмент: $name"
    }

    private val timeRe = Regex("^\\d{1,2}:\\d{2}$")
    private val dateRe = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun setReminder(input: JSONObject): String {
        val text = input.optString("text").ifBlank { "Напоминание" }
        val cal = Calendar.getInstance()
        val minutes = input.optInt("minutes", 0)
        val time = input.optString("time").trim()
        val date = input.optString("date").trim()
        if (minutes > 0) {
            cal.add(Calendar.MINUTE, minutes)
        } else if (timeRe.matches(time)) {
            val hm = time.split(":")
            if (dateRe.matches(date)) {
                val d = date.split("-")
                cal.set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt())
            }
            cal.set(Calendar.HOUR_OF_DAY, hm[0].toInt())
            cal.set(Calendar.MINUTE, hm[1].toInt())
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                if (dateRe.matches(date)) return "Это время уже прошло"
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        } else {
            return "Нужно указать minutes или time (HH:mm)"
        }
        val at = cal.timeInMillis
        val id = Store.addReminder(at, text)
        Reminders.schedule(App.instance, id, at, text)
        Store.log("remind", "Поставил напоминание на ${Store.dateTime(at)}: $text")
        return "Напоминание «$text» поставлено на ${Store.dateTime(at)}"
    }

    private fun listReminders(): String {
        val l = Store.reminders().filter { it.optLong("at") > System.currentTimeMillis() }
        if (l.isEmpty()) return "Напоминаний нет"
        return l.sortedBy { it.optLong("at") }
            .joinToString("\n") { "${Store.dateTime(it.optLong("at"))} — ${it.optString("text")}" }
    }

    private fun addTask(input: JSONObject): String {
        val title = input.optString("title").trim()
        if (title.isEmpty()) return "Пустая задача"
        val id = Store.addTask(title)
        return "Задача #$id добавлена"
    }

    private fun listTasks(): String {
        val l = Store.tasks()
        if (l.isEmpty()) return "Список задач пуст"
        return l.joinToString("\n") {
            "#${it.optInt("id")} ${if (it.optBoolean("done")) "☑" else "☐"} ${it.optString("title")}"
        }
    }

    private fun completeTask(input: JSONObject): String {
        val id = input.optInt("id", -1)
        if (input.optBoolean("delete", false)) {
            return if (Store.deleteTask(id)) "Задача #$id удалена" else "Нет задачи #$id"
        }
        val t = Store.setTaskDone(id, true) ?: return "Нет задачи #$id"
        Store.log("task", "Выполнено: ${t.optString("title")}")
        return "Задача #$id отмечена выполненной"
    }

    private fun getInbox(input: JSONObject): String {
        val limit = input.optInt("limit", 30).coerceIn(1, 100)
        val includeRead = input.optBoolean("include_read", false)
        val l = Store.takeInbox(limit, onlyUnseen = !includeRead, markSeen = true)
        Bus.emit(Event("inbox"))
        if (l.isEmpty()) return "Новых сообщений нет"
        return l.joinToString("\n") {
            "[${Store.time(it.optLong("t"))}] ${it.optString("app")} · ${it.optString("chat")}" +
                (if (it.optBoolean("group")) " (группа)" else "") +
                " · ${it.optString("sender")}: ${it.optString("text")}"
        }
    }

    private fun setBusy(input: JSONObject): String {
        val on = input.optBoolean("on", true)
        val minutes = input.optInt("minutes", 0)
        Store.setBusy(on, minutes)
        return Store.busyText()
    }
}
