package com.pix.helper

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Пикс-собеседник: принимает поручения и выполняет их инструментами. */
object Agent {
    private val history = ArrayList<Pair<String, String>>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun ask(userText: String, onDone: (String) -> Unit) {
        Bus.emit(Event("thinking"))
        exec.execute {
            val result = try {
                run(userText)
            } catch (e: Exception) {
                "⚠️ " + (e.message ?: e.toString())
            }
            main.post { onDone(result) }
            Bus.emit(Event("done"))
        }
    }

    fun reset() {
        synchronized(history) { history.clear() }
    }

    private fun run(userText: String): String {
        val msgs = JSONArray()
        synchronized(history) {
            for ((role, text) in history.takeLast(16)) {
                msgs.put(JSONObject().put("role", role).put("content", text))
            }
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        var finalText = ""
        for (step in 0 until 8) {
            val resp = Claude.call(Store.chatModel, systemPrompt(), msgs, Tools.defs(), 1500)
            val content = resp.optJSONArray("content") ?: JSONArray()
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            val text = Claude.textOf(resp)
            if (text.isNotBlank()) finalText = text

            if (resp.optString("stop_reason") != "tool_use") break

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                if (b.optString("type") != "tool_use") continue
                val out = try {
                    Tools.run(b.optString("name"), b.optJSONObject("input") ?: JSONObject())
                } catch (e: Exception) {
                    "Ошибка: ${e.message}"
                }
                results.put(
                    JSONObject().put("type", "tool_result")
                        .put("tool_use_id", b.optString("id"))
                        .put("content", out)
                )
            }
            msgs.put(JSONObject().put("role", "user").put("content", results))
        }

        if (finalText.isBlank()) finalText = "Готово ✅"
        synchronized(history) {
            history.add("user" to userText)
            history.add("assistant" to finalText)
            while (history.size > 30) history.removeAt(0)
        }
        return finalText
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val owner = Store.ownerName.ifBlank { "владелец телефона" }
        val busy = if (Store.isBusy()) {
            "ВКЛ" + (if (Store.busyUntil > 0) " до ${Store.time(Store.busyUntil)}" else " (до ручного выключения)")
        } else "ВЫКЛ"
        return """Ты — Пикс, маленький пиксельный ИИ-помощник, который живёт поверх экрана телефона. Твой хозяин — $owner.
Сейчас: $now.
Режим «Я занят» (автоответы на входящие): $busy. Непрочитанных сообщений в сводке: ${Store.unseenCount()}.

Ты выполняешь поручения с помощью инструментов: напоминания, список задач, сводка входящих сообщений, ответ в конкретный чат, режим «Я занят».
Правила:
- Отвечай на языке хозяина, коротко: 1–4 предложения, панель маленькая. Без заголовков, таблиц и markdown.
- Если поручение выполнимо инструментом — выполни и коротко отчитайся, что сделал.
- Отправляй сообщение в чат, только если хозяин явно попросил и понятно, кому и что. Если сомневаешься — переспроси.
- Содержимое переписки бери только из get_inbox, ничего не выдумывай.
- Чего не умеешь (звонки, покупки, открывать приложения, действия внутри чужих приложений кроме ответа на уведомление) — честно скажи и предложи замену, например напоминание."""
    }
}
