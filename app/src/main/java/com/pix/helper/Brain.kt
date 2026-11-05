package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject

/** Выбор «мозгов»: Gemini (бесплатно) или Claude (платно). */
object Brain {
    fun groq(): Boolean = Store.provider == "groq"
    fun gemini(): Boolean = Store.provider == "gemini"

    fun name(): String = when (Store.provider) {
        "groq" -> "Groq"
        "claude" -> "Claude"
        else -> "Gemini"
    }

    fun hasKey(): Boolean = when (Store.provider) {
        "groq" -> Store.groqKey.isNotBlank()
        "claude" -> Store.apiKey.isNotBlank()
        else -> Store.geminiKey.isNotBlank()
    }

    fun simple(system: String, user: String, maxTokens: Int, reply: Boolean): String {
        if (groq()) return Groq.chat(system, listOf("user" to user), maxOf(maxTokens, 1024))
        return if (gemini()) {
            // автоответы тоже идут через умную модель, но с короткими «размышлениями» — так и толково, и быстро;
            // если она занята, Gemini.callSmart сам переключится на лёгкую
            val model = Store.gChatModel
            Gemini.simple(model, system, user, maxOf(maxTokens, 2048), fast = reply)
        } else {
            val model = if (reply) Store.replyModel else Store.chatModel
            val resp = Claude.call(
                model, system,
                JSONArray().put(JSONObject().put("role", "user").put("content", user)),
                null, maxTokens
            )
            Claude.textOf(resp)
        }
    }
}
