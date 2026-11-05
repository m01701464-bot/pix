package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Общий клиент для сервисов с форматом OpenAI (Mistral, OpenRouter). */
object OpenAiCompat {
    class ApiError(msg: String, val tryNext: Boolean = false, val limit: Boolean = false, val keyProblem: Boolean = false) : Exception(msg)

    private val thinkRe = Regex("<think>[\\s\\S]*?</think>")

    fun call(
        url: String, key: String, service: String, model: String, system: String,
        turns: List<Pair<String, String>>, maxTokens: Int, headers: Map<String, String> = emptyMap()
    ): String {
        if (key.isBlank()) throw ApiError("Не задан ключ $service", keyProblem = true)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in turns) msgs.put(JSONObject().put("role", if (role == "user") "user" else "assistant").put("content", text))
        val body = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", maxTokens).put("temperature", 0.8)

        Store.countRequest()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("authorization", "Bearer ${key.trim()}")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: java.net.UnknownHostException) {
                throw ApiError("Нет интернета или не находится сервер $service", tryNext = true)
            } catch (e: java.io.IOException) {
                throw ApiError("$service не отвечает", tryNext = true)
            }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    val e = JSONObject(text).opt("error")
                    if (e is JSONObject) e.optString("message", text) else (e?.toString() ?: JSONObject(text).optString("message", text))
                } catch (ex: Exception) {
                    text.take(200)
                }
                throw when (code) {
                    401, 403 -> ApiError("$service: ключ не принят (${msg.take(80)})", keyProblem = true)
                    429 -> ApiError("$service: лимит исчерпан", tryNext = true, limit = true)
                    402 -> ApiError("$service: модель платная или нет кредитов", tryNext = true)
                    404 -> ApiError("$service: модель $model недоступна", tryNext = true)
                    else -> if (code >= 500) ApiError("$service временно сбоит ($code)", tryNext = true)
                    else ApiError("$service $code: ${msg.take(150)}", tryNext = true)
                }
            }
            val msgObj = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val raw = if (msgObj == null || msgObj.isNull("content")) "" else msgObj.optString("content", "")
            val content = raw.replace(thinkRe, "").trim()
            // «думающая» модель могла потратить весь ответ на размышления — берём следующую
            if (content.isEmpty() || content == "null") throw ApiError("$service: модель $model вернула пустой ответ", tryNext = true)
            return content
        } finally {
            conn.disconnect()
        }
    }

    /** Перебирает модели: при лимите или недоступности — следующая */
    fun chatModels(
        models: Collection<String>, url: String, key: String, service: String, system: String,
        turns: List<Pair<String, String>>, maxTokens: Int, headers: Map<String, String> = emptyMap()
    ): String {
        var last: ApiError? = null
        for (m in models) {
            if (m.isBlank()) continue
            try {
                return call(url, key, service, m.trim(), system, turns, maxTokens, headers)
            } catch (e: ApiError) {
                last = e
                if (!e.tryNext) throw e
            }
        }
        throw last ?: ApiError("$service не ответил", tryNext = true)
    }
}

/** Mistral (есть бесплатный тариф Experiment, ключ — console.mistral.ai). */
object Mistral {
    private const val URL_CHAT = "https://api.mistral.ai/v1/chat/completions"
    const val DEFAULT_MODEL = "mistral-medium-latest"

    fun chat(system: String, turns: List<Pair<String, String>>, maxTokens: Int): String {
        val models = LinkedHashSet<String>()
        models.add(Store.mistralModel.trim().ifEmpty { DEFAULT_MODEL })
        models.addAll(listOf("mistral-medium-latest", "mistral-large-latest", "mistral-small-latest"))
        return OpenAiCompat.chatModels(models, URL_CHAT, Store.mistralKey, "Mistral", system, turns, maxTokens)
    }
}

/** OpenRouter: один ключ ко многим бесплатным моделям (их id кончаются на «:free»). */
object OpenRouter {
    private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
    private const val URL_MODELS = "https://openrouter.ai/api/v1/models"
    private val HEADERS = mapOf("HTTP-Referer" to "https://github.com/m01701464-bot/pix", "X-Title" to "Pix")
    private val THINKERS = listOf("-r1", "r1-", "think", "reason", "qwq")
    private val PREFER = listOf("deepseek", "qwen", "llama-3.3", "gemma", "mistral", "glm", "kimi", "llama")

    @Volatile
    private var cache: List<String> = emptyList()
    @Volatile
    private var cacheAt = 0L

    /** Список бесплатных моделей (кэшируется на 6 часов), лучшие для болтовни — первыми */
    fun freeModels(): List<String> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cacheAt < 6 * 3600_000L) return cache
        try {
            val conn = URL(URL_MODELS).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id") ?: continue
                if (id.endsWith(":free")) ids.add(id)
            }
            fun rank(id: String): Int {
                val l = id.lowercase()
                // «думающие» модели отвечают долго и часто пусто — в конец
                if (THINKERS.any { l.contains(it) }) return 200
                return PREFER.indexOfFirst { l.contains(it) }.let { if (it < 0) 99 else it }
            }
            cache = ids.sortedBy { rank(it) }
            cacheAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return cache
    }

    fun chat(system: String, turns: List<Pair<String, String>>, maxTokens: Int): String {
        val models = LinkedHashSet<String>()
        Store.orModel.trim().let { if (it.isNotEmpty()) models.add(it) }
        models.addAll(freeModels().take(5))
        if (models.isEmpty()) throw OpenAiCompat.ApiError("OpenRouter: не удалось получить список бесплатных моделей", tryNext = true)
        return OpenAiCompat.chatModels(models, URL_CHAT, Store.orKey, "OpenRouter", system, turns, maxTokens, HEADERS)
    }
}

/** NVIDIA NIM (build.nvidia.com): бесплатный доступ к открытым моделям. Экстренная запаска. */
object Nvidia {
    private const val URL_CHAT = "https://integrate.api.nvidia.com/v1/chat/completions"
    private const val URL_MODELS = "https://integrate.api.nvidia.com/v1/models"
    private val PREFER = listOf("llama-3.3-70b", "deepseek-v3", "qwen3", "mistral-large", "llama-3.1-70b", "gemma", "llama")
    private val SKIP = listOf("embed", "rerank", "reward", "guard", "vision", "vl", "coder", "safety", "parse", "nemoretriever", "clip", "r1")
    private val FALLBACK = listOf("meta/llama-3.3-70b-instruct", "deepseek-ai/deepseek-v3.1", "qwen/qwen3-235b-a22b")

    @Volatile
    private var cache: List<String> = emptyList()
    @Volatile
    private var cacheAt = 0L

    private fun models(): List<String> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cacheAt < 12 * 3600_000L) return cache
        try {
            val conn = java.net.URL(URL_MODELS).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("authorization", "Bearer ${Store.nvKey.trim()}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = org.json.JSONObject(text).optJSONArray("data") ?: org.json.JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id") ?: continue
                val l = id.lowercase()
                if (SKIP.any { l.contains(it) }) continue
                if (PREFER.any { l.contains(it) }) ids.add(id)
            }
            fun rank(id: String) = PREFER.indexOfFirst { id.lowercase().contains(it) }.let { if (it < 0) 99 else it }
            cache = ids.sortedBy { rank(it) }
            cacheAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return cache
    }

    fun chat(system: String, turns: List<Pair<String, String>>, maxTokens: Int): String {
        val list = LinkedHashSet<String>()
        Store.nvModel.trim().let { if (it.isNotEmpty()) list.add(it) }
        list.addAll(models().take(4))
        list.addAll(FALLBACK)
        return OpenAiCompat.chatModels(list, URL_CHAT, Store.nvKey, "NVIDIA", system, turns, maxTokens)
    }
}
