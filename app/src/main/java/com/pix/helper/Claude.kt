package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Минимальный клиент Claude Messages API без сторонних библиотек. */
object Claude {
    class ApiError(msg: String) : Exception(msg)

    fun call(
        model: String,
        system: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        maxTokens: Int = 1024
    ): JSONObject {
        val key = Store.apiKey.trim()
        if (key.isEmpty()) throw ApiError("Не задан API-ключ Claude — открой Пикса и вставь его в настройках")

        val body = JSONObject()
            .put("model", model.trim())
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        if (tools != null && tools.length() > 0) body.put("tools", tools)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").optString("message", text)
                } catch (e: Exception) {
                    text.take(200)
                }
                throw ApiError(
                    when (code) {
                        401 -> "Неверный API-ключ"
                        429 -> "Слишком много запросов, попробуй позже"
                        529 -> "Claude перегружен, попробуй через минуту"
                        else -> "Ошибка API $code: $msg"
                    }
                )
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    fun textOf(resp: JSONObject): String {
        val content = resp.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(b.optString("text"))
            }
        }
        return sb.toString().trim()
    }
}
