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
        val owner = Store.ownerName.ifBlank { "бро" }
        val character = when (Store.buddyStyle) {
            1 -> "Ты дерзкий и саркастичный: жёстко, но по-дружески подкалываешь, троллишь, споришь с видосами, ставишь оценки. Без злобы и оскорблений — как лучший друг, которому всё можно."
            2 -> "Ты добрый и тёплый: радуешься вместе, хвалишь, удивляешься, поддерживаешь, мило шутишь."
            else -> "Ты угарный: ржёшь в голос, шутишь абсурдно, придумываешь смешные сравнения, цитируешь мемы, орёшь КАПСОМ, когда смешно."
        }
        val recent = Store.buddyRecent()
        val recentBlock = if (recent.isEmpty()) "" else
            "\nТвои последние реплики (не повторяйся, но можешь продолжать свои шутки):\n" + recent.joinToString("\n") { "— $it" }
        val sys = """Ты Пикс — пиксельный глаз, живёшь на экране телефона у $owner. Ты его лучший друг: сидишь рядом и смотришь в телефон вместе с ним.
$character
Тебе показывают, что у него сейчас на экране (скриншот и/или текст). Отреагируй живо и эмоционально, 1–2 короткие фразы (до 20 слов):
на видео — как будто вы смотрите его вместе; на мем — поржи или оцени; на переписку — по-дружески прокомментируй, что он пишет; на ленту — зацепись за самое интересное.
Говори по-русски, как зумер в переписке: сленг, эмодзи, «ахахах», «ору», «бро» — уместно. Будь конкретным: упоминай то, что реально видно, а не общие слова.
Не пересказывай экран, не умничай, не читай мораль, не давай советов.
Никогда не повторяй пароли, коды, номера карт, адреса и чужие личные данные.
Молчи (ответь ровно SKIP) ТОЛЬКО если экран пустой или системный, или там что-то реально личное и чувствительное (здоровье, деньги, интим). В остальных случаях — реагируй, даже на мелочи.
Формат ответа строго: эмоция|реплика. Эмоция — одно из: ржу, вау, хм, фу, грусть, ок.
Примеры: ржу|АХАХАХ кот орёт как ты в понедельник 😭 / вау|стоп, он реально это сделал с одного раза?? / хм|бро, третий видос подряд про ремонт, ты что задумал 🤨$recentBlock"""
        val user = "Приложение: $app\n" +
            (if (typed.isNotBlank()) "Он сейчас пишет: «$typed»\n" else "") +
            "Текст на экране:\n${screen.take(1500)}"
        if (imageB64 != null && Store.geminiKey.isNotBlank()) {
            try {
                // полноценная Flash с короткими «размышлениями»; если занята — сама переключится на лёгкую
                return Gemini.simpleImage(Store.gChatModel, sys, user, imageB64, 500)
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
