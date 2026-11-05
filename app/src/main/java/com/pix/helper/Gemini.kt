package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Клиент Google Gemini API (есть бесплатный тариф, ключ — aistudio.google.com). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val FALLBACK_MODEL = "gemini-flash-latest"

    class ApiError(msg: String, val tryNextModel: Boolean = false) : Exception(msg)

    fun call(model: String, system: String, contents: JSONArray, tools: JSONArray?, maxTokens: Int): JSONObject {
        val key = Store.geminiKey.trim()
        if (key.isEmpty()) throw ApiError("Не задан ключ Gemini — открой Пикса и вставь его в настройках")

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        if (tools != null && tools.length() > 0) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", convertTools(tools))))
        }

        val conn = URL(BASE + model.trim() + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
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
                val keyBad = msg.contains("API key", ignoreCase = true) &&
                    (msg.contains("invalid", true) || msg.contains("not valid", true))
                throw when {
                    keyBad -> ApiError("Неверный ключ Gemini")
                    code == 404 -> ApiError("Модель $model недоступна", tryNextModel = true)
                    code == 429 && msg.contains("limit: 0") -> ApiError("Модель $model не входит в бесплатный тариф", tryNextModel = true)
                    code == 429 -> ApiError("Лимит бесплатного тарифа на сейчас исчерпан — подожди минуту")
                    code == 503 -> ApiError("Gemini перегружен, попробуй через минуту")
                    else -> ApiError("Ошибка Gemini $code: $msg")
                }
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Пробует указанную модель, а если её нет или она платная — запасную. */
    fun callSmart(model: String, system: String, contents: JSONArray, tools: JSONArray?, maxTokens: Int): JSONObject {
        return try {
            call(model, system, contents, tools, maxTokens)
        } catch (e: ApiError) {
            if (e.tryNextModel && model.trim() != FALLBACK_MODEL) call(FALLBACK_MODEL, system, contents, tools, maxTokens)
            else throw e
        }
    }

    fun content(resp: JSONObject): JSONObject? =
        resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")

    fun textOf(content: JSONObject?): String {
        val parts = content?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            val t = p.optString("text", "")
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(t)
            }
        }
        return sb.toString().trim()
    }

    fun simple(model: String, system: String, user: String, maxTokens: Int): String {
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
        )
        val resp = callSmart(model, system, contents, null, maxTokens)
        return textOf(content(resp))
    }

    /** Переводит описания инструментов из формата Claude в формат Gemini. */
    private fun convertTools(claudeTools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until claudeTools.length()) {
            val t = claudeTools.optJSONObject(i) ?: continue
            val fd = JSONObject().put("name", t.optString("name")).put("description", t.optString("description"))
            val schema = t.optJSONObject("input_schema")
            val props = schema?.optJSONObject("properties")
            if (schema != null && props != null && props.length() > 0) fd.put("parameters", upper(schema))
            out.put(fd)
        }
        return out
    }

    private fun upper(schema: JSONObject): JSONObject {
        val o = JSONObject()
        val type = schema.optString("type", "string").uppercase()
        o.put("type", type)
        schema.optString("description", "").let { if (it.isNotEmpty()) o.put("description", it) }
        val props = schema.optJSONObject("properties")
        if (props != null) {
            val np = JSONObject()
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                props.optJSONObject(k)?.let { np.put(k, upper(it)) }
            }
            o.put("properties", np)
        }
        schema.optJSONArray("required")?.let { if (it.length() > 0) o.put("required", it) }
        return o
    }
}
