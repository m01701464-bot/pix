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
        val finalText = (if (Brain.gemini()) runGeminiSafe(userText) else runClaude(userText)).ifBlank { "Готово ✅" }
        synchronized(history) {
            history.add("user" to userText)
            history.add("assistant" to finalText)
            while (history.size > 30) history.removeAt(0)
        }
        return finalText
    }

    /** Сначала «родные» инструменты Gemini, при сбое — текстовые команды. */
    private fun runGeminiSafe(userText: String): String {
        return try {
            runGemini(userText)
        } catch (e: Gemini.ApiError) {
            if (e.keyProblem) throw e
            runGeminiText(userText)
        } catch (e: org.json.JSONException) {
            runGeminiText(userText)
        }
    }

    private val jsonRe = Regex("\\{[\\s\\S]*\\}")

    /** Запасной режим: модель пишет команду как JSON-текст, Пикс её выполняет. */
    private fun runGeminiText(userText: String): String {
        fun textPart(t: String) = JSONArray().put(JSONObject().put("text", t))
        val sys = systemPrompt() + "\n\n" + toolsAsText()
        val contents = JSONArray()
        synchronized(history) {
            for ((role, text) in history.takeLast(16)) {
                contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", textPart(text)))
            }
        }
        contents.put(JSONObject().put("role", "user").put("parts", textPart(userText)))

        var answer = ""
        for (step in 0 until 6) {
            val out = Gemini.chatText(Store.gChatModel, sys, contents, 2048).trim()
            val call = parseCommand(out)
            if (call == null) {
                answer = out
                break
            }
            val name = call.optString("tool")
            val result = try {
                Tools.run(name, call.optJSONObject("args") ?: JSONObject())
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            contents.put(JSONObject().put("role", "model").put("parts", textPart(out)))
            contents.put(
                JSONObject().put("role", "user").put(
                    "parts",
                    textPart("[Результат $name]\n$result\n\nЕсли нужно ещё действие — снова одна строка JSON. Иначе коротко ответь хозяину обычным текстом.")
                )
            )
            answer = result
        }
        return answer
    }

    private fun parseCommand(text: String): JSONObject? {
        val clean = text.replace("```json", "").replace("```", "").trim()
        if (!clean.contains("\"tool\"")) return null
        val m = jsonRe.find(clean) ?: return null
        return try {
            val o = JSONObject(m.value)
            if (o.optString("tool").isNotEmpty()) o else null
        } catch (e: Exception) {
            null
        }
    }

    private fun toolsAsText(): String {
        val defs = Tools.defs()
        val sb = StringBuilder()
        sb.append("ИНСТРУМЕНТЫ. Чтобы выполнить действие, ответь ТОЛЬКО одной строкой JSON без пояснений, например:\n")
        sb.append("{\"tool\":\"set_reminder\",\"args\":{\"text\":\"позвонить маме\",\"minutes\":20}}\n")
        sb.append("Я выполню его и пришлю результат. Если действие не нужно — отвечай обычным текстом.\nДоступные инструменты:\n")
        for (i in 0 until defs.length()) {
            val t = defs.optJSONObject(i) ?: continue
            sb.append("- ").append(t.optString("name")).append(": ").append(t.optString("description"))
            val props = t.optJSONObject("input_schema")?.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                val keys = props.keys()
                val parts = ArrayList<String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pr = props.optJSONObject(k)
                    parts.add("$k (${pr?.optString("type")}): ${pr?.optString("description")}")
                }
                sb.append(" Аргументы: ").append(parts.joinToString("; "))
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun runGemini(userText: String): String {
        fun textPart(t: String) = JSONArray().put(JSONObject().put("text", t))
        val contents = JSONArray()
        synchronized(history) {
            for ((role, text) in history.takeLast(16)) {
                contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", textPart(text)))
            }
        }
        contents.put(JSONObject().put("role", "user").put("parts", textPart(userText)))

        var finalText = ""
        for (step in 0 until 8) {
            val resp = Gemini.callSmart(Store.gChatModel, systemPrompt(), contents, Tools.defs(), 4096, attempts = 1)
            val content = Gemini.content(resp) ?: break
            if (!content.has("role")) content.put("role", "model")
            contents.put(content)
            val text = Gemini.textOf(content)
            if (text.isNotBlank()) finalText = text

            val parts = content.optJSONArray("parts") ?: JSONArray()
            val responses = JSONArray()
            for (i in 0 until parts.length()) {
                val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
                val name = fc.optString("name")
                val out = try {
                    Tools.run(name, fc.optJSONObject("args") ?: JSONObject())
                } catch (e: Exception) {
                    "Ошибка: ${e.message}"
                }
                val fr = JSONObject().put("name", name).put("response", JSONObject().put("result", out))
                if (fc.has("id")) fr.put("id", fc.optString("id"))
                responses.put(JSONObject().put("functionResponse", fr))
            }
            if (responses.length() == 0) break
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return finalText
    }

    private fun runClaude(userText: String): String {
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
