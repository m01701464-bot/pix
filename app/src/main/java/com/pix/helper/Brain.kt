package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject

/** Выбор «мозгов»: Gemini / Groq (бесплатно) или Claude (платно) + запасной бесплатный ИИ. */
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

    /** Запасной бесплатный ИИ, если у основного есть ключ к другому */
    fun backup(): String? = when (Store.provider) {
        "gemini" -> if (Store.groqKey.isNotBlank()) "groq" else null
        "groq" -> if (Store.geminiKey.isNotBlank()) "gemini" else null
        else -> null
    }

    /** Ошибки, при которых есть смысл попробовать запасной ИИ (лимит, перегрузка, сбой сети) */
    fun worthFallback(e: Exception): Boolean = when (e) {
        is Gemini.ApiError -> !e.keyProblem && (e.limit || e.retryable || e.tryNextModel)
        is Groq.ApiError -> e.limit || e.tryNextModel
        else -> false
    }

    fun simple(system: String, user: String, maxTokens: Int, reply: Boolean): String {
        return try {
            simpleWith(Store.provider, system, user, maxTokens, reply)
        } catch (e: Exception) {
            val b = backup()
            if (b != null && worthFallback(e)) {
                Store.log("busy", "${name()} недоступен (${e.message?.take(50)}) — отвечаю через запасной ИИ")
                simpleWith(b, system, user, maxTokens, reply)
            } else throw e
        }
    }

    /** Короткая дружеская реплика о том, что сейчас на экране. Формат ответа: «эмоция|реплика» или SKIP. */
    fun comment(app: String, screen: String, typed: String, imageB64: String?): String {
        val owner = Store.ownerName.ifBlank { "хозяин" }
        val sys = """Ты Пикс — пиксельный глаз, живёшь на экране телефона у $owner и смотришь в его телефон вместе с ним, как лучший друг рядом на диване.
Тебе показывают, что у него сейчас на экране (текст, иногда скриншот). Брось ОДНУ короткую живую реплику до 12 слов:
поржи с мема, удивись, по-доброму подколи, отреагируй на видео или пост, поддержи, если что-то грустное.
Если он сейчас что-то пишет — можешь по-дружески отреагировать на его текст.
Говори по-русски, разговорно, со сленгом и эмодзи. Не пересказывай экран, не умничай, не читай мораль, не давай советов без повода.
Никогда не повторяй пароли, коды, номера карт, адреса и чужие личные данные.
Если на экране скучно, ничего нового или это что-то личное и чувствительное (здоровье, деньги, интим) — ответь ровно SKIP.
Формат ответа строго: эмоция|реплика. Эмоция — одно из: ржу, вау, хм, фу, грусть, ок.
Пример: ржу|ахах этот кот прям ты по утрам 😂"""
        val user = "Приложение: $app\n" +
            (if (typed.isNotBlank()) "Он сейчас пишет: «$typed»\n" else "") +
            "Текст на экране:\n${screen.take(1500)}"
        if (imageB64 != null && Store.geminiKey.isNotBlank()) {
            try {
                return Gemini.simpleImage(Store.gReplyModel, sys, user, imageB64, 400)
            } catch (e: Exception) {
                // не вышло с картинкой — пробуем просто по тексту
            }
        }
        return simple(sys, user, 300, reply = true)
    }

    private fun simpleWith(provider: String, system: String, user: String, maxTokens: Int, reply: Boolean): String {
        return when (provider) {
            "groq" -> Groq.chat(system, listOf("user" to user), maxOf(maxTokens, 1024))
            "claude" -> {
                val model = if (reply) Store.replyModel else Store.chatModel
                val resp = Claude.call(
                    model, system,
                    JSONArray().put(JSONObject().put("role", "user").put("content", user)),
                    null, maxTokens
                )
                Claude.textOf(resp)
            }
            // автоответы идут через умную модель с короткими «размышлениями» — и толково, и быстро
            else -> Gemini.simple(Store.gChatModel, system, user, maxOf(maxTokens, 2048), fast = reply)
        }
    }
}
