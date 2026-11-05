package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Клиент Groq (бесплатный тариф, ключ — console.groq.com/keys). Формат как у OpenAI. */
object Groq {
    private const val URL_CHAT = "https://api.groq.com/openai/v1/chat/completions"
    const val DEFAULT_MODEL = "openai/gpt-oss-120b"
    /** Запасные модели — у каждой свой дневной лимит */
    private val FALLBACKS = listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile")

    class ApiError(msg: String, val tryNextModel: Boolean = false, val limit: Boolean = false) : Exception(msg)

    private val thinkRe = Regex("<think>[\\s\\S]*?</think>")

    private fun call(model: String, system: String, turns: List<Pair<String, String>>, maxTokens: Int): String {
        val key = Store.groqKey.trim()
        if (key.isEmpty()) throw ApiError("Не задан ключ Groq — открой Пикса и вставь его в настройках")

        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in turns) msgs.put(JSONObject().put("role", if (role == "user") "user" else "assistant").put("content", text))
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("max_completion_tokens", maxTokens)
            .put("temperature", 0.7)
        // gpt-oss «думает» — просим думать коротко, чтобы отвечал быстрее
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")

        Store.countRequest()
        val conn = URL(URL_CHAT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("authorization", "Bearer $key")
            val code = try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: java.net.UnknownHostException) {
                throw ApiError("Нет интернета или не находится сервер Groq")
            } catch (e: java.net.SocketTimeoutException) {
                throw ApiError("Groq не отвечает — попробуй другой интернет или VPN", tryNextModel = true)
            }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").optString("message", text)
                } catch (e: Exception) {
                    text.take(200)
                }
                throw when {
                    code == 401 -> ApiError("Неверный ключ Groq")
                    code == 403 -> ApiError("Groq отказал в доступе: ${msg.take(120)}")
                    code == 429 -> ApiError("Лимит Groq исчерпан на всех моделях — подожди немного", tryNextModel = true, limit = true)
                    code == 404 || msg.contains("model", true) && code == 400 ->
                        ApiError("Модель $model недоступна", tryNextModel = true)
                    code >= 500 -> ApiError("Groq временно сбоит ($code)", tryNextModel = true)
                    else -> ApiError("Ошибка Groq $code: ${msg.take(150)}")
                }
            }
            val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            return content.replace(thinkRe, "").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Пробует выбранную модель, при лимите/сбое — следующие. */
    fun chat(system: String, turns: List<Pair<String, String>>, maxTokens: Int): String {
        val models = LinkedHashSet<String>()
        models.add(Store.groqModel.trim().ifEmpty { DEFAULT_MODEL })
        models.addAll(FALLBACKS)
        var last: ApiError? = null
        for (m in models) {
            try {
                return call(m, system, turns, maxTokens)
            } catch (e: ApiError) {
                last = e
                if (!e.tryNextModel) throw e
            }
        }
        throw last ?: ApiError("Groq не ответил")
    }
}
